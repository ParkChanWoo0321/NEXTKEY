package pleos.nextkey.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.lifecycle.EndController;
import static org.junit.jupiter.api.Assertions.*;

/** All fixture changes roll back; this suite is permitted only on the isolated test schema. */
@SpringBootTest(properties="nextkey.dev.review.handoff=true")
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
@Transactional
class DevelopmentReviewFlowTests {
    @Autowired JdbcTemplate db;
    @Autowired ChallengeService challenges;
    @Autowired EndController end;
    private final Actor vehicle = new Actor("demo-car","VEHICLE");

    @BeforeEach void prepareRolledBackReviewFixtures() {
        assertEquals("nextkey_test",db.queryForObject("SELECT DATABASE()",String.class));
        // Other suites share these IDs. Temporarily release their fixture allocations within this rollback only.
        db.update("UPDATE rental_session SET state='ENDED',cleanup_state='COMPLETE',ended_at=COALESCE(ended_at,UTC_TIMESTAMP(6)) WHERE vehicle_id='demo-car' OR account_id IN ('demo-customer','test2')");
        db.update("UPDATE reservation SET status='CANCELLED' WHERE vehicle_id='demo-car' OR account_id IN ('demo-customer','test2')");
        db.update("""
                UPDATE reservation SET status='CONFIRMED',starts_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,
                confirmed_end_at=UTC_TIMESTAMP(6)+INTERVAL 1 DAY,destination_json=JSON_OBJECT('name','review-owner-private')
                WHERE id='demo-reservation'
                """);
        db.update("INSERT INTO account(id,role,username,password_hash,is_demo) VALUES('test2','CUSTOMER','test2','DISABLED-TEST-CREDENTIAL',TRUE)");
        db.update("UPDATE account SET factor_attempts=0,factor_blocked_until=NULL WHERE id IN ('demo-customer','test2')");
    }

    @Test void sameVehicleCanPassToTheSecondRenterAndBothCanRepeatWithoutOverwritingHistory() {
        String first=start("demo-customer","test");
        assertEquals(first,start("demo-customer","test"));
        String firstReservation=reservation(first);
        assertEquals("test",firstReservation);
        finish(first,true);
        var frozen=db.queryForMap("SELECT start_json,end_json,started_at,ended_at,return_state FROM rental_session WHERE id=?",first);
        assertEquals("UNCONFIRMED",frozen.get("return_state"));
        String second=start("test2","test2");
        assertNotEquals(first,second);
        assertEquals("demo-car",db.queryForObject("SELECT vehicle_id FROM rental_session WHERE id=?",String.class,second));
        assertNull(db.queryForObject("SELECT destination_json FROM reservation WHERE id=?",String.class,reservation(second)));
        finish(second,true);
        String repeatFirst=start("demo-customer","test");
        assertTrue(reservation(repeatFirst).startsWith("test-run-"));
        assertNotEquals(first,repeatFirst);
        finish(repeatFirst,true);
        String repeatSecond=start("test2","test2");
        assertTrue(reservation(repeatSecond).startsWith("test2-run-"));
        assertEquals(frozen,db.queryForMap("SELECT start_json,end_json,started_at,ended_at,return_state FROM rental_session WHERE id=?",first));
        assertEquals("CONFIRMED",db.queryForObject("SELECT status FROM reservation WHERE id='demo-reservation'",String.class));
    }

    @Test void secondRenterRequiresFirstEndAndCleanupAndCannotTakeAnOccupiedVehicle() {
        rejects("FIRST_REVIEW_RENTAL_NOT_RETURNED",()->start("test2","test2"));
        String first=start("demo-customer","test");
        rejects("FIRST_REVIEW_RENTAL_NOT_RETURNED",()->start("test2","test2"));
        UUID request=finish(first,false);
        rejects("FIRST_REVIEW_RENTAL_NOT_RETURNED",()->start("test2","test2"));
        asSession(first,()->end.clean(first,new EndController.Clean(request,true,true)));
        start("test2","test2");
        rejects("PREVIOUS_SESSION_REQUIRES_CLEANUP",()->start("demo-customer","test"));
    }

    @Test void failedProofCannotAllocateAndVerifiedChallengeCannotBeUsedTwice() {
        var challenge=bound("demo-customer","test");
        String id=(String)challenge.get("id");
        rejects("SECOND_FACTOR_INVALID",()->challenges.verify(id,new Actor("demo-customer","CUSTOMER"),"wrong-proof"));
        assertEquals(0,count("SELECT COUNT(*) FROM reservation WHERE id='test'"));
        assertEquals(0,count("SELECT COUNT(*) FROM rental_session WHERE reservation_id='test'"));
        challenges.verify(id,new Actor("demo-customer","CUSTOMER"),System.getenv("NEXTKEY_DEV_OTP"));
        rejects("CHALLENGE_ALREADY_USED",()->challenges.verify(id,new Actor("demo-customer","CUSTOMER"),System.getenv("NEXTKEY_DEV_OTP")));
        assertEquals(1,count("SELECT COUNT(*) FROM rental_session WHERE reservation_id='test'"));
        assertEquals("test",db.queryForObject("SELECT reservation_id FROM auth_challenge WHERE id=?",String.class,id));
    }

    @Test void anotherConfirmedReservationStillBlocksTheReviewFixture() {
        db.update("""
                INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at)
                VALUES(?, 'demo-customer-2','demo-car','CONFIRMED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)
                """,UUID.randomUUID().toString());
        rejects("RESERVATION_OVERLAP",()->start("demo-customer","test"));
        assertEquals(0,count("SELECT COUNT(*) FROM reservation WHERE id='test'"));
    }

    @Test void aReservationAliasOwnedByAnotherCustomerCannotBeReassigned() {
        db.update("""
                INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at)
                VALUES('test','demo-customer-2','demo-car','CONFIRMED',UTC_TIMESTAMP(6)-INTERVAL 2 HOUR,UTC_TIMESTAMP(6)-INTERVAL 1 HOUR)
                """);
        rejects("REVIEW_RESERVATION_CONFLICT",()->start("demo-customer","test"));
        assertEquals("demo-customer-2",db.queryForObject("SELECT account_id FROM reservation WHERE id='test'",String.class));
    }

    @Test void nonDemoCustomerAndCancelledTemplateCannotEnableReviewAllocation() {
        db.update("UPDATE account SET is_demo=FALSE WHERE id='demo-customer'");
        rejects("REVIEW_FIXTURE_UNAVAILABLE",()->start("demo-customer","test"));
        db.update("UPDATE account SET is_demo=TRUE WHERE id='demo-customer'");
        db.update("UPDATE reservation SET status='CANCELLED' WHERE id='demo-reservation'");
        rejects("REVIEW_FIXTURE_UNAVAILABLE",()->start("demo-customer","test"));
        assertEquals(0,count("SELECT COUNT(*) FROM reservation WHERE id='test'"));
    }

    private int count(String sql) { return db.queryForObject(sql,Integer.class); }
    private String reservation(String session) { return db.queryForObject("SELECT reservation_id FROM rental_session WHERE id=?",String.class,session); }
    private Map<String,Object> bound(String customer,String alias) {
        var challenge=challenges.create(vehicle,"QR",Map.of());
        challenges.qr((String)challenge.get("id"),new Actor(customer,"CUSTOMER"),(String)challenge.get("qrToken"),alias);
        return challenge;
    }
    private String start(String customer,String alias) {
        var challenge=bound(customer,alias);
        String id=(String)challenge.get("id");
        var verified=challenges.verify(id,new Actor(customer,"CUSTOMER"),System.getenv("NEXTKEY_DEV_OTP"));
        var claimed=challenges.claim(id,vehicle,(String)challenge.get("pollSecret"));
        assertEquals(verified.get("sessionId"),claimed.get("sessionId"));
        assertEquals(reservation((String)claimed.get("sessionId")),db.queryForObject("SELECT reservation_id FROM auth_challenge WHERE id=?",String.class,id));
        return (String)claimed.get("sessionId");
    }
    private UUID finish(String session,boolean cleanup) {
        UUID request=UUID.randomUUID();
        asSession(session,()->{
            end.submit(session,new EndController.End(request,true,null,
                    new EndController.Safety(true,true,Instant.now(),"DEV_SIMULATOR"),Map.of(),Map.of(),List.of()));
            end.acknowledge(session,new EndController.Ack(request));
            if(cleanup) end.clean(session,new EndController.Clean(request,true,true));
        });
        return request;
    }
    private void asSession(String session,Runnable work) {
        var previous=SecurityContextHolder.getContext();
        try {
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new Actor(session,"SESSION"),null,List.of()));
            SecurityContextHolder.setContext(context);
            work.run();
        } finally { SecurityContextHolder.setContext(previous); }
    }
    private void rejects(String code,Runnable work) { assertEquals(code,assertThrows(ApiError.class,work::run).code()); }
}
