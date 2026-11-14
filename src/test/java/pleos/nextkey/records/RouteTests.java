package pleos.nextkey.records;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RouteTests {
    private final VehicleEvents events = new VehicleEvents(new RecordData(null,new MockEnvironment()));
    private static final Instant START = Instant.parse("2026-04-01T12:00:00Z");

    private Map<String,Object> point(int seconds,String grant) {
        return Map.of("kind","LOCATION","collected_at",START.plusSeconds(seconds),"status","VALID",
            "consent_id",grant,"payload","{\"latitude\":37.5,\"longitude\":127}");
    }

    @Test void onlyContiguousStationarySamplesBecomeEstimatedStops() {
        var result=events.route(List.of(point(0,"grant1"),point(60,"grant1"),point(120,"grant1"),
            point(600,"grant1"),point(610,"grant2")));
        assertEquals(3,((List<?>)result.get("segments")).size());
        assertEquals(1,((List<?>)result.get("gaps")).size());
        var stops=(List<?>)result.get("estimatedStops");
        assertEquals(1,stops.size());
        assertEquals("ESTIMATED_STOP",((Map<?,?>)stops.get(0)).get("classification"));
    }
}
