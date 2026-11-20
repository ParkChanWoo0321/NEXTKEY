package pleos.nextkey.core;

import static pleos.nextkey.core.CoreData.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.Json;
import pleos.nextkey.lifecycle.ContractController;
import pleos.nextkey.lifecycle.DevelopmentReportFaults;
import pleos.nextkey.lifecycle.Snapshots;
import pleos.nextkey.records.PleosDemoRecords;

/** Owns only newly generated, marked demo fixtures. Runtime transitions use the normal domain entry points. */
@Service
@Profile("dev & !prod")
class PleosDemoService {
    record Options(String preset,String energyType,String destinationState,String returnPlaceState,
                   Boolean savedProfile,Boolean priorDamage,String customerPassword) {}
    enum Start { NORMAL, RETURN_30_MINUTES, RETURN_10_MINUTES, OVERDUE_AFTER_5_SECONDS, MISSING_START, EARLY, CANCELLED }
    record Names(String run,String customer,String otherCustomer,String vehicle,String otherVehicle,String operator) {
        List<String> accounts() { return List.of(customer,otherCustomer,vehicle,otherVehicle,operator); }
    }
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final Credentials credentials;
    private final BookingService bookings;
    private final ChallengeService challenges;
    private final ContractController contracts;
    private final Environment env;
    private final PleosDemoRecords phoneRecords;
    private final DevelopmentReportFaults reportFaults;

    PleosDemoService(JdbcTemplate db,PasswordEncoder passwords,Credentials credentials,BookingService bookings,
                     ChallengeService challenges,ContractController contracts,Environment env,
                     PleosDemoRecords phoneRecords,DevelopmentReportFaults reportFaults) {
        this.db=db;this.passwords=passwords;this.credentials=credentials;this.bookings=bookings;
        this.challenges=challenges;this.contracts=contracts;this.env=env;this.phoneRecords=phoneRecords;
        this.reportFaults=reportFaults;
    }

    @Transactional
    public Map<String,Object> create(Actor caller,Options input) {
        admin(caller);
        return createFixture(input,caller.id());
    }

    @Transactional
    public Map<String,Object> reset(Actor caller,String run,Options input) {
        Names old=authorize(caller,run);
        var control=first(db,"SELECT * FROM pleos_demo_run WHERE run_id=? FOR UPDATE",run);
        if(control.isEmpty())throw fail(409,"DEMO_RECREATE_REQUIRED");
        if(caller.role().equals("OPERATOR")&&!caller.id().equals(control.get("presenter_id")))
            throw fail(403,"DEMO_PRESENTER_REQUIRED");
        String digest=resetDigest(input);
        Map<String,Object> result;
        if(control.get("reset_to_run")!=null) {
            // The expired vehicle bearer never gains a recovery exception. Only the original presenter can recover.
            caller.require("OPERATOR");
            if(!digest.equals(control.get("reset_digest")))throw fail(409,"DEMO_RESET_PAYLOAD_CHANGED");
            Names next=names(string(control,"reset_to_run"));
            var successor=first(db,"SELECT reset_to_run FROM pleos_demo_run WHERE run_id=? FOR UPDATE",next.run());
            if(successor.isEmpty()||successor.get("reset_to_run")!=null)throw fail(409,"DEMO_RESET_TARGET_RETIRED");
            if(input!=null&&input.customerPassword()!=null) {
                var password=first(db,"SELECT password_hash FROM account WHERE id=?",next.customer());
                if(!passwords.matches(input.customerPassword(),string(password,"password_hash")))throw fail(409,"DEMO_RESET_PAYLOAD_CHANGED");
            }
            result=publicRun(next);
            // An approved exchange may have moved this run to its second vehicle before the presenter retries.
            result.put("vehicleToken",credentials.issue(string(result,"vehicleId"),null));
        } else {
            result=createFixture(input,string(control,"presenter_id"));
            db.update("UPDATE pleos_demo_run SET reset_to_run=?,reset_digest=? WHERE run_id=?",
                    result.get("runId"),digest,run);
        }
        // A reset starts another isolated fixture; it does not falsify an old server END/cleanup ACK.
        for(String account:old.accounts())
            db.update("UPDATE auth_token SET expires_at=UTC_TIMESTAMP(6) WHERE account_id=?",account);
        result.put("previousRunId",old.run());
        result.put("previousRecordsRetained",true);
        return result;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> status(Actor caller,String run) {
        return publicRun(authorize(caller,run));
    }

    private Map<String,Object> createFixture(Options raw,String presenter) {
        localOnly();
        Options input=raw==null?new Options(null,null,null,null,null,null,null):raw;
        Start start=start(input.preset());
        String energy=input.energyType()==null?"BATTERY":input.energyType();
        if(!Set.of("BATTERY","FUEL").contains(energy))throw fail(400,"INVALID_DEMO_ENERGY");
        String directPassword=input.customerPassword();
        if(directPassword!=null&&(directPassword.length()<12||directPassword.getBytes(StandardCharsets.UTF_8).length>72))
            throw fail(400,"INVALID_DEMO_CUSTOMER_PASSWORD");
        // Validate optional places before writing anything.
        var destination=place(input.destinationState(),false);
        var returnPlace=place(input.returnPlaceState(),true);
        Names n=names(UUID.randomUUID().toString());
        Map<String,String> roles=new LinkedHashMap<>();
        roles.put(n.customer(),"CUSTOMER");roles.put(n.otherCustomer(),"CUSTOMER");
        roles.put(n.vehicle(),"VEHICLE");roles.put(n.otherVehicle(),"VEHICLE");roles.put(n.operator(),"OPERATOR");
        // Random IDs and hashes, INSERT only: a collision fails instead of editing any existing account.
        for(var role:roles.entrySet()) {
            String password="CUSTOMER".equals(role.getValue()) && directPassword!=null ? directPassword : secret();
            db.update("INSERT INTO account(id,role,username,password_hash,is_demo) VALUES(?,?,?,?,TRUE)",
                    role.getKey(),role.getValue(),role.getKey(),passwords.encode(password));
        }
        for(String vehicle:List.of(n.vehicle(),n.otherVehicle())) {
            db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,?,?,?)",vehicle,
                    "PLEOS_DEMO:"+start.name(),energy,"PLEOS_DEMO:"+n.run());
            capabilities(vehicle);
        }
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        Actor office=new Actor(n.operator(),"OPERATOR");
        if(Boolean.TRUE.equals(input.priorDamage()))previousDamage(n,office,energy,now);
        if(Boolean.TRUE.equals(input.savedProfile()))previousProfile(n,now);
        long seconds=switch(start){case RETURN_30_MINUTES->1800;case RETURN_10_MINUTES->600;
            case OVERDUE_AFTER_5_SECONDS->86400;default->7200;};
        Instant begins=start==Start.EARLY?now.plusSeconds(3600):now.minusSeconds(60);
        Instant ends=start==Start.EARLY?now.plusSeconds(7200):now.plusSeconds(seconds);
        var reservation=bookings.create(office,new ReservationView(null,n.customer(),n.vehicle(),null,
                begins,ends,destination,returnPlace));
        if(start==Start.CANCELLED)bookings.update(reservation.id(),office,"cancel",null);
        db.update("INSERT INTO pleos_demo_run(run_id,presenter_id,reservation_id,prepared_end_at,created_at) VALUES(?,?,?,?,?)",
                n.run(),presenter,reservation.id(),sql(reservation.confirmedEndAt()),sql(now));
        var result=publicRun(n);
        // Only a vehicle-scoped bearer crosses this boundary. Phone/operator identities remain server-side.
        result.put("vehicleToken",credentials.issue(n.vehicle(),null));
        return result;
    }

    private void localOnly() {
        if(!env.matchesProfiles("dev & !prod"))throw fail(404,"DEMO_DISABLED");
        if(!"LOCAL".equalsIgnoreCase(env.getProperty("nextkey.reservations.source","LOCAL")))
            throw fail(409,"DEMO_LOCAL_RESERVATION_SOURCE_REQUIRED");
    }

    private void capabilities(String vehicle) {
        for(var e:Map.of("CLIMATE_TARGET_C",List.of(18,19,20,21,22,23,24,25,26,27,28),
                "CLIMATE_FAN_LEVEL",List.of(0,1,2,3,4,5),"SEAT_MEMORY_SLOT",List.of(1,2)).entrySet())
            db.update("INSERT INTO vehicle_setting_capability(vehicle_id,setting_name,allowed_values) VALUES(?,?,?)",
                    vehicle,e.getKey(),Json.write(Map.of("values",e.getValue())));
        db.update("INSERT INTO setting_capability_detail(vehicle_id,setting_name,detail) VALUES(?,?,?)",vehicle,
                "SEAT_FORE_AFT_POSITION",Json.write(Map.of("values",List.of(),"accessType","READ","area","ROW_1_LEFT","supportOrigin","REGISTERED")));
        boolean alternate=vehicle.endsWith("-car2");
        db.update("INSERT INTO setting_capability_detail(vehicle_id,setting_name,detail) VALUES(?,?,?)",vehicle,
                "CLIMATE_TARGET_C",Json.write(Map.of("values",List.of(),"accessType","READ_WRITE","area","ROW_1_LEFT",
                        "minValue",alternate?17:18,"maxValue",alternate?29:28,"step",new BigDecimal("0.5"),"supportOrigin","REGISTERED")));
    }

    private void previousProfile(Names n,Instant now) {
        db.update("INSERT INTO setting_profile(account_id,compatibility_key,source_vehicle_id,observed_at,source,readings,observation_details,memory_reference) VALUES(?,?,?,?,?,?,?,?)",
                n.customer(),"PLEOS_DEMO:"+n.run(),n.vehicle(),sql(now.minusSeconds(3600)),"DEV_SIMULATOR",
                Json.write(Map.of("CLIMATE_TARGET_C",21,"SEAT_FORE_AFT_POSITION",48,"SEAT_MEMORY_SLOT",1)),
                Json.write(Map.of("CLIMATE_TARGET_C",Map.of("status","OK","unit","C"),
                        "SEAT_FORE_AFT_POSITION",Map.of("status","OK","area","ROW_1_LEFT","unit","native-position"))),
                Json.write(Map.of("vehicleId",n.vehicle(),"slot",1,"referenceSource","VERIFIED_EXISTING_VEHICLE_MEMORY_REFERENCE",
                        "expectedSeatPosition",48,"verification","CLIENT_REPORTED")));
    }

    private void previousDamage(Names n,Actor office,String energy,Instant now) {
        var old=bookings.create(office,new ReservationView(null,n.otherCustomer(),n.vehicle(),null,
                now.minusSeconds(10800),now.minusSeconds(7200),null,null));
        String session=id();
        Instant began=now.minusSeconds(10700),ended=now.minusSeconds(7300);
        db.update("""
            INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,ended_at,start_json,end_json,cleanup_state)
            VALUES(?,?,?,?,'ENDED',?,?,?,?,'COMPLETE')
            """,session,old.id(),n.otherCustomer(),n.vehicle(),sql(began),sql(ended),
                Json.write(Snapshots.normalize(sample(energy,began,false),energy,began)),
                Json.write(Snapshots.normalize(sample(energy,ended,false),energy,ended)));
        db.update("""
            INSERT INTO damage_report(id,session_id,vehicle_id,category,description,discovered_at,reported_at,verification,part_code)
            VALUES(?,?,?,'SCRATCH',?,?,?,'UNVERIFIED','LEFT_FRONT')
            """,id(),session,n.vehicle(),"가상 시연용 이전 외관 제보. 손상 발생 시점·책임 판정이 아닙니다.",
                sql(now.minusSeconds(9000)),sql(now.minusSeconds(8900)));
    }

    public Map<String,Object> bootstrap(Actor caller,String run,Map<String,Object> requestedSnapshot) {
        Names n=authorize(caller,run);
        requireCurrentRun(n);
        String vehicle=caller.role().equals("VEHICLE")?caller.id():currentReservation(n).vehicleId();
        authenticationReservation(n,vehicle);
        Start start=start(model(n.vehicle()));
        String energy=db.queryForObject("SELECT energy_type FROM vehicle WHERE id=?",String.class,vehicle);
        var snapshot=requestedSnapshot==null?sample(energy,Instant.now(),start==Start.MISSING_START):requestedSnapshot;
        // Each proxied domain call commits normally. Repeating after response loss reauthenticates the same ACTIVE.
        var ch=challenges.create(new Actor(vehicle,"VEHICLE"),"QR",snapshot);
        phone(caller,run,(String)ch.get("id"),(String)ch.get("qrToken"));
        var claimed=new LinkedHashMap<>(challenges.claim((String)ch.get("id"),new Actor(vehicle,"VEHICLE"),(String)ch.get("pollSecret")));
        requireCurrentRun(n);
        claimed.put("runId",n.run());
        claimed.put("state",db.queryForObject("SELECT state FROM rental_session WHERE id=?",String.class,claimed.get("sessionId")));
        return claimed;
    }

    private static String resetDigest(Options raw) {
        Options r=raw==null?new Options(null,null,null,null,null,null,null):raw;
        if(r.customerPassword()!=null&&(r.customerPassword().length()<12||r.customerPassword().getBytes(StandardCharsets.UTF_8).length>72))
            throw fail(400,"INVALID_DEMO_CUSTOMER_PASSWORD");
        // Do not persist a password or a fast digest of a password as part of the request fingerprint.
        return hash(Json.write(new TreeMap<>(Map.of("preset",start(r.preset()).name(),"energyType",r.energyType()==null?"BATTERY":r.energyType(),
                "destinationState",r.destinationState()==null?"KNOWN":r.destinationState(),
                "returnPlaceState",r.returnPlaceState()==null?"KNOWN":r.returnPlaceState(),
                "savedProfile",Boolean.TRUE.equals(r.savedProfile()),"priorDamage",Boolean.TRUE.equals(r.priorDamage()),
                "customerPasswordProvided",r.customerPassword()!=null))));
    }

    public Map<String,Object> phone(Actor caller,String run,String challenge,String qrToken) {
        Names n=authorize(caller,run);
        requireCurrentRun(n);
        var row=first(db,"SELECT * FROM auth_challenge WHERE id=? AND dev_issued=TRUE",challenge);
        if(row.isEmpty()||!List.of(n.vehicle(),n.otherVehicle()).contains(row.get("vehicle_id")))throw fail(404,"DEMO_CHALLENGE_NOT_FOUND");
        String vehicle=string(row,"vehicle_id");
        if(caller.role().equals("VEHICLE")&&!caller.id().equals(vehicle))throw fail(403,"DEMO_WRONG_VEHICLE");
        if(!"QR".equals(row.get("method"))||!matches(qrToken,string(row,"qr_hash")))throw fail(401,"INVALID_QR");
        var reservation=authenticationReservation(n,vehicle);
        Actor customer=new Actor(reservation.accountId(),"CUSTOMER");
        String state=string(row,"status");
        if("OPEN".equals(state))challenges.qr(challenge,customer,qrToken,reservation.id());
        else if(!customer.id().equals(row.get("account_id"))||!reservation.id().equals(row.get("reservation_id")))
            throw fail(409,"DEMO_CHALLENGE_BINDING_CHANGED");
        if(Set.of("VERIFIED","CLAIMED").contains(state)) {
            String sid=string(row,"session_id");
            return Map.of("sessionId",sid,"state",db.queryForObject("SELECT state FROM rental_session WHERE id=?",String.class,sid),"scope","APP_SESSION");
        }
        return challenges.verify(challenge,customer,env.getRequiredProperty("NEXTKEY_DEV_OTP"));
    }

    public Map<String,Object> transition(Actor caller,String run,String requested) {
        Names n=authorize(caller,run);
        requireCurrentRun(n);
        var original=initialReservation(n);
        String preset=requested==null?"":requested;
        var target=Set.of("NEW_DAMAGE","REPORT_FAILED").contains(preset)?currentReservation(n):original;
        var session=first(db,"SELECT id,state FROM rental_session WHERE reservation_id=?",target.id());
        if(session.isEmpty())throw fail(409,"DEMO_BOOTSTRAP_REQUIRED");
        String sid=string(session,"id");
        Actor customer=new Actor(target.accountId(),"CUSTOMER"),office=new Actor(n.operator(),"OPERATOR");
        Map<String,Object> detail;
        if(Set.of("EXTENSION_PENDING","EXTENSION_APPROVED","EXTENSION_REJECTED").contains(preset)) {
            UUID eid=UUID.nameUUIDFromBytes(("pleos-extension:"+n.run()).getBytes(StandardCharsets.UTF_8));
            var prior=first(db,"SELECT status,requested_end_at FROM rental_extension WHERE id=?",eid.toString());
            if(prior.isEmpty())as(customer,()->contracts.create(original.id(),new ContractController.Extension(eid,original.confirmedEndAt().plusSeconds(3600))));
            else if(Set.of("APPROVED","REJECTED").contains(prior.get("status")) &&
                    !(preset.equals("EXTENSION_APPROVED")&&"APPROVED".equals(prior.get("status"))) &&
                    !(preset.equals("EXTENSION_REJECTED")&&"REJECTED".equals(prior.get("status"))))throw fail(409,"DEMO_RESET_REQUIRED");
            var quote=new ContractController.Quote(new BigDecimal("5000.00"),"KRW");
            as(office,()->contracts.quote(eid,quote));as(customer,()->contracts.agree(eid,quote));
            if(!preset.equals("EXTENSION_PENDING"))as(office,()->contracts.decide(eid,
                    new ContractController.Decision(preset.equals("EXTENSION_APPROVED"),"가상 시연 결정")));
            detail=as(customer,()->contracts.sessionContract(sid));
        } else if(Set.of("EXCHANGE_PENDING","EXCHANGE_PREAPPROVED","USER_CHANGE_PENDING","USER_CHANGE_PREAPPROVED").contains(preset)) {
            boolean user=preset.startsWith("USER_CHANGE");
            String nextCustomer=user?n.otherCustomer():n.customer(),nextVehicle=user?n.vehicle():n.otherVehicle();
            var prepared=first(db,"SELECT new_reservation_id FROM session_replacement WHERE old_session_id=?",sid);
            Instant nextEnd=original.confirmedEndAt().isAfter(Instant.now().plusSeconds(3600))?
                    original.confirmedEndAt():Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MICROS);
            var next=prepared.isEmpty()?bookings.replacement(office,sid,new ReservationView(null,nextCustomer,nextVehicle,null,
                    original.startsAt(),nextEnd,original.destination(),original.returnPlace())):
                    bookings.lookup(string(prepared,"new_reservation_id"),false);
            if(!nextCustomer.equals(next.accountId())||!nextVehicle.equals(next.vehicleId()))throw fail(409,"DEMO_RESET_REQUIRED");
            if(preset.endsWith("PREAPPROVED"))as(office,()->contracts.preapproveReplacement(sid,new ContractController.Replacement(next.id())));
            detail=as(customer,()->contracts.sessionContract(sid));
        } else if(Set.of("HANDOFF_APPROVED","HANDOFF_UNAVAILABLE").contains(preset)) {
            var handoff=first(db,"SELECT new_reservation_id FROM session_replacement WHERE old_session_id=?",sid);
            if(handoff.isEmpty())throw fail(409,"DEMO_HANDOFF_NOT_PREPARED");
            String next=string(handoff,"new_reservation_id");
            owned(n,bookings.lookup(next,false));
            if(preset.equals("HANDOFF_APPROVED"))as(office,()->contracts.approveReplacement(sid,new ContractController.Replacement(next)));
            else if(!"CANCELLED".equals(bookings.lookup(next,false).status()))bookings.update(next,office,"cancel",null);
            detail=as(customer,()->contracts.sessionContract(sid));
        } else if(preset.equals("REPORT_FAILED")) {
            detail=reportFaults.arm(sid);
        } else if(preset.equals("NEW_DAMAGE")) {
            String key=UUID.nameUUIDFromBytes(("pleos-damage:"+n.run()).getBytes(StandardCharsets.UTF_8)).toString();
            detail=as(customer,()->phoneRecords.incomingDamage(sid,key));
        } else throw fail(400,"INVALID_DEMO_TRANSITION");
        String resultField=preset.equals("NEW_DAMAGE")?"damage":preset.equals("REPORT_FAILED")?"report":"contract";
        return Map.of("runId",n.run(),"preset",preset,"sessionId",sid,resultField,detail);
    }

    @Transactional
    public Map<String,Object> replacementVehicle(Actor caller,String run) {
        Names n=authorize(caller,run);
        requireCurrentRun(n);
        var original=initialReservation(n);
        var old=first(db,"SELECT id,state,cleanup_state FROM rental_session WHERE reservation_id=? FOR UPDATE",original.id());
        if(!"ENDED".equals(old.get("state"))||!"COMPLETE".equals(old.get("cleanup_state")))throw fail(409,"OLD_SESSION_NOT_CLEARED");
        var replacement=first(db,"SELECT new_reservation_id,status FROM session_replacement WHERE old_session_id=?",old.get("id"));
        if(!"APPROVED".equals(replacement.get("status")))throw fail(409,"REPLACEMENT_PENDING");
        var next=bookings.lookup(string(replacement,"new_reservation_id"),false);
        if(!List.of(n.vehicle(),n.otherVehicle()).contains(next.vehicleId())||
                !List.of(n.customer(),n.otherCustomer()).contains(next.accountId()))throw fail(403,"DEMO_RUN_MISMATCH");
        return Map.of("runId",n.run(),"vehicleId",next.vehicleId(),"vehicleToken",credentials.issue(next.vehicleId(),null),
                "reservation",next,"directUsername",next.accountId());
    }

    private ReservationView authenticationReservation(Names n,String vehicle) {
        var original=initialReservation(n);
        var old=first(db,"SELECT id,cleanup_state FROM rental_session WHERE reservation_id=?",original.id());
        if(!old.isEmpty()&&"COMPLETE".equals(old.get("cleanup_state"))) {
            var next=first(db,"SELECT new_reservation_id,status FROM session_replacement WHERE old_session_id=?",old.get("id"));
            if(!"APPROVED".equals(next.get("status")))throw fail(409,"DEMO_RESET_REQUIRED");
            var result=owned(n,bookings.lookup(string(next,"new_reservation_id"),false));
            if(!vehicle.equals(result.vehicleId()))throw fail(409,"DEMO_USE_REPLACEMENT_VEHICLE");
            return result;
        }
        if(!vehicle.equals(original.vehicleId()))throw fail(409,"DEMO_REPLACEMENT_NOT_READY");
        return original;
    }

    private ReservationView initialReservation(Names n) {
        var row=first(db,"SELECT id FROM reservation WHERE account_id=? AND vehicle_id=? ORDER BY starts_at DESC LIMIT 1",n.customer(),n.vehicle());
        if(row.isEmpty())throw fail(404,"DEMO_RUN_NOT_FOUND");
        return bookings.lookup(string(row,"id"),false);
    }

    private Map<String,Object> publicRun(Names n) {
        var r=currentReservation(n);
        var out=new LinkedHashMap<String,Object>();
        out.put("runId",n.run());out.put("preset",model(n.vehicle()));out.put("vehicleId",r.vehicleId());out.put("reservation",r);
        out.put("directUsername",r.accountId());
        var s=first(db,"SELECT id,state,cleanup_state FROM rental_session WHERE reservation_id=?",r.id());
        if(!s.isEmpty()){out.put("sessionId",s.get("id"));out.put("state",s.get("state"));out.put("cleanupState",s.get("cleanup_state"));}
        else out.put("state","NEW_AUTH_REQUIRED");
        return out;
    }

    private ReservationView currentReservation(Names n) {
        var r=initialReservation(n);
        var old=first(db,"SELECT id,cleanup_state FROM rental_session WHERE reservation_id=?",r.id());
        if("COMPLETE".equals(old.get("cleanup_state"))) {
            var next=first(db,"SELECT new_reservation_id,status FROM session_replacement WHERE old_session_id=?",old.get("id"));
            if("APPROVED".equals(next.get("status")))r=owned(n,bookings.lookup(string(next,"new_reservation_id"),false));
        }
        return r;
    }

    private ReservationView owned(Names n,ReservationView r) {
        if(!List.of(n.customer(),n.otherCustomer()).contains(r.accountId())||
                !List.of(n.vehicle(),n.otherVehicle()).contains(r.vehicleId()))throw fail(403,"DEMO_RUN_MISMATCH");
        return r;
    }

    private String model(String vehicle) {
        String value=db.queryForObject("SELECT model FROM vehicle WHERE id=?",String.class,vehicle);
        if(value==null||!value.startsWith("PLEOS_DEMO:"))throw fail(404,"DEMO_RUN_NOT_FOUND");
        return value.substring("PLEOS_DEMO:".length());
    }

    private void admin(Actor actor) {
        localOnly();actor.require("OPERATOR");demoAccount(actor.id(),"OPERATOR");
    }

    private Names authorize(Actor caller,String run) {
        localOnly();Names n=names(run);
        if(caller.role().equals("OPERATOR"))demoAccount(caller.id(),"OPERATOR");
        else {
            caller.require("VEHICLE");
            if(!List.of(n.vehicle(),n.otherVehicle()).contains(caller.id()))throw fail(403,"DEMO_WRONG_VEHICLE");
        }
        demoAccount(n.customer(),"CUSTOMER");demoAccount(n.otherCustomer(),"CUSTOMER");
        demoAccount(n.vehicle(),"VEHICLE");demoAccount(n.otherVehicle(),"VEHICLE");demoAccount(n.operator(),"OPERATOR");
        model(n.vehicle());model(n.otherVehicle());
        return n;
    }

    private void demoAccount(String id,String role) {
        var row=first(db,"SELECT role,is_demo FROM account WHERE id=? AND enabled=TRUE",id);
        if(!role.equals(row.get("role"))||!Boolean.TRUE.equals(row.get("is_demo")))throw fail(403,"DEMO_ACCOUNT_REQUIRED");
    }

    private void requireCurrentRun(Names n) {
        var control=first(db,"SELECT reset_to_run FROM pleos_demo_run WHERE run_id=?",n.run());
        if(control.isEmpty())throw fail(409,"DEMO_RECREATE_REQUIRED");
        if(control.get("reset_to_run")!=null)throw fail(409,"DEMO_RUN_RETIRED");
    }

    static Names names(String run) {
        try { if(run==null||!UUID.fromString(run).toString().equals(run))throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e){throw fail(400,"INVALID_DEMO_RUN");}
        String p="pleos-"+run;
        return new Names(run,p+"-customer",p+"-customer2",p+"-car",p+"-car2",p+"-operator");
    }

    static Start start(String value) {
        try { return value==null?Start.NORMAL:Start.valueOf(value); }
        catch(IllegalArgumentException e){throw fail(400,"INVALID_DEMO_PRESET");}
    }

    private static ReservationView.Place place(String state,boolean returning) {
        if(state==null)state="KNOWN";
        if("NONE".equals(state))return null;
        if(!Set.of("KNOWN","UNKNOWN").contains(state))throw fail(400,"INVALID_DEMO_PLACE_STATE");
        return new ReservationView.Place(returning?"가상 시연 반납 장소":"가상 시연 목적지","가상 시연 주소",
                "KNOWN".equals(state)?37.5665:null,"KNOWN".equals(state)?126.9780:null,null);
    }

    static Map<String,Object> sample(String energy,Instant time,boolean missingStart) {
        var out=new LinkedHashMap<String,Object>();
        if(!missingStart)out.put("odometer",Map.of("source","DEV_SIMULATOR","value",32230,"unit","km","status","VALID","collectedAt",time.toString()));
        out.put(energy.equals("FUEL")?"fuel":"battery",Map.of("source","DEV_SIMULATOR","value",energy.equals("FUEL")?60:78,"unit","%","status","VALID","collectedAt",time.toString()));
        out.put("warnings",Map.of("source","DEV_SIMULATOR","codes",List.of(),"status","VALID","collectedAt",time.toString()));
        return out;
    }

    /** A scoped stand-in for the phone/office, always restoring the authenticated vehicle caller. */
    static <T> T as(Actor actor,Supplier<T> operation) {
        var original=SecurityContextHolder.getContext();
        try {
            var temporary=SecurityContextHolder.createEmptyContext();
            temporary.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,
                    List.of(new SimpleGrantedAuthority("ROLE_"+actor.role()))));
            SecurityContextHolder.setContext(temporary);
            return operation.get();
        } finally {SecurityContextHolder.setContext(original);}
    }
}
