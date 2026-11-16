package pleos.nextkey.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingPrecisionTests {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ReservationSource source=mock(ReservationSource.class);
    private final BookingAvailability availability=mock(BookingAvailability.class);
    private final BookingService bookings=new BookingService(db,source,availability);
    private final Actor owner=new Actor("customer","CUSTOMER");
    private final Actor operator=new Actor("operator","OPERATOR");
    private final Instant start=Instant.parse("2026-11-15T09:00:00.123456789Z");
    private final Instant end=Instant.parse("2026-11-15T10:00:00.987654321Z");
    private final Instant storedStart=Instant.parse("2026-11-15T09:00:00.123456Z");
    private final Instant storedEnd=Instant.parse("2026-11-15T10:00:00.987654Z");

    private ReservationView request(Instant from,Instant until) {
        return new ReservationView(null,owner.id(),"car",null,from,until,null,null);
    }

    @Test void aPositiveIntervalThatCollapsesInTheDatabaseIsRejectedBeforeAnyWork() {
        var input=request(Instant.parse("2026-11-15T09:00:00.000000100Z"),
                Instant.parse("2026-11-15T09:00:00.000000200Z"));
        assertEquals("INVALID_RESERVATION",assertThrows(ApiError.class,()->bookings.create(owner,input)).code());
        assertEquals("INVALID_RESERVATION",assertThrows(ApiError.class,
                ()->bookings.replacement(operator,"previous-session",input)).code());
        verifyNoInteractions(db,source,availability);
    }

    @Test void creationReturnsTheSameMicrosecondTimesThatItChecksAndWrites() {
        when(source.change(any())).thenAnswer(call->call.getArgument(0));
        var result=bookings.create(owner,request(start,end));
        assertEquals(storedStart,result.startsAt());
        assertEquals(storedEnd,result.confirmedEndAt());

        var forwarded=ArgumentCaptor.forClass(ReservationView.class);
        verify(source).change(forwarded.capture());
        assertEquals(storedStart,forwarded.getValue().startsAt());
        assertEquals(storedEnd,forwarded.getValue().confirmedEndAt());
        verify(availability).requireAvailable(owner.id(),"car",storedStart,storedEnd,null,null);

        var write=mockingDetails(db).getInvocations().iterator().next();
        assertEquals("update",write.getMethod().getName());
        var parameters=(Object[])write.getRawArguments()[1];
        assertEquals(LocalDateTime.ofInstant(storedStart,ZoneOffset.UTC),parameters[4]);
        assertEquals(LocalDateTime.ofInstant(storedEnd,ZoneOffset.UTC),parameters[5]);
    }

    @Test void aProviderIntervalThatCollapsesAtMicrosecondsNeverReachesTheDatabase() {
        when(source.change(any())).thenAnswer(call->{
            ReservationView requested=call.getArgument(0);
            return new ReservationView(requested.id(),requested.accountId(),requested.vehicleId(),requested.status(),
                    Instant.parse("2026-11-15T09:00:00.000000100Z"),
                    Instant.parse("2026-11-15T09:00:00.000000200Z"),requested.destination(),requested.returnPlace());
        });
        assertEquals("INVALID_PROVIDER_RESERVATION",assertThrows(ApiError.class,
                ()->bookings.create(owner,request(start,end))).code());
        verifyNoInteractions(db);
    }

    @Test void providerChangesUseMicrosecondsInTheUpdateResponseAndPersistence() {
        var row=Map.<String,Object>of("id","reservation","account_id",owner.id(),"vehicle_id","car",
                "status","CONFIRMED","starts_at",LocalDateTime.ofInstant(storedStart,ZoneOffset.UTC),
                "confirmed_end_at",LocalDateTime.ofInstant(storedEnd,ZoneOffset.UTC));
        when(db.queryForList(contains("FROM reservation"),eq("reservation"))).thenReturn(List.of(row));
        when(db.queryForList(contains("FROM rental_session"),eq("reservation"))).thenReturn(List.of());
        when(source.change(any())).thenAnswer(call->{
            ReservationView requested=call.getArgument(0);
            return new ReservationView(requested.id(),requested.accountId(),requested.vehicleId(),requested.status(),
                    start,end,requested.destination(),requested.returnPlace());
        });
        var result=bookings.update("reservation",owner,"destination",null);
        assertEquals(storedStart,result.startsAt());
        assertEquals(storedEnd,result.confirmedEndAt());
        var write=mockingDetails(db).getInvocations().stream()
                .filter(invocation->invocation.getMethod().getName().equals("update")).findFirst().orElseThrow();
        var parameters=(Object[])write.getRawArguments()[1];
        assertEquals(LocalDateTime.ofInstant(storedStart,ZoneOffset.UTC),parameters[1]);
        assertEquals(LocalDateTime.ofInstant(storedEnd,ZoneOffset.UTC),parameters[2]);
    }

    @Test void aLegacyReplacementFingerprintStillRecoversItsOriginalResult() {
        String place="{\"name\":null,\"address\":null,\"latitude\":null,\"longitude\":null,\"coordinateStatus\":\"UNKNOWN\"}";
        String legacyRequest="{\"accountId\":\"customer\",\"vehicleId\":\"car\",\"startsAt\":\""+start+
                "\",\"confirmedEndAt\":\""+end+"\",\"destination\":"+place+",\"returnPlace\":"+place+"}";
        String originalResult="{\"id\":\"original-replacement\",\"accountId\":\"customer\",\"vehicleId\":\"car\",\"status\":\"CONFIRMED\","+
                "\"startsAt\":\""+start+"\",\"confirmedEndAt\":\""+end+"\",\"destination\":"+place+",\"returnPlace\":"+place+"}";
        when(db.queryForList(contains("FROM rental_session"),eq("previous-session"))).thenReturn(List.of(Map.of(
                "id","previous-session","account_id","previous-customer","vehicle_id","previous-car","reservation_id","previous-reservation")));
        when(db.queryForList(contains("FROM session_replacement"),eq("previous-session"))).thenReturn(List.of(Map.of(
                "preparation_digest",CoreData.hash(legacyRequest),"preparation_result",originalResult)));
        var recovered=bookings.replacement(operator,"previous-session",request(start,end));
        assertEquals("original-replacement",recovered.id());
        assertEquals(start,recovered.startsAt());
        assertEquals(end,recovered.confirmedEndAt());
        verifyNoInteractions(source);
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(call->call.getMethod().getName().equals("update")));
    }
}
