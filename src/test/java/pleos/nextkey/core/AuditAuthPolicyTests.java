package pleos.nextkey.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;

import static org.junit.jupiter.api.Assertions.*;

/** Source-PDF contract checks, intentionally separate from implementation-policy tests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "NEXTKEY_SPEC_AUDIT", matches = "true")
class AuditAuthPolicyTests {
    @Autowired JdbcTemplate db;
    @Autowired ChallengeService challenges;
    private Actor device;
    private Actor customer;
    private String reservation;

    @BeforeEach
    void isolatedFixtures() {
        assertEquals("nextkey_test", db.queryForObject("SELECT DATABASE()", String.class));
        String prefix = "audit-auth-" + UUID.randomUUID();
        device = new Actor(prefix + "-car", "VEHICLE");
        customer = new Actor(prefix + "-user", "CUSTOMER");
        reservation = UUID.randomUUID().toString();
        db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,'CUSTOMER','DISABLED')",
                customer.id(), customer.id());
        db.update("INSERT INTO vehicle(id,model,compatibility_key,energy_type) VALUES(?,'VIRTUAL','AUDIT','BATTERY')",
                device.id());
        Instant now = Instant.now();
        db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) VALUES(?,?,?,'CONFIRMED',?,?)",
                reservation, customer.id(), device.id(), CoreData.sql(now.minusSeconds(60)), CoreData.sql(now.plusSeconds(3600)));
    }

    private String boundChallenge() {
        Map<String,Object> created = challenges.create(device, "QR", Map.of());
        String id = (String) created.get("id");
        challenges.qr(id, customer, (String) created.get("qrToken"), reservation);
        return id;
    }

    @Test
    void thirdWrongProofMustReachThePdfAttemptLimit() {
        // Wide PDF p145/p177: wrong attempts 1-2 stay in place; attempt 3 is limited.
        String id = boundChallenge();
        for (int i = 0; i < 2; i++) {
            assertEquals("SECOND_FACTOR_INVALID", assertThrows(ApiError.class,
                    () -> challenges.verify(id, customer, "not-the-proof")).code());
        }
        ApiError third = assertThrows(ApiError.class,
                () -> challenges.verify(id, customer, "not-the-proof"));
        assertEquals("SECOND_FACTOR_ATTEMPT_LIMIT", third.code(),
                "PDF says the third wrong proof enters the attempt-limit state");
        assertEquals(429,third.status());
        assertEquals(3,third.details().get("attemptLimit"));
        assertEquals(0,third.details().get("remainingAttempts"));
        assertEquals(15L,third.details().get("lockSeconds"));
        Instant retryAt=Instant.parse((String)third.details().get("retryAt"));
        assertTrue(retryAt.isAfter(Instant.now()));
        assertTrue(((Number)third.details().get("retryAfterSeconds")).longValue()<=15);
    }

    @Test
    void validProofAfterThreeFailuresMustStillBeBlockedByThePdfPolicy() {
        String id = boundChallenge();
        for (int i = 0; i < 3; i++) {
            assertThrows(ApiError.class, () -> challenges.verify(id, customer, "not-the-proof"));
        }
        ApiError blocked=assertThrows(ApiError.class,
                () -> challenges.verify(id, customer, System.getenv("NEXTKEY_DEV_OTP")),
                "Correct proof must not bypass the PDF's lock after three failed attempts");
        assertEquals("SECOND_FACTOR_ATTEMPT_LIMIT",blocked.code());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rental_session WHERE account_id=?",Integer.class,customer.id()));
    }

    @Test
    void qrLifetimeMustMatchTheHtmlSixtySecondTimer() {
        // The shipped HTML initializes qrExpiresAt to now+60000 and prints a 60-second timer.
        Instant before = Instant.now();
        Map<String,Object> created = challenges.create(device, "QR", Map.of());
        long lifetime = Duration.between(before, (Instant) created.get("expiresAt")).toSeconds();
        assertTrue(lifetime >= 59 && lifetime <= 61,
                "HTML shows QR expiration after 60 seconds, actual default lifetime was " + lifetime);
    }

    @Test
    void recoveryMustRejectAnAuthoritativeStartTimeMovedIntoTheFuture() {
        String id = boundChallenge();
        String session = (String) challenges.verify(id, customer, System.getenv("NEXTKEY_DEV_OTP")).get("sessionId");
        Instant now = Instant.now();
        // Model a later authoritative reservation correction; HTML bookingError(..., true)
        // still enforces the start-time boundary, while exempting only the old end time.
        Instant availableAt=now.plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update("UPDATE reservation SET starts_at=?,confirmed_end_at=? WHERE id=?",
                CoreData.sql(availableAt), CoreData.sql(now.plusSeconds(7200)), reservation);
        ApiError error=assertThrows(ApiError.class, () -> challenges.recovery(new Actor(session, "SESSION")),
                "A07 recovery must preserve protection when the authoritative start is still in the future");
        assertEquals("BOARDING_TOO_EARLY",error.code());
        assertEquals(409,error.status());
        assertEquals(availableAt.toString(),error.details().get("availableAt"));
        assertNotNull(error.details().get("serverTime"));
    }

    @Test
    void newChallengeCannotResetFailuresAndCannotDispatchWhileLocked() {
        String first=boundChallenge();
        for(int i=0;i<2;i++)assertThrows(ApiError.class,
                () -> challenges.verify(first,customer,"not-the-proof"));
        String second=boundChallenge();
        ApiError limit=assertThrows(ApiError.class,
                () -> challenges.verify(second,customer,"not-the-proof"));
        assertEquals("SECOND_FACTOR_ATTEMPT_LIMIT",limit.code());
        Map<String,Object> next=challenges.create(device,"QR",Map.of());
        String id=(String)next.get("id");
        ApiError bindBlocked=assertThrows(ApiError.class,
                () -> challenges.qr(id,customer,(String)next.get("qrToken"),reservation));
        assertEquals("SECOND_FACTOR_ATTEMPT_LIMIT",bindBlocked.code());
        assertEquals("OPEN",db.queryForObject("SELECT status FROM auth_challenge WHERE id=?",String.class,id));
    }

    @Test
    void anExpiredAccountLockAllowsTheSameUnexpiredChallengeToBeVerified() {
        String id=boundChallenge();
        for(int i=0;i<3;i++)assertThrows(ApiError.class,
                () -> challenges.verify(id,customer,"not-the-proof"));
        db.update("UPDATE account SET factor_blocked_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",customer.id());
        String session=(String)challenges.verify(id,customer,System.getenv("NEXTKEY_DEV_OTP")).get("sessionId");
        assertNotNull(session);
        assertEquals(0,db.queryForObject("SELECT factor_attempts FROM account WHERE id=?",Integer.class,customer.id()));
        assertNull(db.queryForObject("SELECT factor_blocked_until FROM account WHERE id=?",Object.class,customer.id()));
    }

    @Test
    void activeReauthenticationRejectsFutureStartWithoutReplacingTheOriginalSession() {
        String first=boundChallenge();
        String session=(String)challenges.verify(first,customer,System.getenv("NEXTKEY_DEV_OTP")).get("sessionId");
        Instant now=Instant.now();
        db.update("UPDATE reservation SET starts_at=?,confirmed_end_at=? WHERE id=?",
                CoreData.sql(now.plusSeconds(3600)),CoreData.sql(now.plusSeconds(7200)),reservation);
        String next=boundChallenge();
        ApiError error=assertThrows(ApiError.class,
                () -> challenges.verify(next,customer,System.getenv("NEXTKEY_DEV_OTP")));
        assertEquals("BOARDING_TOO_EARLY",error.code());
        assertEquals(session,db.queryForObject("SELECT id FROM rental_session WHERE reservation_id=?",String.class,reservation));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rental_session WHERE reservation_id=?",Integer.class,reservation));
    }

    @Test
    void concurrentFailuresKeepOneSharedAttemptLimit() throws Exception {
        String id=boundChallenge();
        var gate=new CyclicBarrier(3);
        var workers=Executors.newFixedThreadPool(3);
        try {
            Callable<Integer> wrongProof=()->{
                gate.await(5,TimeUnit.SECONDS);
                return assertThrows(ApiError.class,
                        ()->challenges.verify(id,customer,"not-the-proof")).status();
            };
            var one=workers.submit(wrongProof);
            var two=workers.submit(wrongProof);
            var three=workers.submit(wrongProof);
            var statuses=new java.util.ArrayList<>(java.util.List.of(
                    one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS),three.get(15,TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(java.util.List.of(401,401,429),statuses);
            assertEquals(3,db.queryForObject("SELECT factor_attempts FROM account WHERE id=?",Integer.class,customer.id()));
            assertEquals(3,db.queryForObject("SELECT attempt_count FROM auth_challenge WHERE id=?",Integer.class,id));
            assertNotNull(db.queryForObject("SELECT factor_blocked_until FROM account WHERE id=?",Object.class,customer.id()));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void overdueActiveUseStillRecoversAndReauthenticatesIntoTheSameOriginalSession() {
        String first=boundChallenge();
        String session=(String)challenges.verify(first,customer,System.getenv("NEXTKEY_DEV_OTP")).get("sessionId");
        Instant now=Instant.now();
        db.update("UPDATE reservation SET starts_at=?,confirmed_end_at=? WHERE id=?",
                CoreData.sql(now.minusSeconds(7200)),CoreData.sql(now.minusSeconds(60)),reservation);
        db.update("UPDATE rental_session SET started_at=? WHERE id=?",CoreData.sql(now.minusSeconds(3600)),session);
        var original=db.queryForMap("SELECT id,started_at,start_json FROM rental_session WHERE id=?",session);
        assertEquals("ACTIVE",challenges.recovery(new Actor(session,"SESSION")).get("state"));
        String next=boundChallenge();
        assertEquals(session,challenges.verify(next,customer,System.getenv("NEXTKEY_DEV_OTP")).get("sessionId"));
        assertEquals(original,db.queryForMap("SELECT id,started_at,start_json FROM rental_session WHERE id=?",session));
    }
}
