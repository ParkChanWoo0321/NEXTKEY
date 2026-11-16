package pleos.nextkey.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import pleos.nextkey.common.Json;
import pleos.nextkey.records.RentalRecords;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Audit expectation: an existing READY report remains readable if a refresh temporarily fails. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="NEXTKEY_SPEC_AUDIT", matches="true")
class AuditEndReportsTests {
    @Test void previouslyReadyReportRemainsReadableDuringTransientRefreshFailure() {
        String session="audit-ended-session", owner="audit-report-owner";
        JdbcTemplate db=mock(JdbcTemplate.class);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation->new SimpleTransactionStatus());
        when(db.queryForList("SELECT account_id,state FROM rental_session WHERE id=?",session))
                .thenReturn(List.of(Map.of("account_id",owner,"state","ENDED")));
        when(db.queryForMap("SELECT s.*,UTC_TIMESTAMP(6) AS snapshot_at FROM rental_session s WHERE id=?",session))
                .thenThrow(new TransientDataAccessResourceException("audit injected refresh outage"));
        Map<String,Object> ready=Map.of("sessionId",session,"reportState","READY","dataComplete",true,
                "distanceKm",Map.of("status","VALID","value",182));
        when(db.queryForObject("SELECT pending_event_ids FROM end_request WHERE session_id=?",String.class,session))
                .thenReturn("{\"ids\":[]}");
        when(db.queryForList("SELECT payload FROM rental_report WHERE session_id=?",session))
                .thenReturn(List.of(Map.of("payload",Json.write(ready))));

        PersonalReports service=new PersonalReports(db,new TransactionTemplate(manager),mock(RentalRecords.class));
        Map<String,Object> result=assertDoesNotThrow(()->service.read(session,owner),
                "A transient refresh failure must not hide a previously published READY report");
        assertEquals("READY",result.get("reportState"));
        assertEquals(session,result.get("sessionId"));
        assertEquals(true,result.get("servedFromCache"));
        assertEquals("FAILED",result.get("refreshState"));
        assertEquals(182,((Number)((Map<?,?>)result.get("distanceKm")).get("value")).intValue());
    }
}
