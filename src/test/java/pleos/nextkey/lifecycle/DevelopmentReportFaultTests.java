package pleos.nextkey.lifecycle;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="nextkey.report.interval-ms=600000",webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
class DevelopmentReportFaultTests {
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate transactions;
    @Autowired PersonalReports reports;
    @Autowired DevelopmentReportFaults faults;

    private String active() {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO account(id,role,username,password_hash) VALUES(?,'CUSTOMER',?,'DISABLED-TEST')",id,id);
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'REPORT-FAULT-FIXTURE','BATTERY',?)",id,id);
        db.update("""
                INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at)
                VALUES(?,?,?,'CONFIRMED',UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)
                """,id,id,id);
        db.update("""
                INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,start_json)
                VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,'{}')
                """,id,id,id,id);
        return id;
    }

    private void ended(String id,List<String> manifest) {
        db.update("UPDATE rental_session SET state='ENDED',ended_at=UTC_TIMESTAMP(6),end_json='{}',cleanup_state='PENDING',report_state='PENDING' WHERE id=?",id);
        db.update("""
                INSERT INTO end_request(session_id,request_id,request_digest,requested_at,confirmed_due_at,final_safety,final_settings,pending_event_ids)
                VALUES(?,?,REPEAT('a',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'{}','{}',?)
                """,id,UUID.randomUUID().toString(),Json.write(java.util.Map.of("ids",manifest)));
    }

    @Test void pendingManifestDoesNotConsumeFaultAndBackgroundCannotReleaseFailure() {
        String id=active(),event=UUID.randomUUID().toString();
        assertEquals("ARMED",faults.arm(id).get("faultState"));
        assertEquals("ARMED",faults.arm(id).get("faultState"));
        ended(id,List.of(event));
        assertEquals("PENDING",reports.retryStatus(id).get("reportState"));
        assertEquals("ARMED",db.queryForObject("SELECT state FROM development_report_fault WHERE session_id=?",String.class,id));
        db.update("""
                INSERT INTO vehicle_event(id,session_id,kind,collected_at,received_at,source,verification,status,payload,request_hash)
                VALUES(?,?,'GAP',UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,UTC_TIMESTAMP(6),'DEV_SIMULATOR','CLIENT_REPORTED','POWER_OFF','{}',REPEAT('b',64))
                """,event,id);
        assertEquals("REPORT_GENERATION_FAILED",assertThrows(ApiError.class,()->reports.retryStatus(id)).code());
        assertEquals("FAILED",db.queryForObject("SELECT report_state FROM rental_session WHERE id=?",String.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rental_report WHERE session_id=?",Integer.class,id));
        // A new gate instance reads the persisted failure; an automatic worker cannot release it.
        assertTrue(new DevelopmentReportFaults(db,transactions).block(id,false));
        assertEquals("FAILED",db.queryForObject("SELECT state FROM development_report_fault WHERE session_id=?",String.class,id));
        assertEquals("READY",reports.retryStatus(id).get("reportState"));
        assertEquals("CLEARED",db.queryForObject("SELECT state FROM development_report_fault WHERE session_id=?",String.class,id));
        assertEquals("DEMO_READY_REPORT_PRESERVED",assertThrows(ApiError.class,()->faults.arm(id)).code());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rental_report WHERE session_id=?",Integer.class,id));
    }

    @Test void customerReadCanStillRecoverAfterCleanupAndLateArmingIsRejected() {
        String id=active();
        faults.arm(id);
        ended(id,List.of());
        assertEquals("DEMO_REPORT_FAULT_REQUIRES_PRE_ACK",assertThrows(ApiError.class,()->faults.arm(id)).code());
        assertEquals("REPORT_GENERATION_FAILED",assertThrows(ApiError.class,()->reports.read(id,id)).code());
        db.update("UPDATE rental_session SET cleanup_state='COMPLETE' WHERE id=?",id);
        assertEquals("READY",reports.read(id,id).get("reportState"));
        assertEquals("COMPLETE",db.queryForObject("SELECT cleanup_state FROM rental_session WHERE id=?",String.class,id));
    }
}
