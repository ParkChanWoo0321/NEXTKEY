package pleos.nextkey.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.records.RentalRecords;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ReportPublicationTests {
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;

    @Test void slowReportDoesNotBlockCleanupOrOverwriteANewerSuccessfulReport() throws Exception {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO account(id,role,username,password_hash) VALUES(?,'CUSTOMER',?,'UNUSABLE-TEST-CREDENTIAL')",id,id);
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'report-test','BATTERY',?)",id,id);
        db.update("""
                INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at)
                VALUES(?,?,?,'CONFIRMED',UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6))
                """,id,id,id);
        db.update("""
                INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,ended_at,
                start_json,end_json,cleanup_state,report_state) VALUES(?,?,?,?,'ENDED',
                UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6),'{}','{}','PENDING','FAILED')
                """,id,id,id,id);
        db.update("""
                INSERT INTO end_request(session_id,request_id,request_digest,requested_at,confirmed_due_at,final_safety,
                final_settings,pending_event_ids) VALUES(?,?,REPEAT('a',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'{}','{}',JSON_OBJECT('ids',JSON_ARRAY()))
                """,id,id);
        var started=new CountDownLatch(1);
        var resume=new CountDownLatch(1);
        var calls=new AtomicInteger();
        RentalRecords delayed=mock(RentalRecords.class);
        when(delayed.report(id)).thenAnswer(invocation->{
            int version=calls.incrementAndGet();
            if (version==1) {
                started.countDown();
                if (!resume.await(12,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            }
            return Map.of("revisionTest",version);
        });
        var service=new PersonalReports(db,tx,delayed);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var old=pool.submit(()->service.read(id,id));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            var clean=pool.submit(()->db.update("UPDATE rental_session SET cleanup_state='COMPLETE' WHERE id=?",id));
            assertEquals(1,clean.get(4,TimeUnit.SECONDS));
            var fresh=service.read(id,id);
            assertEquals(2,((Map<?,?>)fresh.get("records")).get("revisionTest"));
            resume.countDown();
            assertEquals(fresh.get("asOf"),old.get(5,TimeUnit.SECONDS).get("asOf"));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rental_report WHERE session_id=?",Integer.class,id));
            doThrow(new IllegalStateException("test refresh failure")).when(delayed).report(id);
            var cached=service.read(id,id);
            assertEquals(fresh.get("asOf"),cached.get("asOf"));
            assertEquals(true,cached.get("servedFromCache"));
            assertEquals("FAILED",cached.get("refreshState"));
            assertEquals("READY",db.queryForObject("SELECT report_state FROM rental_session WHERE id=?",String.class,id));
        } finally { resume.countDown();pool.shutdownNow(); }
    }
}
