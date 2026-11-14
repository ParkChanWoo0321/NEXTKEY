package pleos.nextkey.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import pleos.nextkey.common.ApiError;

/** A reading is client-reported even when its shape, unit and range are valid. */
public final class Snapshots {
    private Snapshots() {}

    public static void assertSourcesAllowed(Map<String,Object> raw, boolean demoAllowed) {
        if (demoAllowed || raw==null) return;
        if (raw.values().stream().anyMatch(item->item instanceof Map<?,?> map && "DEV_SIMULATOR".equals(map.get("source")))) {
            throw new ApiError(403,"SIMULATOR_DISABLED");
        }
    }

    public static Map<String, Object> normalize(Map<String, Object> input, String energyType, Instant now) {
        Map<String, Object> raw = input == null ? Map.of() : input;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("odometer", reading(raw.get("odometer"), "km", true, now));
        out.put("fuel", reading(raw.get("fuel"), "%", "FUEL".equals(energyType), now));
        out.put("battery", reading(raw.get("battery"), "%", "BATTERY".equals(energyType), now));
        out.put("warnings", reading(raw.get("warnings"), "code", true, now));
        return out;
    }

    private static Map<String, Object> reading(Object raw, String unit, boolean supported, Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unit", unit);
        out.put("verification", "CLIENT_REPORTED");
        if (!supported) return status(out, "NOT_APPLICABLE");
        if (!(raw instanceof Map<?,?> item)) return status(out, "UNKNOWN");
        Object source = item.get("source");
        if (!"VEHICLE_SDK".equals(source) && !"DEV_SIMULATOR".equals(source)) return status(out, "UNKNOWN_SOURCE");
        out.put("source", source);
        Instant time = instant(item.get("collectedAt"));
        if (time == null) return status(out, "UNKNOWN_TIME");
        out.put("collectedAt", time.toString());
        if (time.isAfter(now)) return status(out, "INVALID_TIME");
        if (time.isBefore(now.minusSeconds(120))) return status(out, "STALE");
        if (!"VALID".equals(item.get("status"))) return status(out, "UNCONFIRMED");
        if (unit.equals("code")) {
            if (!(item.get("codes") instanceof List<?> codes) || codes.size() > 64
                    || codes.stream().anyMatch(c -> !(c instanceof String s) || !s.matches("[A-Za-z0-9_.-]{1,64}"))) {
                return status(out, "INVALID_CODES");
            }
            out.put("codes", List.copyOf(codes));
        } else {
            if (!unit.equals(item.get("unit"))) return status(out, "UNIT_MISMATCH");
            if (!(item.get("value") instanceof Number number)) return status(out, "MISSING_VALUE");
            BigDecimal value;
            try { value = new BigDecimal(number.toString()); }
            catch (NumberFormatException ex) { return status(out, "INVALID_VALUE"); }
            if (value.signum() < 0 || ("%".equals(unit) && value.compareTo(BigDecimal.valueOf(100)) > 0)) {
                return status(out, "OUT_OF_RANGE");
            }
            out.put("value", value);
        }
        return status(out, "VALID");
    }

    private static Map<String, Object> status(Map<String, Object> out, String status) {
        out.put("status", status);
        return out;
    }

    static Instant instant(Object value) {
        if (value instanceof String s) {
            try { return Instant.parse(s); } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    static Map<String, Object> difference(Map<String,Object> start, Map<String,Object> end, String field) {
        if (!(start.get(field) instanceof Map<?,?> a) || !(end.get(field) instanceof Map<?,?> b)
                || !"VALID".equals(a.get("status")) || !"VALID".equals(b.get("status"))
                || !Objects.equals(a.get("unit"), b.get("unit"))
                || !(a.get("value") instanceof Number first) || !(b.get("value") instanceof Number last)) {
            return Map.of("status", "UNCONFIRMED");
        }
        Instant t1 = instant(a.get("collectedAt")), t2 = instant(b.get("collectedAt"));
        if (t1 == null || t2 == null || t2.isBefore(t1)) return Map.of("status", "TIME_REVERSED");
        if (!Objects.equals(a.get("source"), b.get("source"))) return Map.of("status", "SOURCE_CHANGED");
        BigDecimal diff = new BigDecimal(last.toString()).subtract(new BigDecimal(first.toString()));
        if ("odometer".equals(field) && diff.signum() < 0) return Map.of("status", "ODOMETER_REVERSED");
        // Energy is signed end minus start (charging/refuelling is possible). It is not consumption.
        return Map.of("status", "VALID", "unit", "odometer".equals(field) ? "km" : "%p", "value", diff);
    }
}
