package pleos.nextkey.records;

import static pleos.nextkey.records.RecordData.*;
import com.fasterxml.jackson.annotation.JsonInclude;
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
        BigDecimal longitude, BigDecimal accuracyMeters,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean qualityAccepted,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long timestampMillis,
        @JsonInclude(JsonInclude.Include.NON_NULL) String intervalId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String intervalKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant from,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant to) {
        // Keep source compatibility for clients and test fixtures using the original DTO.
        Input(String eventId, String kind, Instant collectedAt, String source, String channel,
              BigDecimal value, String unit, String warningCode, String reason, String consentId,
              Boolean permissionGranted, Boolean supported, Instant fixAt, BigDecimal latitude,
              BigDecimal longitude, BigDecimal accuracyMeters) {
            this(eventId,kind,collectedAt,source,channel,value,unit,warningCode,reason,consentId,
                permissionGranted,supported,fixAt,latitude,longitude,accuracyMeters,
                null,null,null,null,null,null,null);
        }
    }
    record Reading(String status, Map<String,Object> payload, String consentId) {}
    record Consent(Boolean granted, String policyVersion,
                   @JsonInclude(JsonInclude.Include.NON_NULL) String expectedConsentId) {
        Consent(Boolean granted,String policyVersion) { this(granted,policyVersion,null); }
    }

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
        boolean active = "ACTIVE".equals(data.session(id,false).get("state"));
        return Map.of("policyVersion", policy(), "latest", withUtcTimes(row),
                "collectionAllowed", active && current && flag(row.get("granted")),
                "requiresDecision", !current);
    }

    @Transactional
    public Map<String,Object> consent(String id, Consent decision) {
        data.active(id);
        require(decision.granted() != null, 400, "MISSING_CONSENT_DECISION");
        require(policy().equals(decision.policyVersion()), 409, "CONSENT_POLICY_CHANGED");
        var previous = latestConsent(id);
        if (decision.expectedConsentId()!=null)
            require(decision.expectedConsentId().equals(previous.getOrDefault("id","")),409,"CONSENT_STATE_CHANGED");
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
        if (!"ACTIVE".equals(s.get("state"))) {
            String manifest = data.db.queryForObject("SELECT pending_event_ids FROM end_request WHERE session_id=?",String.class,id);
            require(((List<?>)Json.read(manifest).get("ids")).contains(event.eventId()),409,"EVENT_NOT_DECLARED_AT_END");
        }
        Instant now = Instant.now();
        Instant at = event.collectedAt();
        require(at != null && !at.isBefore(instant(s.get("started_at"))) && !at.isAfter(now)
                        && (s.get("ended_at") == null || !at.isAfter(instant(s.get("ended_at")))),
                400, "EVENT_OUTSIDE_SESSION");
        data.source(event.source());
        require(!hasInterval(event) || "GAP".equals(event.kind()) || "SYNC".equals(event.kind()),400,"INVALID_INTERVAL_KIND");
        Reading reading = switch (event.kind() == null ? "" : event.kind()) {
            case "LOCATION" -> location(id, event);
            case "MEASUREMENT" -> measurement(s.get("vehicle_id"), event);
            case "WARNING" -> {
                // Preserve the SDK identifier, including case/dots/hyphens, just as snapshots do.
                require(event.warningCode() != null && event.warningCode().matches("[A-Za-z0-9_.-]{1,64}"), 400, "INVALID_WARNING_CODE");
                yield new Reading("REPORTED", Map.of("warningCode", event.warningCode()), null);
            }
            case "GAP", "SYNC" -> {
                var allowed = "GAP".equals(event.kind()) ? Set.of("POWER_OFF", "HIDDEN", "UNAVAILABLE", "STORAGE_FAILURE","SIGNAL_LOST")
                    : Set.of("OFFLINE", "STORAGE_FAILURE", "UNSENT", "RETRYING");
                require(event.reason() != null && allowed.contains(event.reason()), 400, "INVALID_EVENT_REASON");
                yield new Reading(event.reason(), interval(id,s,event), null);
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
        if ("LOCATION".equals(event.kind()) && !"VALID".equals(status)) {
            List<Object> safe = new ArrayList<>(Arrays.asList(event.eventId(), event.kind(), event.collectedAt(), event.source(), event.consentId(),
                event.permissionGranted(), event.supported(), event.fixAt()));
            // Append new non-coordinate metadata only when supplied, retaining existing retry hashes.
            Map<String,Object> metadata=new LinkedHashMap<>();
            if (event.confidence()!=null) metadata.put("confidence",event.confidence());
            if (event.qualityAccepted()!=null) metadata.put("qualityAccepted",event.qualityAccepted());
            if (event.timestampMillis()!=null) metadata.put("timestampMillis",event.timestampMillis());
            if (!metadata.isEmpty()) safe.add(metadata);
            input=safe;
        }
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
        Instant fix = e.fixAt();
        if (fix == null && e.timestampMillis() != null) {
            try { fix = Instant.ofEpochMilli(e.timestampMillis()); }
            catch (DateTimeException ignored) { /* invalid timestamp is a coordinate-free NO_FIX */ }
        }
        if (reason == null && (fix == null || e.latitude() == null || e.longitude() == null)) reason = "NO_FIX";
        else if (reason == null && e.fixAt()!=null && e.timestampMillis()!=null &&
                !e.fixAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).equals(Instant.ofEpochMilli(e.timestampMillis())))
            reason = "FIX_TIME_MISMATCH";
        else if (reason == null && (fix.isAfter(e.collectedAt()) || fix.isBefore(instant(c.get("changed_at"))) ||
                fix.isBefore(e.collectedAt().minusSeconds(seconds("max-fix-age-seconds", 30))))) reason = "STALE_FIX";
        else if (reason == null && (e.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0 || e.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0))
            reason = "INVALID_COORDINATES";
        else if (reason == null) reason = locationQuality(e);
        if (reason != null) return new Reading(reason, Map.of(), null);
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("latitude",e.latitude());payload.put("longitude",e.longitude());payload.put("fixAt",fix);
        if (e.accuracyMeters()!=null) payload.put("accuracyMeters",e.accuracyMeters());
        if (e.confidence()!=null) payload.put("confidence",e.confidence());
        if (e.qualityAccepted()!=null) payload.put("qualityAccepted",e.qualityAccepted());
        payload.put("qualityBasis",e.confidence()!=null || e.qualityAccepted()!=null ? "SDK_CONFIDENCE" : "ACCURACY_METERS");
        return new Reading("VALID", payload, (String)c.get("id"));
    }

    private String locationQuality(Input e) {
        // SDK confidence is dimensionless. Never fabricate metre accuracy from it.
        boolean sdkQuality = e.confidence()!=null || e.qualityAccepted()!=null;
        if (sdkQuality && (!Boolean.TRUE.equals(e.qualityAccepted()) || e.confidence()==null ||
                e.confidence().signum()<0 || e.confidence().compareTo(BigDecimal.ONE)>0)) return "LOW_CONFIDENCE";
        if (e.accuracyMeters()!=null && (e.accuracyMeters().signum()<0 || e.accuracyMeters().compareTo(
                BigDecimal.valueOf(seconds("max-accuracy-meters",50)))>0)) return "LOW_ACCURACY";
        return !sdkQuality && e.accuracyMeters()==null ? "LOW_ACCURACY" : null;
    }

    private static boolean hasInterval(Input e) {
        return e.intervalId()!=null || e.intervalKind()!=null || e.from()!=null || e.to()!=null;
    }

    private Map<String,Object> interval(String id,Map<String,Object> session,Input e) {
        if (!hasInterval(e)) return Map.of(); // legacy point-in-time gap
        require(e.intervalId()!=null && e.intervalId().matches("[A-Za-z0-9_-]{1,64}") &&
            e.intervalKind()!=null && e.intervalKind().matches("[A-Za-z0-9_-]{1,32}"),400,"INVALID_INTERVAL_ID");
        require(e.from()!=null && !e.from().isBefore(instant(session.get("started_at"))) && !e.from().isAfter(e.collectedAt()) &&
            (e.to()==null || (!e.to().isBefore(e.from()) && !e.to().isAfter(e.collectedAt()))),400,"INVALID_INTERVAL_TIME");
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("intervalId",e.intervalId());payload.put("intervalKind",e.intervalKind());
        payload.put("from",e.from());payload.put("to",e.to());
        // The session row is locked by accept: reordered open/close delivery is safe, conflicting intervals are not.
        var prior = data.db.queryForList("SELECT kind,status,payload FROM vehicle_event WHERE session_id=? " +
            "AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.intervalId'))=?",id,e.intervalId());
        for (var row : prior) {
            var p = Json.read((String)row.get("payload"));
            require(e.kind().equals(row.get("kind")) && e.reason().equals(row.get("status")) &&
                e.intervalKind().equals(p.get("intervalKind")) && e.from().equals(instant(p.get("from"))) &&
                (e.to()==null || p.get("to")==null || e.to().equals(instant(p.get("to")))),409,"INTERVAL_ID_CONFLICT");
        }
        return payload;
    }

    List<Map<String,Object>> timeline(String id) {
        return data.db.queryForList("SELECT id,kind,collected_at,received_at,source,verification,status,consent_id,payload " +
            "FROM vehicle_event WHERE session_id=? ORDER BY collected_at, CASE WHEN kind='GAP' THEN 0 ELSE 1 END,id", id);
    }

    Map<String,Object> route(List<Map<String,Object>> events) {
        List<List<Map<String,Object>>> segments = new ArrayList<>();
        List<Map<String,Object>> points = null;
        var collectionGaps = intervals(events,"GAP");
        List<Map<String,Object>> gaps = new ArrayList<>(collectionGaps);
        Instant last = null;
        Object grant = null;
        for (var row : events) {
            String kind = (String) row.get("kind");
            if (!"LOCATION".equals(kind) && !"GAP".equals(kind)) continue;
            Instant at = instant(row.get("collected_at"));
            if (!"VALID".equals(row.get("status"))) {
                if ("LOCATION".equals(kind)) gaps.add(Map.of("at",at,"reason",row.get("status")));
                points = null;
                last = null;
                continue;
            }
            boolean intervalGap = last != null && at.isAfter(last.plusSeconds(seconds("segment-gap-seconds",120)));
            if (intervalGap) gaps.add(Map.of("from", last,"to",at,"reason","NO_SAMPLES"));
            boolean explicitGap = false;
            if (last!=null) for (var gap : collectionGaps) {
                if (!gap.containsKey("from")) continue;
                Instant from=instant(gap.get("from")), to=gap.get("to")==null ? null : instant(gap.get("to"));
                if (!from.isAfter(at) && (to==null || to.isAfter(last))) { explicitGap=true;break; }
            }
            if (points == null || intervalGap || explicitGap || !Objects.equals(grant,row.get("consent_id"))) {
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
        return Map.of("segments",segments,"gaps",gaps,"estimatedStops",stops,
            "dataGaps",collectionGaps,"syncDelays",intervals(events,"SYNC"));
    }

    /** Explicit intervals are merged by identity; old point events remain points, never invented durations. */
    List<Map<String,Object>> intervals(List<Map<String,Object>> events,String kind) {
        Map<String,Map<String,Object>> merged = new LinkedHashMap<>();
        List<Map<String,Object>> result = new ArrayList<>();
        for (var row : events) {
            if (!kind.equals(row.get("kind"))) continue;
            var payload = Json.read((String)row.get("payload"));
            if (payload.get("intervalId")==null) {
                result.add(Map.of("at",instant(row.get("collected_at")),"reason",row.get("status"),"interval",false));
                continue;
            }
            String key=(String)payload.get("intervalId");
            var interval = merged.get(key);
            if (interval==null) {
                interval = new LinkedHashMap<>();
                interval.put("intervalId",key);interval.put("kind",payload.get("intervalKind"));
                interval.put("reason",row.get("status"));interval.put("from",instant(payload.get("from")));
                interval.put("to",null);interval.put("interval",true);
                merged.put(key,interval);result.add(interval);
            }
            if (payload.get("to")!=null) interval.put("to",instant(payload.get("to")));
        }
        for (var interval : merged.values()) {
            boolean closed=interval.get("to")!=null;
            interval.put("closed",closed);
            interval.put("durationSeconds",closed ? Duration.between(instant(interval.get("from")),instant(interval.get("to"))).getSeconds() : null);
        }
        result.sort(Comparator.comparing(r -> instant(r.get(r.containsKey("from") ? "from" : "at"))));
        return result;
    }

    private void estimateStops(List<Map<String,Object>> points, List<Map<String,Object>> stops) {
        int start = 0;
        for (int i = 1; i <= points.size(); i++) {
            var anchor = points.get(start);
            if (i < points.size() && meters(anchor, points.get(i)) <= seconds("stop-radius-meters", 30)) continue;
            Instant from = instant(anchor.get("collectedAt")), until = instant(points.get(i-1).get("collectedAt"));
            if (i-start >= 2 && Duration.between(from,until).getSeconds() >= seconds("stop-dwell-seconds",60)) {
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
