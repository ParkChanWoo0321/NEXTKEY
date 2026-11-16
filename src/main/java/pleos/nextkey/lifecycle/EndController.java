package pleos.nextkey.lifecycle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.*;
import pleos.nextkey.records.RentalRecords;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/sessions/{id}")
public class EndController {
    public record Safety(boolean parked, boolean interactionAllowed, @NotNull Instant collectedAt,
                         @Pattern(regexp = "VEHICLE_SDK|DEV_SIMULATOR") @NotNull String source) {}
    public record End(@NotNull UUID requestId, boolean confirmed, Instant confirmedAt, @Valid @NotNull Safety safety,
                      Map<String,Object> snapshot, Map<String,Object> settings,
                      @NotNull @Size(max=1000) List<@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String> pendingEventIds) {}
    public record Ack(@NotNull UUID requestId) {}
    public record Clean(@NotNull UUID requestId, boolean localRecordsSettled, boolean personalCacheCleared) {}

    private final JdbcTemplate db;
    private final RentalRecords records;
    private final boolean demo;
    private final long safetyAge;

    public EndController(JdbcTemplate db, RentalRecords records, Environment env,
                         @Value("${nextkey.end.safety-max-age-seconds:30}") long safetyAge) {
        this.db=db;
        this.records=records;
        this.safetyAge=safetyAge;
        demo=env.acceptsProfiles(Profiles.of("dev & !prod"));
    }

    @GetMapping("/end-preview")
    public Map<String,Object> preview(@PathVariable String id) {
        sessionPrincipal(id);
        var states=db.queryForList("SELECT state FROM rental_session WHERE id=?",String.class,id);
        if (states.isEmpty()) throw new ApiError(404,"SESSION_NOT_FOUND");
        if (!"ACTIVE".equals(states.get(0))) throw new ApiError(409,"END_ALREADY_SUBMITTED");
        return Map.of("sessionId",id,"state","ACTIVE","requiresFreshSafety",true,"requiresFinalConfirmation",true);
    }

    @PostMapping("/end/cancel")
    public Map<String,Object> cancel(@PathVariable String id) {
        return preview(id);
    }

    @PostMapping("/end")
    @Transactional
    public Map<String,Object> submit(@PathVariable String id, @Valid @RequestBody End input) {
        sessionPrincipal(id);
        // Final settings update the owner's profile. Use the same account-first order as authentication.
        db.queryForObject("SELECT id FROM account WHERE id=(SELECT account_id FROM rental_session WHERE id=?) FOR UPDATE",String.class,id);
        var due = db.queryForObject("""
                SELECT r.confirmed_end_at FROM reservation r WHERE r.id=(
                    SELECT reservation_id FROM rental_session WHERE id=?) FOR UPDATE
                """, LocalDateTime.class, id);
        var session=lockSession(id);
        String digest=digest(input);
        if (!"ACTIVE".equals(session.get("state"))) {
            var original=request(id, input.requestId());
            if (!digest.equals(original.get("request_digest"))) throw new ApiError(409,"END_PAYLOAD_CHANGED");
            return status(session,input.requestId());
        }
        Instant now=Instant.now();
        Instant confirmedAt=input.confirmedAt()==null ? now : input.confirmedAt();
        if (confirmedAt.isAfter(now) || confirmedAt.isBefore(Times.utc(session.get("started_at")))) {
            throw new ApiError(422,"INVALID_FINAL_TIME");
        }
        LocalDateTime fixedTime=LocalDateTime.ofInstant(confirmedAt,ZoneOffset.UTC);
        if (db.queryForObject("SELECT COUNT(*) FROM vehicle_event WHERE session_id=? AND collected_at>?",Integer.class,id,fixedTime)>0) {
            throw new ApiError(409,"FINAL_TIME_PRECEDES_RECORDS");
        }
        Safety safety=input.safety();
        if (!input.confirmed()) throw new ApiError(422,"FINAL_CONFIRMATION_REQUIRED");
        if (!safety.parked() || !safety.interactionAllowed()) throw new ApiError(409,"NOT_SAFE_TO_END");
        if (safety.collectedAt().isAfter(confirmedAt) || safety.collectedAt().isBefore(confirmedAt.minusSeconds(safetyAge))) {
            throw new ApiError(409,"STALE_FINAL_SAFETY");
        }
        if (!demo && "DEV_SIMULATOR".equals(safety.source())) throw new ApiError(403,"SIMULATOR_DISABLED");
        var energy=db.queryForObject("SELECT energy_type FROM vehicle WHERE id=?",String.class,session.get("vehicle_id"));
        Snapshots.assertSourcesAllowed(input.snapshot(),demo);
        var snapshot=Snapshots.normalize(input.snapshot(),energy,confirmedAt);
        var settings=input.settings()==null ? Map.<String,Object>of() : input.settings();
        if (new HashSet<>(input.pendingEventIds()).size()!=input.pendingEventIds().size()) throw new ApiError(422,"DUPLICATE_EVENT_ID");
        if (!input.pendingEventIds().isEmpty()) {
            int conflicts=new NamedParameterJdbcTemplate(db).queryForObject(
                    "SELECT COUNT(*) FROM vehicle_event WHERE session_id<>:session AND id IN (:ids)",
                    Map.of("session",id,"ids",input.pendingEventIds()),Integer.class);
            if (conflicts>0) throw new ApiError(409,"EVENT_ID_CONFLICT");
        }
        String requestId=input.requestId().toString();
        var laterApprovals=db.queryForList("""
                SELECT previous_end_at FROM rental_extension WHERE reservation_id=? AND status='APPROVED'
                AND decided_at>? ORDER BY decided_at,id LIMIT 1
                """,LocalDateTime.class,session.get("reservation_id"),fixedTime);
        if (!laterApprovals.isEmpty()) due=laterApprovals.get(0);
        db.update("UPDATE rental_session SET state='END_PENDING',ended_at=?,end_json=? WHERE id=?",
                fixedTime,Json.write(snapshot),id);
        var savedSettings=records.saveEndSettings(id,settings);
        db.update("""
                INSERT INTO end_request(session_id,request_id,request_digest,requested_at,confirmed_due_at,final_safety,
                                        final_settings,pending_event_ids) VALUES(?,?,?,?,?,?,?,?)
                """,id,requestId,digest,LocalDateTime.ofInstant(now,ZoneOffset.UTC),due,
                Json.write(Map.of("parked",true,"interactionAllowed",true,"source",safety.source(),
                        "collectedAt",safety.collectedAt().toString(),"verification","CLIENT_REPORTED")),
                Json.write(savedSettings),Json.write(Map.of("ids",input.pendingEventIds())));
        return status(lockSession(id),input.requestId());
    }

    @PostMapping("/end/ack")
    @Transactional
    public Map<String,Object> acknowledge(@PathVariable String id,@Valid @RequestBody Ack ack) {
        sessionPrincipal(id);
        var session=lockSession(id);
        request(id,ack.requestId());
        if ("END_PENDING".equals(session.get("state"))) {
            db.update("""
                    UPDATE rental_session SET state='ENDED',cleanup_state='PENDING',report_state='PENDING' WHERE id=?
                    """,id);
        }
        return status(lockSession(id),ack.requestId());
    }

    @GetMapping("/cleanup")
    @Transactional
    public Map<String,Object> cleanup(@PathVariable String id) {
        sessionPrincipal(id);
        var session=lockSession(id);
        if (!"ENDED".equals(session.get("state"))) throw new ApiError(409,"END_NOT_ACKNOWLEDGED");
        var row=db.queryForMap("SELECT request_id,pending_event_ids FROM end_request WHERE session_id=?",id);
        boolean settled=uploadsSettled(id,row);
        return Map.of("requestId",row.get("request_id"),"cleanupState",session.get("cleanup_state"),
                "action",settled ? "CLEAR_PERSONAL_CACHE_THEN_ACK" : "RETRY_UPLOADS",
                "declaredEventsAccepted",settled);
    }

    @PostMapping("/cleanup/ack")
    @Transactional
    public Map<String,Object> clean(@PathVariable String id,@Valid @RequestBody Clean ack) {
        sessionPrincipal(id);
        var session=lockSession(id);
        var row=request(id,ack.requestId());
        if (!"ENDED".equals(session.get("state"))) throw new ApiError(409,"END_NOT_ACKNOWLEDGED");
        if (!ack.personalCacheCleared() || !ack.localRecordsSettled() || !uploadsSettled(id,row)) {
            throw new ApiError(409,"CLEANUP_INCOMPLETE");
        }
        db.update("UPDATE rental_session SET cleanup_state='COMPLETE' WHERE id=?",id);
        return Map.of("sessionId",id,"cleanupState","COMPLETE");
    }

    private boolean uploadsSettled(String id,Map<String,Object> row) {
        List<?> ids=(List<?>) Json.read(row.get("pending_event_ids").toString()).get("ids");
        return records.accepted(id,ids.stream().map(Object::toString).toList());
    }

    private static void sessionPrincipal(String id) {
        Actor actor=Actor.current();
        actor.require("SESSION");
        if (!actor.id().equals(id)) throw new ApiError(403,"WRONG_SESSION");
    }

    private Map<String,Object> lockSession(String id) {
        var rows=db.queryForList("SELECT * FROM rental_session WHERE id=? FOR UPDATE",id);
        if (rows.isEmpty()) throw new ApiError(404,"SESSION_NOT_FOUND");
        return rows.get(0);
    }

    private Map<String,Object> request(String id,UUID expected) {
        var rows=db.queryForList("SELECT * FROM end_request WHERE session_id=?",id);
        if (rows.isEmpty()) throw new ApiError(409,"END_NOT_SUBMITTED");
        if (!expected.toString().equals(rows.get(0).get("request_id"))) throw new ApiError(409,"END_REQUEST_MISMATCH");
        return rows.get(0);
    }

    private static Map<String,Object> status(Map<String,Object> row,UUID request) {
        return Map.of("sessionId",row.get("id"),"requestId",request,"state",row.get("state"),
                "cleanupState",row.get("cleanup_state"),"reportState",row.get("report_state"),
                "returnState",row.get("return_state"),"endedAt",Times.utc(row.get("ended_at")));
    }

    private static String digest(End input) {
        Map<String,Object> fields=new TreeMap<>();
        fields.put("confirmed",input.confirmed());
        fields.put("confirmedAt",input.confirmedAt()==null?null:input.confirmedAt().toString());
        fields.put("safety",List.of(input.safety().parked(),input.safety().interactionAllowed(),
                input.safety().collectedAt().toString(),input.safety().source()));
        fields.put("snapshot",canonical(input.snapshot()));
        fields.put("settings",canonical(input.settings()));
        fields.put("pendingEventIds",input.pendingEventIds().stream().sorted().toList());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Json.write(fields).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?,?> map) {
            var out=new TreeMap<String,Object>();
            map.forEach((k,v)->out.put(k.toString(),canonical(v)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(EndController::canonical).toList();
        return value;
    }
}
