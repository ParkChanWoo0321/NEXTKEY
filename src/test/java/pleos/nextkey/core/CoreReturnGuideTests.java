package pleos.nextkey.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import pleos.nextkey.common.Access;
import pleos.nextkey.common.Actor;
import pleos.nextkey.records.RentalRecords;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreReturnGuideTests {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final BookingService bookings=mock(BookingService.class);
    private final Access access=mock(Access.class);
    private final RentalRecords records=mock(RentalRecords.class);
    private final Actor actor=new Actor("owner","CUSTOMER");
    private final String session="return-guide-session",reservation="return-guide-reservation";

    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }

    private Map<?,?> guide(String state,Instant endedAt,Instant currentDeadline,Instant frozenDeadline) {
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of()));
        SecurityContextHolder.setContext(context);
        var row=new HashMap<String,Object>();
        row.put("id",session);
        row.put("reservation_id",reservation);
        row.put("vehicle_id","vehicle");
        row.put("started_at",CoreData.sql(Instant.now().minusSeconds(14400)));
        row.put("ended_at",endedAt==null?null:CoreData.sql(endedAt));
        row.put("state",state);
        row.put("return_state","UNCONFIRMED");
        when(access.session(session,actor)).thenReturn(row);
        when(bookings.lookup(reservation,false)).thenReturn(new ReservationView(reservation,"owner","vehicle",
                "CONFIRMED",Instant.now().minusSeconds(14400),currentDeadline,null,null));
        when(records.latestEnergy(session)).thenReturn(Map.of("status","UNKNOWN","unit","%"));
        when(db.queryForList("SELECT confirmed_due_at FROM end_request WHERE session_id=?",session))
                .thenReturn(frozenDeadline==null?List.of():List.of(Map.of("confirmed_due_at",CoreData.sql(frozenDeadline))));
        return (Map<?,?>)new CoreController(bookings,access,db,records,300,List.of(30,10)).guide(session);
    }

    @Test void lateEndUsesTheFrozenDeadlineBeforeALaterExtensionApproval() {
        Instant ended=Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        Instant priorDue=ended.minusSeconds(60),extendedDue=ended.plusSeconds(3600);
        for(String state:List.of("END_PENDING","ENDED")) {
            var result=guide(state,ended,extendedDue,priorDue);
            assertEquals(priorDue,result.get("confirmedEndAt"));
            assertEquals(true,result.get("overdue"));
            assertEquals(0L,result.get("remainingSeconds"));
        }
    }

    @Test void finishedTripDoesNotBecomeOverdueAsWallClockAdvances() {
        Instant ended=Instant.now().minusSeconds(7200).truncatedTo(ChronoUnit.MICROS);
        Instant due=ended.plusSeconds(1800);
        var result=guide("ENDED",ended,due,due);
        assertEquals(false,result.get("overdue"));
        assertEquals(1800L,result.get("remainingSeconds"));
    }

    @Test void activeTripStillUsesTheLiveApprovedDeadline() {
        Instant due=Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        var result=guide("ACTIVE",null,due,null);
        assertEquals(due,result.get("confirmedEndAt"));
        assertEquals(false,result.get("overdue"));
        long remaining=((Number)result.get("remainingSeconds")).longValue();
        assertTrue(remaining>3590 && remaining<=3600);
        verifyNoInteractions(db);
    }

    @Test void legacyEndedTripsWithoutAFinalRequestRetainTheirExistingDeadline() {
        Instant ended=Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        Instant due=ended.plusSeconds(1800);
        var result=guide("ENDED",ended,due,null);
        assertEquals(due,result.get("confirmedEndAt"));
        assertEquals(false,result.get("overdue"));
        assertEquals(1800L,result.get("remainingSeconds"));
    }
}
