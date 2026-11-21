package pleos.nextkey.core;

import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;
import pleos.nextkey.lifecycle.Snapshots;
import static pleos.nextkey.core.CoreData.*;

@Service
class ChallengeService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DevelopmentReviewHandoff reviewHandoff;
    private static final int FACTOR_ATTEMPT_LIMIT=3;
    private static final long FACTOR_LOCK_SECONDS=15;
    private final JdbcTemplate db;
    private final BookingService bookings;
    private final Credentials credentials;
    private final IdentityFactor factor;
    private final Optional<DevelopmentOverdueDeadline> demoDeadline;
    private final long challengeSeconds;
    private final long factorSeconds;
    private final boolean allowDemo;
    ChallengeService(JdbcTemplate db,BookingService bookings,Credentials credentials,IdentityFactor factor,
            @Value("${nextkey.auth.challenge-seconds:60}") long challengeSeconds,
            @Value("${nextkey.auth.factor-seconds:120}") long factorSeconds,
            org.springframework.core.env.Environment env,Optional<DevelopmentOverdueDeadline> demoDeadline) {
        this.db=db;this.bookings=bookings;this.credentials=credentials;this.factor=factor;
        this.demoDeadline=demoDeadline;
        if (challengeSeconds<1 || challengeSeconds>900 || factorSeconds<1 || factorSeconds>900)
            throw new IllegalArgumentException("Invalid challenge lifetime");
        this.challengeSeconds=challengeSeconds;this.factorSeconds=factorSeconds;
        this.allowDemo=env.matchesProfiles("dev & !prod");
    }

    @Transactional
    public Map<String,Object> create(Actor actor,String method,Map<String,Object> snapshot){
        actor.require("VEHICLE");
        if (!"QR".equals(method) && !"DIRECT".equals(method)) throw fail(400,"INVALID_AUTH_METHOD");
        var vehicle=lockVehicle(actor.id());
        String id=id(),poll=secret(),qr="QR".equals(method)?secret():null;
        Instant now=Instant.now();
        // Validate the raw payload before replacing any in-flight authentication.
        var input=snapshot==null ? Map.<String,Object>of() : snapshot;
        if (Json.write(input).length()>16384) throw fail(400,"SNAPSHOT_TOO_LARGE");
        Snapshots.assertSourcesAllowed(input,allowDemo);
        var normalized=Snapshots.normalize(input,string(vehicle,"energy_type"),now);
        db.update("UPDATE auth_challenge SET status='SUPERSEDED' WHERE vehicle_id=? AND status IN ('OPEN','SECOND_FACTOR','VERIFIED')",actor.id());
        db.update("""
            DELETE FROM auth_token WHERE session_id IN (
                SELECT id FROM rental_session WHERE vehicle_id=? AND cleanup_state<>'COMPLETE')
            """,actor.id());
        db.update("""
            INSERT INTO auth_challenge(id,vehicle_id,method,qr_hash,poll_hash,expires_at,status,start_input,dev_issued)
            VALUES (?,?,?,?,?,?,'OPEN',?,?)
            """,id,actor.id(),method,qr==null?null:hash(qr),hash(poll),sql(now.plusSeconds(challengeSeconds)),Json.write(normalized),allowDemo);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",id);result.put("pollSecret",poll);result.put("expiresAt",now.plusSeconds(challengeSeconds));
        result.put("serverTime",now);
        if (qr!=null) result.put("qrToken",qr);
        return result;
    }
    private Map<String,Object> lockVehicle(String vehicleId){
        var r=first(db,"SELECT * FROM vehicle WHERE id=? FOR UPDATE",vehicleId);
        if(r.isEmpty()) throw fail(404,"VEHICLE_NOT_FOUND");
        return r;
    }
    private Map<String,Object> lockChallenge(String id) {
        var ref=first(db,"SELECT vehicle_id FROM auth_challenge WHERE id=? AND dev_issued=?",id,allowDemo);
        if(ref.isEmpty()) throw fail(404,"CHALLENGE_NOT_FOUND");
        lockVehicle(string(ref,"vehicle_id"));
        return first(db,"SELECT * FROM auth_challenge WHERE id=? FOR UPDATE",id);
    }
    private void requireOpen(Map<String,Object> c,String method){
        if (!method.equals(c.get("method"))) throw fail(409,"AUTH_METHOD_MISMATCH");
        if (!"OPEN".equals(c.get("status"))) throw fail(409,"QR".equals(method)?"QR_ALREADY_USED":"CHALLENGE_ALREADY_USED");
        if (!instant(c.get("expires_at")).isAfter(Instant.now())) throw fail(410,"QR".equals(method)?"QR_EXPIRED":"CHALLENGE_EXPIRED");
    }

    @Transactional(noRollbackFor=ApiError.class)
    public Map<String,Object> qr(String id,Actor actor,String token,String reservationId){
        actor.require("CUSTOMER");
        var account=lockCustomer(actor.id());
        var c=lockChallenge(id);
        if(!matches(token,string(c,"qr_hash"))) throw fail(401,"INVALID_QR");
        requireOpen(c,"QR");
        return bind(c,actor,reservationId,account);
    }

    @Transactional(noRollbackFor=ApiError.class)
    public Map<String,Object> direct(String id,Actor device,String username,String password,String reservationId){
        device.require("VEHICLE");
        var ref=first(db,"SELECT * FROM auth_challenge WHERE id=? AND dev_issued=?",id,allowDemo);
        if (ref.isEmpty()) throw fail(404,"CHALLENGE_NOT_FOUND");
        if (!device.id().equals(ref.get("vehicle_id"))) throw fail(403,"DEVICE_MISMATCH");
        requireOpen(ref,"DIRECT");
        Actor customer=credentials.authenticate(username,password);
        customer.require("CUSTOMER");
        var account=lockCustomer(customer.id());
        var c=lockChallenge(id);
        if (!device.id().equals(c.get("vehicle_id"))) throw fail(403,"DEVICE_MISMATCH");
        requireOpen(c,"DIRECT");
        return bind(c,customer,reservationId,account);
    }

    private Map<String,Object> bind(Map<String,Object> c,Actor customer,String reservationId,Map<String,Object> account){
        if (!BookingService.validId(reservationId)) throw fail(400,"RESERVATION_ID_REQUIRED");
        Instant now=Instant.now();
        int attempts=availableFactorAttempts(account,now);
        String id=string(c,"id");
        factor.begin(customer.id(),id);
        Instant issuedAt=Instant.now();
        Instant expiry=issuedAt.plusSeconds(factorSeconds);
        db.update("UPDATE auth_challenge SET status='SECOND_FACTOR',account_id=?,reservation_id=?,second_expires_at=? WHERE id=?",
                customer.id(),reservationId,sql(expiry),id);
        return Map.of("state","SECOND_FACTOR_REQUIRED","expiresAt",expiry,"serverTime",issuedAt,
                "attemptLimit",FACTOR_ATTEMPT_LIMIT,"remainingAttempts",FACTOR_ATTEMPT_LIMIT-attempts,
                "lockSeconds",FACTOR_LOCK_SECONDS);
    }

    private Map<String,Object> lockCustomer(String account){
        var a=first(db,"SELECT * FROM account WHERE id=? AND enabled=TRUE AND role='CUSTOMER' FOR UPDATE",account);
        if(a.isEmpty()) throw fail(403,"ACCOUNT_DISABLED");
        return a;
    }

    /** Caller holds the account lock, so a new challenge cannot bypass the shared limit. */
    private int availableFactorAttempts(Map<String,Object> account,Instant now) {
        Instant until=account.get("factor_blocked_until")==null ? null : instant(account.get("factor_blocked_until"));
        if(until!=null && until.isAfter(now)) throw factorLimit(until,now);
        String id=string(account,"id");
        if(until!=null) {
            db.update("UPDATE account SET factor_attempts=0,factor_blocked_until=NULL WHERE id=?",id);
            db.update("UPDATE auth_challenge SET attempt_count=0 WHERE account_id=? AND status='SECOND_FACTOR'",id);
            return 0;
        }
        int attempts=((Number)account.get("factor_attempts")).intValue();
        if(attempts>=FACTOR_ATTEMPT_LIMIT) {
            // Also bring an in-flight counter created under an older policy into this limit.
            Instant retryAt=now.plusSeconds(FACTOR_LOCK_SECONDS);
            db.update("UPDATE account SET factor_blocked_until=? WHERE id=?",sql(retryAt),id);
            throw factorLimit(retryAt,now);
        }
        return attempts;
    }

    private ApiError factorLimit(Instant retryAt,Instant now) {
        long seconds=Math.max(1,(Duration.between(now,retryAt).toMillis()+999)/1000);
        return new ApiError(429,"SECOND_FACTOR_ATTEMPT_LIMIT",Map.of(
                "attemptLimit",FACTOR_ATTEMPT_LIMIT,"remainingAttempts",0,"lockSeconds",FACTOR_LOCK_SECONDS,
                "retryAt",retryAt.toString(),"retryAfterSeconds",seconds,"serverTime",now.toString()));
    }

    private ApiError beforeStart(Instant startsAt,Instant now) {
        return new ApiError(409,"BOARDING_TOO_EARLY",Map.of("availableAt",startsAt.toString(),"serverTime",now.toString()));
    }

    @Transactional(noRollbackFor=ApiError.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> verify(String id,Actor actor,String code){
        var ref=first(db,"SELECT account_id FROM auth_challenge WHERE id=? AND dev_issued=?",id,allowDemo);
        if(ref.isEmpty()) throw fail(404,"CHALLENGE_NOT_FOUND");
        if(ref.get("account_id")==null) throw fail(409,"SECOND_FACTOR_NOT_STARTED");
        var a=lockCustomer(string(ref,"account_id"));
        var c=lockChallenge(id);
        boolean qr="QR".equals(c.get("method"));
        actor.require(qr?"CUSTOMER":"VEHICLE");
        if (!actor.id().equals(c.get(qr?"account_id":"vehicle_id"))) throw fail(403,"CHALLENGE_FORBIDDEN");
        if (!"SECOND_FACTOR".equals(c.get("status"))) throw fail(409,"CHALLENGE_ALREADY_USED");
        Instant checkedAt=Instant.now();
        int attempts=availableFactorAttempts(a,checkedAt);
        if(!instant(c.get("second_expires_at")).isAfter(checkedAt)) throw fail(410,"SECOND_FACTOR_EXPIRED");
        String account=string(c,"account_id");
        if (!factor.verify(account,id,code)) {
            int global=attempts+1;
            Instant failedAt=Instant.now();
            Instant retryAt=global>=FACTOR_ATTEMPT_LIMIT ? failedAt.plusSeconds(FACTOR_LOCK_SECONDS) : null;
            db.update("UPDATE account SET factor_attempts=?,factor_blocked_until=? WHERE id=?",global,
                    retryAt==null?null:sql(retryAt),account);
            db.update("UPDATE auth_challenge SET attempt_count=attempt_count+1 WHERE id=?",id);
            if(retryAt!=null) throw factorLimit(retryAt,failedAt);
            throw new ApiError(401,"SECOND_FACTOR_INVALID",Map.of("attemptLimit",FACTOR_ATTEMPT_LIMIT,
                    "remainingAttempts",FACTOR_ATTEMPT_LIMIT-global,"lockSeconds",FACTOR_LOCK_SECONDS));
        }
        String reservation = string(c,"reservation_id");
        if (reviewHandoff != null) reservation = reviewHandoff.resolve(reservation, account, string(c,"vehicle_id"));
        var r=bookings.lookup(reservation,true);
        if (!account.equals(r.accountId())) throw fail(403,"RESERVATION_FORBIDDEN");
        if (!r.vehicleId().equals(c.get("vehicle_id"))) throw fail(409,"VEHICLE_MISMATCH");
        r=bookings.refresh(r);
        if ("CANCELLED".equals(r.status())) throw fail(409,"RESERVATION_CANCELLED");
        var session=first(db,"SELECT * FROM rental_session WHERE reservation_id=? FOR UPDATE",r.id());
        Instant now=Instant.now();
        if((session.isEmpty() || "ACTIVE".equals(session.get("state"))) && now.isBefore(r.startsAt()))
            throw beforeStart(r.startsAt(),now);
        String sid,state;
        if (session.isEmpty()){
            if(!now.isBefore(r.confirmedEndAt())) throw fail(409,"RESERVATION_EXPIRED");
            if(!first(db,"SELECT old_session_id FROM session_replacement WHERE new_reservation_id=? AND status<>'APPROVED' FOR UPDATE",r.id()).isEmpty())
                throw fail(409,"REPLACEMENT_PENDING");
            if (!first(db,"SELECT id FROM rental_session WHERE occupied_vehicle=? OR occupied_account=? FOR UPDATE",r.vehicleId(),r.accountId()).isEmpty())
                throw fail(409,"PREVIOUS_SESSION_REQUIRES_CLEANUP");
            var snapshot=json(c.get("start_input"));
            // Keep original validation reasons and make values that aged during verification unknown.
            for (var entry:snapshot.entrySet()) {
                if (entry.getValue() instanceof Map<?,?> m && "VALID".equals(m.get("status")) &&
                        Instant.parse(m.get("collectedAt").toString()).isBefore(now.minusSeconds(120))) {
                    Map<String,Object> stale=new LinkedHashMap<>();
                    m.forEach((key,value)->stale.put(key.toString(),value));
                    stale.remove("value");stale.remove("codes");stale.put("status","STALE");entry.setValue(stale);
                }
            }
            sid=id();state="ACTIVE";
            db.update("""
                INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,start_json)
                VALUES (?,?,?,?,'ACTIVE',?,?)
                """,sid,r.id(),r.accountId(),r.vehicleId(),sql(now),Json.write(snapshot));
        } else {
            if ("COMPLETE".equals(session.get("cleanup_state"))) throw fail(409,"SESSION_ALREADY_CLEANED");
            sid=string(session,"id");state=string(session,"state");
        }
        db.update("UPDATE account SET factor_attempts=0,factor_blocked_until=NULL WHERE id=?",account);
        db.update("UPDATE auth_challenge SET status='VERIFIED',reservation_id=?,session_id=?,expires_at=? WHERE id=?",r.id(),sid,sql(Instant.now().plusSeconds(challengeSeconds)),id);
        return Map.of("sessionId",sid,"state",state,"scope","APP_SESSION");
    }

    @Transactional
    public Map<String,Object> claim(String id,Actor actor,String secret){
        actor.require("VEHICLE");
        var c=lockChallenge(id);
        if (!actor.id().equals(c.get("vehicle_id")) || !matches(secret,string(c,"poll_hash"))) throw fail(403,"CLAIM_FORBIDDEN");
        if (!"VERIFIED".equals(c.get("status"))) throw fail(409,"CHALLENGE_NOT_CLAIMABLE");
        if (!instant(c.get("expires_at")).isAfter(Instant.now())) throw fail(410,"CLAIM_EXPIRED");
        String sid=string(c,"session_id");
        demoDeadline.ifPresent(deadline->deadline.onClaim(actor.id(),string(c,"reservation_id"),sid));
        var session=first(db,"SELECT cleanup_state FROM rental_session WHERE id=? FOR UPDATE",sid);
        if ("COMPLETE".equals(session.get("cleanup_state"))) throw fail(409,"SESSION_ALREADY_CLEANED");
        String token=credentials.issue(actor.id(),sid);
        db.update("UPDATE auth_challenge SET status='CLAIMED' WHERE id=?",id);
        return Map.of("sessionId",sid,"token",token,"type","Bearer");
    }

    @Transactional(noRollbackFor=ApiError.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> recovery(Actor actor){
        actor.require("VEHICLE","SESSION");
        boolean device="VEHICLE".equals(actor.role());
        var row=device ? first(db,"SELECT * FROM rental_session WHERE occupied_vehicle=?",actor.id())
            : first(db,"SELECT * FROM rental_session WHERE id=?",actor.id());
        if (row.isEmpty()) return Map.of("state","NEW_AUTH_REQUIRED");
        String state=string(row,"state");
        if ("ENDED".equals(state)) state="CLEANUP_PENDING";
        if (device) return Map.of("state",state,"requiresAuthentication",true);
        try {
            var booking=bookings.refresh(bookings.lockAllocation(string(row,"reservation_id")));
            if(!booking.accountId().equals(row.get("account_id")) || !booking.vehicleId().equals(row.get("vehicle_id"))
                    || !"CONFIRMED".equals(booking.status())) throw fail(403,"RECOVERY_REQUIRES_REAUTHENTICATION");
            Instant now=Instant.now();
            // Pending uploads and cleanup remain reachable; only ACTIVE use needs the start boundary.
            if("ACTIVE".equals(row.get("state")) && now.isBefore(booking.startsAt()))
                throw beforeStart(booking.startsAt(),now);
        } catch(ApiError e) {
            db.update("DELETE FROM auth_token WHERE session_id=?",actor.id());
            throw e;
        }
        return Map.of("state",state,"sessionId",actor.id(),"requiresAuthentication",false,
            "cleanupState",row.get("cleanup_state"),"reportState",row.get("report_state"),"returnState",row.get("return_state"));
    }
}
