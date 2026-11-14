package pleos.nextkey.core;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
class AuthenticationEdgesTests {
    @Autowired JdbcTemplate db;
    @Autowired ChallengeService challenges;
    @Autowired Credentials credentials;
    @Autowired BookingService bookings;
    @Autowired PasswordEncoder encoder;
    private Actor device,customer;
    private String otherVehicle;
    private String otp;
    private Instant now;

    @BeforeEach void provisionIsolatedBookingOwners(){
        assertEquals("nextkey_test",db.queryForObject("SELECT DATABASE()",String.class));
        String prefix="edge-"+UUID.randomUUID();
        device=new Actor(prefix+"-car","VEHICLE");
        otherVehicle=prefix+"-car2";
        customer=new Actor(prefix+"-user","CUSTOMER");
        db.update("INSERT INTO account(id,username,role,password_hash) VALUES (?,?,'CUSTOMER','disabled-password')",customer.id(),customer.id());
        for(String v:new String[]{device.id(),otherVehicle})
            db.update("INSERT INTO vehicle(id,model,compatibility_key,energy_type) VALUES (?,'VIRTUAL','TEST','BATTERY')",v);
        otp=System.getenv("NEXTKEY_DEV_OTP");now=Instant.now();
    }
    String booking(String car,String status,Instant start,Instant end){
        String rid=UUID.randomUUID().toString();
        db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) VALUES (?,?,?,?,?,?)",
                rid,customer.id(),car,status,CoreData.sql(start),CoreData.sql(end));
        return rid;
    }
    String factor(String reservationId){
        var c=challenges.create(device,"QR",Map.of());
        String id=(String)c.get("id");
        challenges.qr(id,customer,(String)c.get("qrToken"),reservationId);
        return id;
    }
    void rejects(String code,Runnable work){assertEquals(code,assertThrows(ApiError.class,work::run).code());}

    @Test void challengeStoresOnlySupportedFieldsAndRechecksFreshnessAtActualStart(){
        String rid=booking(device.id(),"CONFIRMED",now.minusSeconds(60),now.plusSeconds(3600));
        var payload=Map.<String,Object>of(
                "extraLocation",Map.of("latitude",12,"longitude",34),
                "odometer",Map.of("value",32000,"unit","km","status","VALID","source","DEV_SIMULATOR",
                        "collectedAt",now.toString(),"privateMarker","should-be-discarded"),
                "battery",Map.of("value",35,"unit","kWh","status","VALID","source","DEV_SIMULATOR","collectedAt",now.toString()));
        var challenge=challenges.create(device,"QR",payload);
        String id=(String)challenge.get("id");
        String stored=db.queryForObject("SELECT start_input FROM auth_challenge WHERE id=?",String.class,id);
        var initial=pleos.nextkey.common.Json.read(stored);
        assertEquals(java.util.Set.of("odometer","fuel","battery","warnings"),initial.keySet());
        assertFalse(stored.contains("privateMarker"));
        assertFalse(stored.contains("should-be-discarded"));
        assertEquals("VALID",((Map<?,?>)initial.get("odometer")).get("status"));

        // Model the wait between QR issuance and final verification without sleeping.
        db.update("UPDATE auth_challenge SET start_input=JSON_SET(start_input,'$.odometer.collectedAt',?) WHERE id=?",
                now.minusSeconds(121).toString(),id);
        challenges.qr(id,customer,(String)challenge.get("qrToken"),rid);
        String sid=(String)challenges.verify(id,customer,otp).get("sessionId");
        var fixed=pleos.nextkey.common.Json.read(db.queryForObject(
                "SELECT start_json FROM rental_session WHERE id=?",String.class,sid));
        Map<?,?> distance=(Map<?,?>)fixed.get("odometer");
        assertEquals("STALE",distance.get("status"));
        assertFalse(distance.containsKey("value"));
        assertEquals("UNIT_MISMATCH",((Map<?,?>)fixed.get("battery")).get("status"));
        assertEquals("UNKNOWN",((Map<?,?>)fixed.get("warnings")).get("status"));
    }

    @Test void rejectsMissingCancelledEarlyExpiredAndMismatchedReservation(){
        Map<String,String> cases=Map.of("RESERVATION_NOT_FOUND",UUID.randomUUID().toString(),
                "RESERVATION_CANCELLED",booking(device.id(),"CANCELLED",now.minusSeconds(30),now.plusSeconds(3600)),
                "BOARDING_TOO_EARLY",booking(device.id(),"CONFIRMED",now.plusSeconds(3600),now.plusSeconds(7200)),
                "RESERVATION_EXPIRED",booking(device.id(),"CONFIRMED",now.minusSeconds(7200),now.minusSeconds(3600)),
                "VEHICLE_MISMATCH",booking(otherVehicle,"CONFIRMED",now.minusSeconds(30),now.plusSeconds(3600)));
        cases.forEach((code,rid)->{
            String cid=factor(rid);
            rejects(code,()->challenges.verify(cid,customer,otp));
        });
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rental_session WHERE account_id=?",Integer.class,customer.id()));
    }
    @Test void rejectsExpiredAndLimitedSecondFactorAcrossChallenges(){
        String rid=booking(device.id(),"CONFIRMED",now.minusSeconds(30),now.plusSeconds(3600));
        String expired=factor(rid);
        db.update("UPDATE auth_challenge SET second_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",expired);
        rejects("SECOND_FACTOR_EXPIRED",()->challenges.verify(expired,customer,otp));
        String limited=factor(rid);
        for(int i=0;i<4;i++) rejects("SECOND_FACTOR_INVALID",()->challenges.verify(limited,customer,"wrong-code"));
        rejects("SECOND_FACTOR_ATTEMPT_LIMIT",()->challenges.verify(limited,customer,"wrong-code"));
        String another=factor(rid);
        rejects("SECOND_FACTOR_ATTEMPT_LIMIT",()->challenges.verify(another,customer,otp));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rental_session WHERE account_id=?",Integer.class,customer.id()));
    }
    @Test void productionDeniesSeededCredentialAndItsAlreadyIssuedBearer(){
        var env=new MockEnvironment();
        env.setActiveProfiles("dev","prod");
        Credentials production=new Credentials(db,encoder,env,10);
        String devToken=credentials.issue("demo-customer",null);
        rejects("INVALID_CREDENTIALS",()->production.authenticate("demo-customer",System.getenv("NEXTKEY_DEV_PASSWORD")));
        assertNull(production.identify(devToken,"/api/reservations","GET"));
        String testRealmToken=credentials.issue(customer.id(),null);
        assertNull(production.identify(testRealmToken,"/api/reservations","GET"));
        assertEquals("DEVELOPMENT_SCHEMA_NOT_ALLOWED",assertThrows(IllegalStateException.class,
                ()->new ProductionSchema(db).afterSingletonsInstantiated()).getMessage());
    }

    @Test void concurrentReservationsForOneCustomerCannotDoubleBookDifferentCars() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for(String car:new String[]{device.id(),otherVehicle}) {
                tasks.add(executor.submit(()->{
                    start.await();
                    var request=new ReservationView(null,customer.id(),car,null,now,now.plusSeconds(300),null,null);
                    try {bookings.create(customer,request);return "CREATED";}
                    catch (ApiError e){return e.code();}
                }));
            }
            start.countDown();
            var outcomes=new java.util.HashSet<String>();
            for(var result:tasks) outcomes.add(result.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(java.util.Set.of("CREATED","RESERVATION_OVERLAP"),outcomes);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM reservation WHERE account_id=?",Integer.class,customer.id()));
        } finally { executor.shutdownNow(); }
    }
}
