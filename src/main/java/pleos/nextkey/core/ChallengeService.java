package pleos.nextkey.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final JdbcTemplate db;
    private final BookingService bookings;
    private final Credentials credentials;
    private final IdentityFactor factor;
    private final long challengeSeconds;
    private final long factorSeconds;
    private final boolean allowDemo;
    ChallengeService(JdbcTemplate db,BookingService bookings,Credentials credentials,IdentityFactor factor,
            @Value("${nextkey.auth.challenge-seconds:180}") long challengeSeconds,
            @Value("${nextkey.auth.factor-seconds:120}") long factorSeconds,
            org.springframework.core.env.Environment env) {
        this.db=db;this.bookings=bookings;this.credentials=credentials;this.factor=factor;
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
            DELETE t FROM auth_token t JOIN rental_session s ON s.id=t.session_id
            WHERE s.vehicle_id=? AND s.cleanup_state<>'COMPLETE'
            """,actor.id());
        db.update("""
            INSERT INTO auth_challenge(id,vehicle_id,method,qr_hash,poll_hash,expires_at,status,start_input,dev_issued)
            VALUES (?,?,?,?,?,?,'OPEN',?,?)
            """,id,actor.id(),method,qr==null?null:hash(qr),hash(poll),sql(now.plusSeconds(challengeSeconds)),Json.write(normalized),allowDemo);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",id);result.put("pollSecret",poll);result.put("expiresAt",now.plusSeconds(challengeSeconds));
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

    @Transactional
    public Map<String,Object> qr(String id,Actor actor,String token,String reservationId){
        actor.require("CUSTOMER");
        lockCustomer(actor.id());
        var c=lockChallenge(id);
        if(!matches(token,string(c,"qr_hash"))) throw fail(401,"INVALID_QR");
        requireOpen(c,"QR");
        return bind(c,actor,reservationId);
    }

    @Transactional
    public Map<String,Object> direct(String id,Actor device,String username,String password,String reservationId){
        device.require("VEHICLE");
        var ref=first(db,"SELECT * FROM auth_challenge WHERE id=? AND dev_issued=?",id,allowDemo);
        if (ref.isEmpty()) throw fail(404,"CHALLENGE_NOT_FOUND");
        if (!device.id().equals(ref.get("vehicle_id"))) throw fail(403,"DEVICE_MISMATCH");
        requireOpen(ref,"DIRECT");
        Actor customer=credentials.authenticate(username,password);
        customer.require("CUSTOMER");
        lockCustomer(customer.id());
        var c=lockChallenge(id);
        if (!device.id().equals(c.get("vehicle_id"))) throw fail(403,"DEVICE_MISMATCH");
        requireOpen(c,"DIRECT");
        return bind(c,customer,reservationId);
    }

    private Map<String,Object> bind(Map<String,Object> c,Actor customer,String reservationId){
        if (!BookingService.validId(reservationId)) throw fail(400,"RESERVATION_ID_REQUIRED");
        String id=string(c,"id");
        factor.begin(customer.id(),id);
        Instant expiry=Instant.now().plusSeconds(factorSeconds);
        db.update("UPDATE auth_challenge SET status='SECOND_FACTOR',account_id=?,reservation_id=?,second_expires_at=? WHERE id=?",
                customer.id(),reservationId,sql(expiry),id);
        return Map.of("state","SECOND_FACTOR_REQUIRED","expiresAt",expiry);
    }

    private Map<String,Object> lockCustomer(String account){
        var a=first(db,"SELECT * FROM account WHERE id=? AND enabled=TRUE AND role='CUSTOMER' FOR UPDATE",account);
        if(a.isEmpty()) throw fail(403,"ACCOUNT_DISABLED");
        return a;
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
        if(!instant(c.get("second_expires_at")).isAfter(Instant.now())) throw fail(410,"SECOND_FACTOR_EXPIRED");
        String account=string(c,"account_id");
        int attempts=((Number)c.get("attempt_count")).intValue();
        var blocked=a.get("factor_blocked_until");
        if(attempts>=5 || (blocked!=null && instant(blocked).isAfter(Instant.now()))) throw fail(429,"SECOND_FACTOR_ATTEMPT_LIMIT");
        if (!factor.verify(account,id,code)) {
            int global=blocked==null ? ((Number)a.get("factor_attempts")).intValue()+1 : 1;
            db.update("UPDATE account SET factor_attempts=?,factor_blocked_until=? WHERE id=?",global,
                    global>=5?sql(Instant.now().plusSeconds(300)):null,account);
            db.update("UPDATE auth_challenge SET attempt_count=attempt_count+1 WHERE id=?",id);
            throw fail(attempts+1>=5 || global>=5 ?429:401,attempts+1>=5 || global>=5?"SECOND_FACTOR_ATTEMPT_LIMIT":"SECOND_FACTOR_INVALID");
        }
        var r=bookings.lookup(string(c,"reservation_id"),true);
        if (!account.equals(r.accountId())) throw fail(403,"RESERVATION_FORBIDDEN");
        if (!r.vehicleId().equals(c.get("vehicle_id"))) throw fail(409,"VEHICLE_MISMATCH");
        r=bookings.refresh(r);
        if ("CANCELLED".equals(r.status())) throw fail(409,"RESERVATION_CANCELLED");
        var session=first(db,"SELECT * FROM rental_session WHERE reservation_id=? FOR UPDATE",r.id());
        String sid,state;
        if (session.isEmpty()){
            Instant now=Instant.now();
            if(now.isBefore(r.startsAt())) throw fail(409,"BOARDING_TOO_EARLY");
            if(!now.isBefore(r.confirmedEndAt())) throw fail(409,"RESERVATION_EXPIRED");
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
        db.update("UPDATE auth_challenge SET status='VERIFIED',session_id=?,expires_at=? WHERE id=?",sid,sql(Instant.now().plusSeconds(challengeSeconds)),id);
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
        } catch(ApiError e) {
            db.update("DELETE FROM auth_token WHERE session_id=?",actor.id());
            throw e;
        }
        return Map.of("state",state,"sessionId",actor.id(),"requiresAuthentication",false,
            "cleanupState",row.get("cleanup_state"),"reportState",row.get("report_state"),"returnState",row.get("return_state"));
    }
}
