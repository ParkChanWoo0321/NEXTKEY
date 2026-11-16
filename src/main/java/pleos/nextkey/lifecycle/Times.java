package pleos.nextkey.lifecycle;

import java.sql.Timestamp;
import java.time.*;

final class Times {
    private Times() {}
    static Instant utc(Object value) {
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp time) return time.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof Instant time) return time;
        throw new IllegalArgumentException("Unexpected database time");
    }
}
