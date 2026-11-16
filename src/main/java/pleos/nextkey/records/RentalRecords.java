package pleos.nextkey.records;

import static pleos.nextkey.records.RecordData.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.Json;

/** Session and report services authorize the caller before requesting these private details. */
@Service
public class RentalRecords {
    private final RecordData data;
    private final VehicleEvents events;
    private final PersonalSettings settings;
    private final DamageRecords damage;

    RentalRecords(RecordData data,VehicleEvents events,PersonalSettings settings,DamageRecords damage) {
        this.data=data;
        this.events=events;
        this.settings=settings;
        this.damage=damage;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> report(String sessionId) {
        var timeline = events.timeline(sessionId);
        var activities = data.db.queryForList("SELECT id,created_at,state,detail FROM setting_activity WHERE session_id=? ORDER BY created_at,id",sessionId);
        activities.forEach(a -> a.put("detail",Json.read((String)a.get("detail"))));
        var recorded = timeline.stream().filter(r -> !"LOCATION".equals(r.get("kind"))).map(row -> {
            var item = withUtcTimes(row);
            item.remove("consent_id");
            item.put("payload",Json.read((String)item.get("payload")));
            return item;
        }).toList();
        Map<String,Object> summary = new LinkedHashMap<>();
        summary.put("settings",activities.stream().map(RecordData::withUtcTimes).toList());
        summary.put("locationDecision",events.consentState(sessionId));
        summary.put("consentHistory",data.db.queryForList("SELECT granted,policy_version,changed_at FROM location_consent WHERE session_id=? ORDER BY sequence_no",sessionId)
            .stream().map(RecordData::withUtcTimes).toList());
        summary.put("privateRoute",events.route(timeline));
        summary.put("vehicleEvents",recorded);
        summary.put("damage",damage.details(sessionId));
        return summary;
    }

    /** Recoverable vehicle end details; no route, private report text, photos or customer identifiers. */
    @Transactional(readOnly=true)
    public Map<String,Object> vehicleSummary(String sessionId) {
        var timeline=events.timeline(sessionId);
        var warningEvents=timeline.stream().filter(row -> "WARNING".equals(row.get("kind"))).map(row -> {
            Map<String,Object> warning=new LinkedHashMap<>();
            warning.put("collectedAt",instant(row.get("collected_at")));
            warning.put("source",row.get("source"));
            warning.put("status",row.get("status"));
            warning.put("warningCode",Json.read((String)row.get("payload")).get("warningCode"));
            return warning;
        }).toList();
        var damageDetails=damage.details(sessionId);
        Map<String,Object> safeDamage=new LinkedHashMap<>();
        for (String key:List.of("existingAtStart","allUnverifiedAtEnd")) {
            if (!(damageDetails.get(key) instanceof Map<?,?> detail)) continue;
            Map<String,Object> safe=new LinkedHashMap<>();
            safe.put("asOf",detail.get("asOf"));
            if (detail.containsKey("availability")) safe.put("availability",detail.get("availability"));
            var records=detail.get("records") instanceof List<?> list ? list : List.of();
            safe.put("count",records.size());
            Map<String,Long> byCategory=new TreeMap<>();
            Map<String,Long> byVerification=new TreeMap<>();
            for (Object item:records) {
                if (item instanceof DamageRecords.HandoverRecord record) {
                    byCategory.merge(record.category(),1L,Long::sum);
                    byVerification.merge(record.verification(),1L,Long::sum);
                }
            }
            safe.put("byCategory",byCategory);
            safe.put("byVerification",byVerification);
            if (detail.get("unknownVerificationRecords") instanceof List<?> unknown)
                safe.put("unknownVerificationCount",unknown.size());
            safeDamage.put(key,safe);
        }
        return Map.of("damage",safeDamage,"warningEvents",warningEvents,
                "dataGaps",events.intervals(timeline,"GAP"),"syncDelays",events.intervals(timeline,"SYNC"));
    }

    @Transactional
    public Map<String,Object> saveEndSettings(String sessionId,Map<String,Object> readings) {
        return settings.end(sessionId,readings);
    }

    public boolean accepted(String sessionId,List<String> ids) {
        if (ids==null || ids.stream().anyMatch(Objects::isNull)) return false;
        if (ids.isEmpty()) return true;
        Set<String> distinct = new HashSet<>(ids);
        var query = new NamedParameterJdbcTemplate(data.db);
        return distinct.size()==query.queryForObject("SELECT COUNT(*) FROM vehicle_event WHERE session_id=:session AND id IN (:ids)",
            Map.of("session",sessionId,"ids",distinct),Integer.class);
    }

    public Map<String,Object> latestEnergy(String sessionId) {
        var rows = data.db.queryForList("SELECT e.collected_at,e.source,e.status,e.payload FROM vehicle_event e " +
            "JOIN rental_session s ON s.id=e.session_id JOIN vehicle v ON v.id=s.vehicle_id WHERE s.id=? " +
            "AND e.kind='MEASUREMENT' AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.channel'))=v.energy_type ORDER BY e.collected_at DESC,e.id DESC LIMIT 1",sessionId);
        if (rows.isEmpty()) return Map.of();
        var r = rows.get(0);
        var p = Json.read((String)r.get("payload"));
        Instant at = instant(r.get("collected_at"));
        String status = (String)r.get("status");
        if ("VALID".equals(status) && at.isBefore(Instant.now().minusSeconds(data.env.getProperty(
            "nextkey.records.energy-max-age-seconds",Long.class,300L)))) status="STALE";
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("value","VALID".equals(status) ? p.get("value") : null);
        result.put("unit","%");
        result.put("source",r.get("source"));
        result.put("collectedAt",at);
        result.put("verification","CLIENT_REPORTED");
        result.put("status",status);
        return result;
    }
}
