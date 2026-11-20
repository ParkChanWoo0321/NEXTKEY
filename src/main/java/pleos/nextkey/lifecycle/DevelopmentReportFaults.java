package pleos.nextkey.lifecycle;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pleos.nextkey.common.ApiError;

/** A run-owned development fixture can fail its first complete assembly before any document exists. */
@Service
@Profile("dev & !prod")
public class DevelopmentReportFaults {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public DevelopmentReportFaults(JdbcTemplate db,TransactionTemplate transactions) {
        this.db=db;
        this.transactions=transactions;
    }

    /** The fixture service authorizes the generated run before invoking this method. */
    @Transactional
    public Map<String,Object> arm(String sessionId) {
        var session=db.queryForMap("SELECT state,report_state FROM rental_session WHERE id=? FOR UPDATE",sessionId);
        if ("READY".equals(session.get("report_state")) ||
                !db.queryForList("SELECT session_id FROM rental_report WHERE session_id=?",sessionId).isEmpty())
            throw new ApiError(409,"DEMO_READY_REPORT_PRESERVED");
        if (!"ACTIVE".equals(session.get("state")) && !"END_PENDING".equals(session.get("state")))
            throw new ApiError(409,"DEMO_REPORT_FAULT_REQUIRES_PRE_ACK");
        var prior=db.queryForList("SELECT state FROM development_report_fault WHERE session_id=?",sessionId);
        if (prior.isEmpty()) db.update("INSERT INTO development_report_fault(session_id,state,armed_at) VALUES(?,'ARMED',UTC_TIMESTAMP(6))",sessionId);
        return Map.of("reportState",session.get("report_state"),"faultState","ARMED",
                "faultSource","DEVELOPMENT_FIXTURE","nextAction","CONFIRM_END_THEN_GENERATE_REPORT");
    }

    /** Called after a complete assembly, outside its read transaction, before publication. */
    boolean block(String sessionId,boolean explicitRetry) {
        if (db.queryForList("SELECT state FROM development_report_fault WHERE session_id=?",sessionId).isEmpty()) return false;
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            var session=db.queryForMap("SELECT state,report_state FROM rental_session WHERE id=? FOR UPDATE",sessionId);
            var fault=db.queryForMap("SELECT state FROM development_report_fault WHERE session_id=? FOR UPDATE",sessionId);
            if ("CLEARED".equals(fault.get("state"))) return false;
            if ("READY".equals(session.get("report_state")) ||
                    !db.queryForList("SELECT session_id FROM rental_report WHERE session_id=?",sessionId).isEmpty()) return false;
            if ("FAILED".equals(fault.get("state")) && explicitRetry) {
                db.update("UPDATE development_report_fault SET state='CLEARED',cleared_at=UTC_TIMESTAMP(6) WHERE session_id=?",sessionId);
                return false;
            }
            db.update("UPDATE development_report_fault SET state='FAILED',failed_at=COALESCE(failed_at,UTC_TIMESTAMP(6)) WHERE session_id=?",sessionId);
            db.update("UPDATE rental_session SET report_state='FAILED' WHERE id=? AND state='ENDED' AND report_state<>'READY'",sessionId);
            return true;
        }));
    }
}
