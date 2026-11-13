package pleos.nextkey.core;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static pleos.nextkey.core.CoreData.*;

/** Run within a READ_COMMITTED transaction. Every local allocation writer takes these same locks. */
@Service
public class BookingAvailability {
    private final JdbcTemplate db;
    public BookingAvailability(JdbcTemplate db) { this.db=db; }

    public void lock(String account,String vehicle) {
        if(first(db,"SELECT id FROM account WHERE id=? AND role='CUSTOMER' AND enabled=TRUE FOR UPDATE",account).isEmpty())
            throw fail(404,"CUSTOMER_NOT_FOUND");
        if(first(db,"SELECT id FROM vehicle WHERE id=? FOR UPDATE",vehicle).isEmpty())
            throw fail(404,"VEHICLE_NOT_FOUND");
    }

    public void requireAvailable(String account,String vehicle,Instant start,Instant end,
                                 String excludedReservation,String replacedReservation) {
        var conflict=first(db,"""
            SELECT r.id FROM reservation r LEFT JOIN rental_session s ON s.reservation_id=r.id
            WHERE r.status='CONFIRMED' AND (r.account_id=? OR r.vehicle_id=?)
              AND r.starts_at<? AND r.confirmed_end_at>?
              AND (? IS NULL OR r.id<>?) AND (? IS NULL OR r.id<>?)
              AND (s.id IS NULL OR s.state<>'ENDED' OR s.cleanup_state<>'COMPLETE' OR s.return_state<>'CONFIRMED')
            LIMIT 1
            """,account,vehicle,sql(end),sql(start),excludedReservation,excludedReservation,replacedReservation,replacedReservation);
        if(!conflict.isEmpty()) throw fail(409,"RESERVATION_OVERLAP");
    }
}
