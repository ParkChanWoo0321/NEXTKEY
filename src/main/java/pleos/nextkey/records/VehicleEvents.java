package pleos.nextkey.records;

import static pleos.nextkey.records.RecordData.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;

@Service
class VehicleEvents {
    record Input(String eventId, String kind, Instant collectedAt, String source, String channel,
        BigDecimal value, String unit, String warningCode, String reason, String consentId,
        Boolean permissionGranted, Boolean supported, Instant fixAt, BigDecimal latitude,
        BigDecimal longitude, BigDecimal accuracyMeters) {}
    record Reading(String status, Map<String,Object> payload, String consentId) {}
    record Consent(Boolean granted, String policyVersion) {}

    private final RecordData data;
    VehicleEvents(RecordData data) { this.data = data; }

    String policy() { return data.env.getProperty("nextkey.location.policy-version", "v1"); }
    private long seconds(String name, long fallback) {
        return data.env.getProperty("nextkey.location." + name, Long.class, fallback);
    }

    private Map<String,Object> latestConsent(String id) {
        return data.db.queryForList("SELECT id,granted,policy_version,changed_at FROM location_consent " +
            "WHERE session_id=? ORDER BY sequence_no DESC LIMIT 1", id).stream().findFirst().orElse(Map.of());
    }

    Map<String,Object> consentState(String id) {
        var row = latestConsent(id);
        boolean current = policy().equals(row.get("policy_version"));
        return Map.of("policyVersion", policy(), "latest", withUtcTimes(row),
                "collectionAllowed", current && flag(row.get("granted")),
                "requiresDecision", !current);
    }

    @Transactional
    public Map<String,Object> consent(String id, Consent decision) {
        data.active(id);
        require(decision.granted() != null, 400, "MISSING_CONSENT_DECISION");
        require(policy().equals(decision.policyVersion()), 409, "CONSENT_POLICY_CHANGED");
        var previous = latestConsent(id);
        if (policy().equals(previous.get("policy_version")) && flag(previous.get("granted")) == decision.granted())
            return consentState(id);
        data.db.update("INSERT INTO location_consent(id,session_id,granted,policy_version,changed_at) VALUES (?,?,?,?,?)",
            uuid(), id, decision.granted(), policy(), stamp(Instant.now()));
        return consentState(id);
    }

    @Transactional
    public Map<String,Object> accept(String id, Input event) {
        var s = data.session(id, true);
        require(event.eventId() != null && event.eventId().matches("[A-Za-z0-9_-]{1,64}"), 400, "INVALID_EVENT_ID");
        var old = data.db.queryForList("SELECT session_id,request_hash,received_at,status FROM vehicle_event WHERE id=?", event.eventId());
        if (!old.isEmpty()) {
            var r = old.get(0);
            require(id.equals(r.get("session_id")) && fingerprint(event, String.valueOf(r.get("status"))).equals(r.get("request_hash")),
                    409, "EVENT_ID_CONFLICT");
            return ack(event.eventId(), r.get("status"), instant(r.get("received_at")), true);
        }
        require(!"COMPLETE".equals(s.get("cleanup_state")), 409, "CLEANUP_COMPLETE");
        require("ACTIVE".equals(s.get("state")), 409, "SESSION_NOT_ACTIVE");
        Instant now = Instant.now();
        Instant at = event.collectedAt();
        require(at != null && !at.isBefore(instant(s.get("started_at"))) && !at.isAfter(now)
                        && (s.get("ended_at") == null || !at.isAfter(instant(s.get("ended_at")))),
                400, "EVENT_OUTSIDE_SESSION");
        data.source(event.source());
        Reading reading = switch (event.kind() == null ? "" : event.kind()) {
            case "LOCATION" -> location(id, event);
            case "MEASUREMENT" -> measurement(s.get("vehicle_id"), event);
            case "WARNING" -> {
                require(event.warningCode() != null && event.warningCode().matches("[A-Z0-9_]{1,48}"), 400, "INVALID_WARNING_CODE");
                yield new Reading("REPORTED", Map.of("warningCode", event.warningCode()), null);
            }
            case "GAP", "SYNC" -> {
                var allowed = "GAP".equals(event.kind()) ? Set.of("POWER_OFF", "HIDDEN", "UNAVAILABLE", "STORAGE_FAILURE","SIGNAL_LOST")
                    : Set.of("OFFLINE", "STORAGE_FAILURE", "UNSENT", "RETRYING");
                require(event.reason() != null && allowed.contains(event.reason()), 400, "INVALID_EVENT_REASON");
                yield new Reading(event.reason(), Map.of(), null);
            }
            default -> throw new ApiError(400, "UNKNOWN_EVENT_KIND");
        };
        try {
            data.db.update("INSERT INTO vehicle_event(id,session_id,kind,collected_at,received_at,source,verification,status,consent_id,payload,request_hash) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)", event.eventId(), id, event.kind(), stamp(at), stamp(now), event.source(),
                "CLIENT_REPORTED", reading.status(), reading.consentId(), Json.write(reading.payload()), fingerprint(event, reading.status()));
        } catch (DuplicateKeyException duplicate) { throw new ApiError(409, "EVENT_ID_CONFLICT"); }
        return ack(event.eventId(), reading.status(), now, false);
    }

    private static Map<String,Object> ack(String eventId, Object status, Instant received, boolean replay) {
        return Map.of("eventId", eventId, "receivedAt", received, "status", status, "acknowledged", true, "replayed", replay);
    }

    private String fingerprint(Input event, String status) {
        // A rejected position leaves no coordinate-derived digest in storage.
        Object input = event;
        if ("LOCATION".equals(event.kind()) && !"VALID".equals(status))
            input = Arrays.asList(event.eventId(), event.kind(), event.collectedAt(), event.source(), event.consentId(),
                event.permissionGranted(), event.supported(), event.fixAt());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(input).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private Reading measurement(Object vehicleId, Input e) {
        String channel = e.channel();
        require(channel != null && Set.of("ODOMETER","BATTERY","FUEL").contains(channel), 400, "INVALID_MEASUREMENT");
        String intended = channel.equals("ODOMETER") ? "km" : "%";
        String status = "VALID";
        if (!channel.equals("ODOMETER") && !channel.equals(data.db.queryForObject("SELECT energy_type FROM vehicle WHERE id=?", String.class, vehicleId)))
            status = "UNSUPPORTED";
        else if (e.value() == null) status = "UNKNOWN";
        else if (!intended.equals(e.unit())) status = "UNIT_MISMATCH";
        else if (e.value().signum() < 0 || ("%".equals(intended) && e.value().compareTo(BigDecimal.valueOf(100)) > 0))
            status = "OUT_OF_RANGE";
        Map<String,Object> p = new LinkedHashMap<>();
        p.put("channel", channel);
        p.put("unit", intended);
        require(e.unit() == null || e.unit().length() <= 12, 400, "INVALID_UNIT");
        p.put("receivedUnit", e.unit());
        p.put("value", "VALID".equals(status) ? e.value() : null);
        return new Reading(status, p, null);
    }

    private Reading location(String id, Input e) {
        var c = latestConsent(id);
        String reason = null;
        if (!policy().equals(c.get("policy_version"))) reason = "CONSENT_REQUIRED";
        else if (!flag(c.get("granted")) || !Objects.equals(c.get("id"), e.consentId()) ||
                e.collectedAt().isBefore(instant(c.get("changed_at")))) reason = "NO_CURRENT_GRANT";
        else if (!Boolean.TRUE.equals(e.permissionGranted())) reason = "PERMISSION_MISSING";
        else if (!Boolean.TRUE.equals(e.supported())) reason = "UNSUPPORTED";
        else if (e.fixAt() == null || e.latitude() == null || e.longitude() == null) reason = "NO_FIX";
        else if (e.fixAt().isAfter(e.collectedAt()) || e.fixAt().isBefore(instant(c.get("changed_at"))) ||
                e.fixAt().isBefore(e.collectedAt().minusSeconds(seconds("max-fix-age-seconds", 30)))) reason = "STALE_FIX";
        else if (e.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0 || e.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0)
            reason = "INVALID_COORDINATES";
        else if (e.accuracyMeters() == null || e.accuracyMeters().signum() < 0 || e.accuracyMeters().compareTo(
                BigDecimal.valueOf(seconds("max-accuracy-meters", 50))) > 0) reason = "LOW_ACCURACY";
        if (reason != null) return new Reading(reason, Map.of(), null);
        return new Reading("VALID", Map.of("latitude",e.latitude(),"longitude",e.longitude(),"fixAt",e.fixAt(),
            "accuracyMeters",e.accuracyMeters()), (String)c.get("id"));
    }

    List<Map<String,Object>> timeline(String id) {
        return data.db.queryForList("SELECT id,kind,collected_at,received_at,source,verification,status,consent_id,payload " +
            "FROM vehicle_event WHERE session_id=? ORDER BY collected_at, CASE WHEN kind='GAP' THEN 0 ELSE 1 END,id", id);
    }

    Map<String,Object> route(List<Map<String,Object>> events) {
        List<List<Map<String,Object>>> segments = new ArrayList<>();
        List<Map<String,Object>> points = null;
        List<Map<String,Object>> gaps = new ArrayList<>();
        Instant last = null;
        Object grant = null;
        for (var row : events) {
            String kind = (String) row.get("kind");
            if (!"LOCATION".equals(kind) && !"GAP".equals(kind)) continue;
            Instant at = instant(row.get("collected_at"));
            if (!"VALID".equals(row.get("status"))) {
                gaps.add(Map.of("at",at,"reason",row.get("status")));
                points = null;
                last = null;
                continue;
            }
            boolean intervalGap = last != null && at.isAfter(last.plusSeconds(seconds("segment-gap-seconds",120)));
            if (intervalGap) gaps.add(Map.of("from", last,"to",at,"reason","NO_SAMPLES"));
            if (points == null || intervalGap || !Objects.equals(grant,row.get("consent_id"))) {
                points = new ArrayList<>();
                segments.add(points);
            }
            var point = new LinkedHashMap<>(Json.read((String)row.get("payload")));
            point.put("collectedAt", at);
            points.add(point);
            last = at;
            grant = row.get("consent_id");
        }
        List<Map<String,Object>> stops = new ArrayList<>();
        for (var segment : segments) estimateStops(segment, stops);
        return Map.of("segments",segments,"gaps",gaps,"estimatedStops",stops);
    }

    private void estimateStops(List<Map<String,Object>> points, List<Map<String,Object>> stops) {
        int start = 0;
        for (int i = 1; i <= points.size(); i++) {
            var anchor = points.get(start);
            if (i < points.size() && meters(anchor, points.get(i)) <= seconds("stop-radius-meters", 30)) continue;
            Instant from = instant(anchor.get("collectedAt")), until = instant(points.get(i-1).get("collectedAt"));
            if (i-start >= 2 && Duration.between(from,until).getSeconds() >= seconds("stop-dwell-seconds",120)) {
                stops.add(Map.of("from",from,"to",until,"latitude",anchor.get("latitude"),"longitude",anchor.get("longitude"),
                    "classification","ESTIMATED_STOP"));
            }
            start = i;
        }
    }

    private static double meters(Map<String,Object> a, Map<String,Object> b) {
        double p1 = Math.toRadians(number(a.get("latitude")).doubleValue()), p2 = Math.toRadians(number(b.get("latitude")).doubleValue());
        double lon = Math.toRadians(number(a.get("longitude")).subtract(number(b.get("longitude"))).doubleValue());
        double h = Math.pow(Math.sin((p2-p1)/2),2) + Math.cos(p1)*Math.cos(p2)*Math.pow(Math.sin(lon/2),2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1,h)));
    }
}
