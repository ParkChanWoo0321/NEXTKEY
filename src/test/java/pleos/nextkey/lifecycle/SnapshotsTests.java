package pleos.nextkey.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pleos.nextkey.common.ApiError;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotsTests {
    private final Instant now=Instant.parse("2026-11-12T06:00:00Z");

    private Map<String,Object> odometer(int value,String unit,Instant at) {
        return Snapshots.normalize(Map.of("odometer",Map.of("value",value,"unit",unit,"source","DEV_SIMULATOR",
                        "status","VALID","collectedAt",at.toString())),"BATTERY",now);
    }
    @Test void demoDistanceUsesOnlyComparableValues() {
        var start=odometer(32230,"km",now.minusSeconds(100));
        var end=odometer(32412,"km",now);
        assertEquals(new BigDecimal("182"),Snapshots.difference(start,end,"odometer").get("value"));
        assertFalse(Snapshots.difference(start,odometer(30000,"km",now),"odometer").containsKey("value"));
        assertFalse(Snapshots.difference(start,odometer(32412,"m",now),"odometer").containsKey("value"));
        assertFalse(Snapshots.difference(start,odometer(32412,"km",now.minusSeconds(150)),"odometer").containsKey("value"));
    }
    @Test void missingOrUnsupportedDoesNotGenerateZeroOrNormalWarnings() {
        var snapshot=Snapshots.normalize(null,"BATTERY",now);
        assertEquals("UNKNOWN",((Map<?,?>)snapshot.get("battery")).get("status"));
        assertEquals("NOT_APPLICABLE",((Map<?,?>)snapshot.get("fuel")).get("status"));
        assertFalse(((Map<?,?>)snapshot.get("warnings")).containsKey("codes"));
        assertFalse(((Map<?,?>)snapshot.get("odometer")).containsKey("value"));
    }
    @Test void simulatedReadingsMustBeDevOnly() {
        assertThrows(ApiError.class,()->Snapshots.assertSourcesAllowed(Map.of("battery",Map.of("source","DEV_SIMULATOR")),false));
    }
    @Test void warningComparisonRequiresComparableReadingsAndDoesNotInventNewWarnings() {
        var start=Snapshots.normalize(Map.of("warnings",Map.of("codes",java.util.List.of("WASHER_LOW"),
                "source","DEV_SIMULATOR","status","VALID","collectedAt",now.minusSeconds(30).toString())),"BATTERY",now);
        var end=Snapshots.normalize(Map.of("warnings",Map.of("codes",java.util.List.of("WASHER_LOW","BRAKE_LOW","BRAKE_LOW"),
                "source","DEV_SIMULATOR","status","VALID","collectedAt",now.toString())),"BATTERY",now);
        assertEquals(java.util.List.of("BRAKE_LOW"),Snapshots.warningDifference(start,end).get("newCodes"));
        assertEquals("UNCONFIRMED",Snapshots.warningDifference(start,Snapshots.normalize(null,"BATTERY",now)).get("status"));
    }
}
