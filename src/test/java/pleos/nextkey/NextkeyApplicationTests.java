package pleos.nextkey;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pleos.nextkey.lifecycle.Snapshots;
import java.time.Instant;
import java.util.Map;

class NextkeyApplicationTests {

    @Test
    void missingDistanceAndWrongEnergyTypeAreExplicit() {
        var snapshot = Snapshots.normalize(null, "BATTERY", Instant.now());
        assertEquals("UNKNOWN", ((Map<?,?>) snapshot.get("odometer")).get("status"));
        assertFalse(((Map<?,?>) snapshot.get("battery")).containsKey("value"));
        assertEquals("NOT_APPLICABLE", ((Map<?,?>) snapshot.get("fuel")).get("status"));
    }

}
