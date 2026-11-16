package pleos.nextkey.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Audit expectation: a report cannot claim full readiness while declared records are still missing. */
@EnabledIfEnvironmentVariable(named="NEXTKEY_SPEC_AUDIT",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AuditEndPendingRecordsTests {
    @Autowired JdbcTemplate db;
    @Autowired PersonalReports reports;

    @Test void declaredButMissingEventCannotBecomeACompleteReadyReport() {
        String id=UUID.randomUUID().toString();
        String eventId="audit-"+id;
        db.update("INSERT INTO account(id,role,username,password_hash) VALUES(?,'CUSTOMER',?,'UNUSABLE-AUDIT-CREDENTIAL')",id,id);
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'audit-report','BATTERY',?)",id,id);
        db.update("""
                INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at)
                VALUES(?,?,?,'CONFIRMED',UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)
                """,id,id,id);
        db.update("""
                INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,ended_at,
                start_json,end_json,cleanup_state,report_state) VALUES(?,?,?,?,'ENDED',
                UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE,'{}','{}','PENDING','PENDING')
                """,id,id,id,id);
        db.update("""
                INSERT INTO end_request(session_id,request_id,request_digest,requested_at,confirmed_due_at,
                final_safety,final_settings,pending_event_ids)
                VALUES(?,?,REPEAT('a',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,
                '{}','{}',JSON_OBJECT('ids',JSON_ARRAY(?)))
                """,id,UUID.randomUUID().toString(),eventId);

        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM vehicle_event WHERE session_id=?",Integer.class,id));
        var report=reports.read(id,id);
        assertNotEquals("READY",report.get("reportState"),
                "A declared event is absent: either wait or explicitly expose an incomplete/provisional report");
        assertEquals("PENDING",report.get("reportState"));
        assertEquals(false,report.get("dataComplete"));
        assertEquals(1,((Number)report.get("declaredEventCount")).intValue());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rental_report WHERE session_id=?",Integer.class,id));
        db.update("UPDATE rental_session SET report_state='FAILED' WHERE id=?",id);
        var waiting=reports.retryStatus(id);
        assertEquals("PENDING",waiting.get("reportState"));
        assertFalse(waiting.containsKey("records"));

        var row=db.queryForMap("SELECT ended_at FROM rental_session WHERE id=?",id);
        String payload="{\"reason\":\"POWER_OFF\"}";
        db.update("""
                INSERT INTO vehicle_event(id,session_id,kind,collected_at,received_at,source,verification,status,payload,request_hash)
                VALUES(?,?,'GAP',?,UTC_TIMESTAMP(6),'DEV_SIMULATOR','CLIENT_REPORTED','POWER_OFF',?,REPEAT('b',64))
                """,eventId,id,row.get("ended_at"),payload);
        db.update("UPDATE rental_session SET report_state='FAILED' WHERE id=?",id);
        var repaired=reports.retryStatus(id);
        assertEquals("READY",repaired.get("reportState"));
        assertFalse(repaired.containsKey("records"));
        var ready=reports.read(id,id);
        assertEquals("READY",ready.get("reportState"));
        assertEquals(true,ready.get("dataComplete"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rental_report WHERE session_id=?",Integer.class,id));
    }
}
