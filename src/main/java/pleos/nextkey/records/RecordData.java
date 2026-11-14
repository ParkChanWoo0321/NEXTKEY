package pleos.nextkey.records;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pleos.nextkey.common.ApiError;

@Component
class RecordData {
    final JdbcTemplate db;
    final Environment env;

    RecordData(JdbcTemplate db, Environment env) {
        this.db = db;
        this.env = env;
    }

    Map<String,Object> session(String id, boolean lock) {
        return db.queryForList("SELECT * FROM rental_session WHERE id=?" + (lock ? " FOR UPDATE" : ""), id)
            .stream().findFirst().orElseThrow(() -> new ApiError(404, "SESSION_NOT_FOUND"));
    }

    Map<String,Object> active(String id) {
        var session = session(id, true);
        require("ACTIVE".equals(session.get("state")), 409, "SESSION_NOT_ACTIVE");
        return session;
    }

    void lockProfileOwner(String id) {
        var owner=session(id,false).get("account_id");
        db.queryForObject("SELECT id FROM account WHERE id=? FOR UPDATE",String.class,owner);
    }

    static void require(boolean ok, int status, String code) {
        if (!ok) throw new ApiError(status, code);
    }

    static String uuid() { return UUID.randomUUID().toString(); }

    static LocalDateTime stamp(Instant time) {
        return LocalDateTime.ofInstant(time.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    static Instant instant(Object value) {
        if (value instanceof Timestamp ts) return ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof LocalDateTime dt) return dt.toInstant(ZoneOffset.UTC);
        if (value instanceof Instant i) return i;
        return Instant.parse(String.valueOf(value));
    }

    static Map<String,Object> withUtcTimes(Map<String,Object> row) {
        Map<String,Object> fields = new LinkedHashMap<>(row);
        fields.replaceAll((key,value) -> value instanceof LocalDateTime || value instanceof Timestamp ? instant(value) : value);
        return fields;
    }

    static BigDecimal number(Object obj) {
        if (!(obj instanceof Number n)) throw new ApiError(400, "INVALID_NUMBER");
        return new BigDecimal(n.toString());
    }

    static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() == 1);
    }

    String source(String source) {
        require("VEHICLE_SDK".equals(source) ||
            ("DEV_SIMULATOR".equals(source) && env.acceptsProfiles(Profiles.of("dev & !prod"))),
            400, "UNSUPPORTED_SOURCE");
        return source;
    }

    static void safeAt(Instant observed, long seconds) {
        Instant now = Instant.now();
        require(observed != null && !observed.isAfter(now) && !observed.isBefore(now.minusSeconds(seconds)),
                409, "SAFETY_OBSERVATION_STALE");
    }
}
