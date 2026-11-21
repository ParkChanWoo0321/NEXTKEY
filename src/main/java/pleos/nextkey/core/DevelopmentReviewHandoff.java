package pleos.nextkey.core;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static pleos.nextkey.core.CoreData.*;

/** Resolve the fixed review number after successful authentication, inside the account and vehicle locks. */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name="nextkey.dev.review.handoff", havingValue="true")
class DevelopmentReviewHandoff {
    private final JdbcTemplate db;
    private final Environment env;
    DevelopmentReviewHandoff(JdbcTemplate db, Environment env) { this.db = db; this.env = env; }

    String resolve(String requested, String account, String car) {
        if (!env.matchesProfiles("dev & !prod") ||
                !"LOCAL".equalsIgnoreCase(env.getProperty("nextkey.reservations.source", "LOCAL"))) return requested;
        boolean primary = "demo-customer".equals(account);
        if (!"demo-car".equals(car) || (!primary && !"test2".equals(account))) return requested;
        String number = primary ? "test" : "test2";
        boolean generated = requested != null && requested.startsWith(number + "-run-");
        if (!number.equals(requested) && !"demo-reservation".equals(requested) && !generated) return requested;
        if (generated && first(db,"SELECT id FROM reservation WHERE id=? AND account_id=? AND vehicle_id=?",
                requested,account,car).isEmpty()) return requested;
        if (!primary && first(db,"""
                SELECT id FROM rental_session WHERE account_id='demo-customer' AND vehicle_id='demo-car'
                AND (reservation_id='test' OR reservation_id LIKE 'test-run-%') AND state='ENDED' AND cleanup_state='COMPLETE' LIMIT 1
                """).isEmpty()) throw fail(409,"FIRST_REVIEW_RENTAL_NOT_RETURNED");
        var template = first(db,"""
                SELECT r.id FROM reservation r JOIN account a ON a.id=? AND a.role='CUSTOMER' AND a.is_demo=TRUE
                JOIN account v ON v.id=r.vehicle_id AND v.role='VEHICLE' AND v.is_demo=TRUE
                WHERE r.id='demo-reservation' AND r.account_id='demo-customer' AND r.vehicle_id=? AND r.status='CONFIRMED'
                """,account,car);
        if (template.isEmpty()) throw fail(409,"REVIEW_FIXTURE_UNAVAILABLE");

        var occupied = first(db,"SELECT account_id,vehicle_id,reservation_id FROM rental_session WHERE occupied_vehicle=? OR occupied_account=? FOR UPDATE",car,account);
        if (!occupied.isEmpty()) {
            String current = string(occupied,"reservation_id");
            if (account.equals(occupied.get("account_id")) && car.equals(occupied.get("vehicle_id"))
                    && (number.equals(current) || current.startsWith(number+"-run-") || (primary && "demo-reservation".equals(current)))) return current;
            throw fail(409,"PREVIOUS_SESSION_REQUIRES_CLEANUP");
        }
        var pending = first(db,"""
                SELECT r.id FROM reservation r LEFT JOIN rental_session s ON s.reservation_id=r.id
                WHERE r.account_id=? AND r.vehicle_id=? AND (r.id=? OR r.id LIKE ?) AND s.id IS NULL
                  AND r.status='CONFIRMED' AND r.starts_at<=UTC_TIMESTAMP(6) AND r.confirmed_end_at>UTC_TIMESTAMP(6)
                ORDER BY r.starts_at DESC,r.id LIMIT 1
                """,account,car,number,number+"-run-%");
        if (!pending.isEmpty()) return string(pending,"id");

        var conflict = first(db,"""
                SELECT r.id FROM reservation r JOIN reservation template ON template.id='demo-reservation'
                LEFT JOIN rental_session s ON s.reservation_id=r.id
                WHERE (r.vehicle_id=? OR r.account_id=?) AND r.status='CONFIRMED' AND r.confirmed_end_at>UTC_TIMESTAMP(6)
                  AND r.starts_at < IF(template.confirmed_end_at>UTC_TIMESTAMP(6), template.confirmed_end_at,UTC_TIMESTAMP(6)+INTERVAL 1 DAY)
                  AND NOT (s.id IS NULL AND r.vehicle_id='demo-car' AND
                       ((r.account_id='demo-customer' AND r.id IN ('demo-reservation','test')) OR
                       (r.account_id='test2' AND r.id='test2')))
                  AND (s.id IS NULL OR s.state<>'ENDED' OR s.cleanup_state<>'COMPLETE' OR
                       (s.return_state<>'CONFIRMED' AND NOT (r.vehicle_id='demo-car' AND
                         ((r.account_id='demo-customer' AND (r.id IN ('demo-reservation','test') OR r.id LIKE 'test-run-%'))
                         OR (r.account_id='test2' AND (r.id='test2' OR r.id LIKE 'test2-run-%'))))))
                LIMIT 1
                """,car,account);
        if (!conflict.isEmpty()) throw fail(409,"RESERVATION_OVERLAP");
        var base = first(db,"SELECT account_id,vehicle_id,status FROM reservation WHERE id=? FOR UPDATE",number);
        if (!base.isEmpty() && (!account.equals(base.get("account_id")) || !car.equals(base.get("vehicle_id"))
                || !"CONFIRMED".equals(base.get("status")))) throw fail(409,"REVIEW_RESERVATION_CONFLICT");
        String next = base.isEmpty() ? number : number+"-run-"+UUID.randomUUID();
        db.update("""
                INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at,destination_json,return_json)
                SELECT ?,?,vehicle_id,'CONFIRMED',?,
                    IF(confirmed_end_at>UTC_TIMESTAMP(6),confirmed_end_at,UTC_TIMESTAMP(6)+INTERVAL 1 DAY),
                    IF(account_id=?,destination_json,NULL),return_json FROM reservation WHERE id='demo-reservation'
                """,next,account,sql(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)),account);
        return next;
    }
}
