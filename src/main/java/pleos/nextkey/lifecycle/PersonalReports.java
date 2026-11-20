package pleos.nextkey.lifecycle;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;
import pleos.nextkey.records.RentalRecords;

import java.time.*;
import java.util.*;

@Service
@EnableScheduling
class PersonalReports {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshotRead;
    private final RentalRecords records;
    private DevelopmentReportFaults developmentFaults;

    PersonalReports(JdbcTemplate db, TransactionTemplate transactions,RentalRecords records) {
        this.db=db;
        this.transactions=transactions;
        this.records=records;
        snapshotRead=new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        snapshotRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotRead.setReadOnly(true);
    }

    @Autowired(required=false)
    void developmentFaults(DevelopmentReportFaults faults) { developmentFaults=faults; }

    @Scheduled(fixedDelayString="${nextkey.report.interval-ms:5000}")
    public void pending() {
        // Waiting for one offline car must not starve other completed reports in the batch.
        for (String id:db.queryForList("""
                SELECT s.id FROM rental_session s JOIN end_request r ON r.session_id=s.id
                WHERE s.state='ENDED' AND s.report_state='PENDING' AND NOT EXISTS (
                    SELECT 1 FROM JSON_TABLE(r.pending_event_ids,'$.ids[*]'
                        COLUMNS(event_id VARCHAR(64) PATH '$')) declared
                    LEFT JOIN vehicle_event e ON e.session_id=s.id AND e.id=CAST(declared.event_id AS BINARY)
                    WHERE e.id IS NULL)
                ORDER BY s.ended_at,s.id LIMIT 20
                """,String.class)) {
            try { generate(id,true,false); } catch (ApiError ignored) { /* state is persisted for the phone's retry */ }
        }
    }

    Map<String,Object> read(String id,String customer) {
        var owners=db.queryForList("SELECT account_id,state FROM rental_session WHERE id=?",id);
        if (owners.isEmpty()) throw new ApiError(404,"SESSION_NOT_FOUND");
        if (!customer.equals(owners.get(0).get("account_id"))) throw new ApiError(403,"NOT_REPORT_OWNER");
        if (!"ENDED".equals(owners.get(0).get("state"))) throw new ApiError(409,"REPORT_NOT_AVAILABLE");
        // An authenticated customer read has always attempted report regeneration, including after cleanup.
        return generate(id,false,true);
    }

    private Map<String,Object> generate(String id,boolean onlyPending,boolean explicitRetry) {
        try {
            var snapshot=snapshotRead.execute(tx->assemble(id,onlyPending));
            if (snapshot==null) return Map.of();
            if (snapshot.payload()!=null && developmentFaults!=null && developmentFaults.block(id,explicitRetry))
                throw new ApiError(503,"REPORT_GENERATION_FAILED");
            return transactions.execute(tx->publish(id,snapshot));
        } catch (RuntimeException failure) {
            // Keep the last published document readable when only a refresh failed.
            try {
                var cached=snapshotRead.execute(tx->cached(id));
                if (cached!=null) return cached;
            } catch (RuntimeException ignored) { /* A database outage may make the cached copy unavailable too. */ }
            db.update("""
                    UPDATE rental_session SET report_state='FAILED' WHERE id=? AND state='ENDED' AND report_state='PENDING'
                    """,id);
            throw new ApiError(503,"REPORT_GENERATION_FAILED");
        }
    }

    private record Snapshot(LocalDateTime asOf, LocalDateTime generated, String payload,
                            int declaredCount, List<String> missing) {}

    private List<String> declared(String id) {
        String json=db.queryForObject("SELECT pending_event_ids FROM end_request WHERE session_id=?",String.class,id);
        Object raw=Json.read(json).get("ids");
        if (!(raw instanceof List<?> ids)) throw new IllegalStateException("Invalid final event manifest");
        return ids.stream().map(Object::toString).toList();
    }

    private List<String> missing(String id,List<String> declared) {
        if (declared.isEmpty()) return List.of();
        var accepted=new NamedParameterJdbcTemplate(db).queryForList(
                "SELECT id FROM vehicle_event WHERE session_id=:session AND id IN (:ids)",
                Map.of("session",id,"ids",declared),String.class);
        var found=new HashSet<>(accepted);
        return declared.stream().filter(event->!found.contains(event)).toList();
    }

    private Map<String,Object> waiting(String id,int declaredCount,List<String> missing,Instant asOf) {
        return Map.of("sessionId",id,"reportState","PENDING","dataComplete",false,
                "declaredEventCount",declaredCount,"acceptedDeclaredEventCount",declaredCount-missing.size(),
                "pendingEventIds",missing,"asOf",asOf.toString());
    }

    private Snapshot assemble(String id,boolean onlyPending) {
        // This first ordinary SELECT establishes a single InnoDB MVCC read view for all component queries.
        var s=db.queryForMap("SELECT s.*,UTC_TIMESTAMP(6) AS snapshot_at FROM rental_session s WHERE id=?",id);
        if (onlyPending && !"PENDING".equals(s.get("report_state"))) return null;
        var asOf=Times.utc(s.get("snapshot_at"));
        var declared=declared(id);
        var missing=missing(id,declared);
        if (!missing.isEmpty()) return new Snapshot(LocalDateTime.ofInstant(asOf,ZoneOffset.UTC),
                LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC),null,declared.size(),missing);
        var v=db.queryForMap("SELECT model,energy_type FROM vehicle WHERE id=?",s.get("vehicle_id"));
        var endRequest=db.queryForMap("SELECT requested_at,confirmed_due_at,final_safety,final_settings FROM end_request WHERE session_id=?",id);
        var start=Json.read((String)s.get("start_json"));
        var end=Json.read((String)s.get("end_json"));
        Instant due=Times.utc(endRequest.get("confirmed_due_at"));
        Instant ended=Times.utc(s.get("ended_at"));
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("sessionId",id);
        body.put("reservationId",s.get("reservation_id"));
        body.put("vehicle",Map.of("id",s.get("vehicle_id"),"model",v.get("model"),"energyType",v.get("energy_type")));
        body.put("startedAt",Times.utc(s.get("started_at")).toString());
        body.put("endedAt",ended.toString());
        body.put("endReceivedAt",Times.utc(endRequest.get("requested_at")).toString());
        body.put("startSnapshot",start);
        body.put("endSnapshot",end);
        body.put("distanceKm",Snapshots.difference(start,end,"odometer"));
        String energy="FUEL".equals(v.get("energy_type"))?"fuel":"battery";
        body.put("energyChange",Snapshots.difference(start,end,energy));
        body.put("finalSafety",Json.read((String)endRequest.get("final_safety")));
        body.put("finalSettings",Json.read((String)endRequest.get("final_settings")));
        var recordData=records.report(id);
        body.put("records",recordData);
        if (recordData.get("privateRoute") instanceof Map<?,?> route) {
            body.put("dataGaps",route.containsKey("dataGaps")?route.get("dataGaps"):route.get("gaps"));
            body.put("syncDelays",route.containsKey("syncDelays")?route.get("syncDelays"):List.of());
        }
        body.put("warningChange",Snapshots.warningDifference(start,end));
        body.put("extensions",db.queryForList("SELECT * FROM rental_extension WHERE reservation_id=? ORDER BY requested_at,id",s.get("reservation_id"))
                .stream().map(ContractController::response).toList());
        body.put("confirmedEndAt",due.toString());
        body.put("overdue",ended.isAfter(due));
        body.put("overdueSeconds",Math.max(0,Duration.between(due,ended).getSeconds()));
        body.put("returnState",s.get("return_state"));
        body.put("reportState","READY");
        body.put("dataComplete",true);
        body.put("declaredEventCount",declared.size());
        body.put("acceptedDeclaredEventCount",declared.size());
        Instant now=Instant.now();
        body.put("generatedAt",now.toString());
        body.put("asOf",asOf.toString());
        return new Snapshot(LocalDateTime.ofInstant(asOf,ZoneOffset.UTC),LocalDateTime.ofInstant(now,ZoneOffset.UTC),
                Json.write(body),declared.size(),List.of());
    }

    private Map<String,Object> publish(String id,Snapshot result) {
        // Event acceptance and cleanup use this same short session lock. Assembly remains lock-free.
        db.queryForObject("SELECT id FROM rental_session WHERE id=? FOR UPDATE",String.class,id);
        var declared=declared(id);
        var missing=missing(id,declared);
        var existing=db.queryForList("SELECT payload,snapshot_at FROM rental_report WHERE session_id=? FOR UPDATE",id);
        if (!existing.isEmpty() && !Times.utc(existing.get(0).get("snapshot_at")).isBefore(result.asOf().toInstant(ZoneOffset.UTC))) {
            var newer=Json.read((String)existing.get(0).get("payload"));
            if (missing.isEmpty() && Boolean.TRUE.equals(newer.get("dataComplete"))) return newer;
            db.update("UPDATE rental_session SET report_state='PENDING' WHERE id=? AND state='ENDED'",id);
            return waiting(id,declared.size(),missing,result.asOf().toInstant(ZoneOffset.UTC));
        }
        if (!result.missing().isEmpty() || !missing.isEmpty()) {
            // If uploads arrived after the read view, a new assembly must include them before READY.
            db.update("UPDATE rental_session SET report_state='PENDING' WHERE id=? AND state='ENDED'",id);
            var remaining=missing.isEmpty()?result.missing():missing;
            return waiting(id,declared.size(),remaining,result.asOf().toInstant(ZoneOffset.UTC));
        }
        // Every publisher owns the session lock, so a portable insert/update retains the same ordering.
        if (existing.isEmpty()) {
            db.update("INSERT INTO rental_report(session_id,snapshot_at,generated_at,payload) VALUES(?,?,?,?)",
                    id,result.asOf(),result.generated(),result.payload());
        } else {
            db.update("UPDATE rental_report SET snapshot_at=?,generated_at=?,payload=? WHERE session_id=?",
                    result.asOf(),result.generated(),result.payload(),id);
        }
        db.update("UPDATE rental_session SET report_state='READY' WHERE id=? AND report_state<>'READY'",id);
        return Json.read(db.queryForObject("SELECT payload FROM rental_report WHERE session_id=?",String.class,id));
    }

    private Map<String,Object> cached(String id) {
        var declared=declared(id);
        if (!missing(id,declared).isEmpty()) return null;
        var rows=db.queryForList("SELECT payload FROM rental_report WHERE session_id=?",id);
        if (rows.isEmpty()) return null;
        var result=new LinkedHashMap<>(Json.read((String)rows.get(0).get("payload")));
        if (!"READY".equals(result.get("reportState")) || !Boolean.TRUE.equals(result.get("dataComplete"))) return null;
        result.put("servedFromCache",true);
        result.put("refreshState","FAILED");
        return result;
    }

    Map<String,Object> retryStatus(String id) {
        var session=db.queryForMap("SELECT state,cleanup_state FROM rental_session WHERE id=?",id);
        if (!"ENDED".equals(session.get("state"))) throw new ApiError(409,"REPORT_NOT_AVAILABLE");
        if ("COMPLETE".equals(session.get("cleanup_state"))) throw new ApiError(403,"SESSION_ALREADY_CLEANED");
        var report=generate(id,false,true);
        // The vehicle can repair/check its own summary without receiving private routes or reports.
        Map<String,Object> status=new LinkedHashMap<>();
        for (String field:List.of("sessionId","reportState","dataComplete","declaredEventCount",
                "acceptedDeclaredEventCount","asOf","generatedAt","servedFromCache","refreshState")) {
            if (report.containsKey(field)) status.put(field,report.get(field));
        }
        return status;
    }

    Map<String,Object> eventStatus(String id) {
        var declared=declared(id);
        var missing=missing(id,declared);
        return Map.of("declaredEventCount",declared.size(),"acceptedDeclaredEventCount",declared.size()-missing.size(),
                "declaredEventsAccepted",missing.isEmpty(),"pendingEventIds",missing);
    }
}
