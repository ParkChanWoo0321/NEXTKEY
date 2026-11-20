package pleos.nextkey.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static pleos.nextkey.core.CoreData.*;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name="nextkey.reservations.source",havingValue="LOCAL",matchIfMissing=true)
class DevelopmentOverdueDeadline {
    private final JdbcTemplate db;
    private final BookingService bookings;

    DevelopmentOverdueDeadline(JdbcTemplate db,BookingService bookings) {
        this.db=db;
        this.bookings=bookings;
    }

    // The common claim transaction serializes token issuance with reset before preparing its optional deadline.
    void onClaim(String vehicleId,String reservationId,String sessionId) {
        var vehicle=first(db,"SELECT model,compatibility_key FROM vehicle WHERE id=?",vehicleId);
        if(!(vehicle.get("compatibility_key") instanceof String compatibility) || !compatibility.startsWith("PLEOS_DEMO:"))return;
        var control=first(db,"SELECT * FROM pleos_demo_run WHERE run_id=? FOR UPDATE",
                compatibility.substring("PLEOS_DEMO:".length()));
        if(control.isEmpty())return;
        if(control.get("reset_to_run")!=null)throw fail(409,"DEMO_RUN_RETIRED");
        if(!"PLEOS_DEMO:OVERDUE_AFTER_5_SECONDS".equals(vehicle.get("model")) ||
                !reservationId.equals(control.get("reservation_id")))return;
        if(control.get("overdue_at")!=null)return;

        var fixture=PleosDemoService.names(string(control,"run_id"));
        var reservation=bookings.lookup(reservationId,true);
        var session=first(db,"SELECT * FROM rental_session WHERE id=? FOR UPDATE",sessionId);
        if(!fixture.vehicle().equals(vehicleId)||!vehicleId.equals(reservation.vehicleId())||
                !fixture.customer().equals(reservation.accountId())||!reservationId.equals(session.get("reservation_id"))||
                !fixture.customer().equals(session.get("account_id"))||!vehicleId.equals(session.get("vehicle_id"))||
                !"ACTIVE".equals(session.get("state"))||!"CONFIRMED".equals(reservation.status())||
                !reservation.confirmedEndAt().equals(instant(control.get("prepared_end_at")))||
                !first(db,"SELECT id FROM rental_extension WHERE reservation_id=? FOR UPDATE",reservationId).isEmpty()||
                !first(db,"SELECT new_reservation_id FROM session_replacement WHERE old_session_id=? FOR UPDATE",sessionId).isEmpty())
            throw fail(409,"DEMO_START_PREPARATION_CHANGED");

        Instant due=Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        if(!due.isAfter(reservation.startsAt())||!due.isAfter(instant(session.get("started_at"))))throw fail(409,"INVALID_DEMO_DEADLINE");
        db.update("UPDATE reservation SET confirmed_end_at=? WHERE id=?",sql(due),reservationId);
        db.update("UPDATE pleos_demo_run SET overdue_at=? WHERE run_id=?",sql(due),fixture.run());
    }
}
