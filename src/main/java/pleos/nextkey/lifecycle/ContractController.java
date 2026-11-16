package pleos.nextkey.lifecycle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.core.BookingAvailability;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ContractController {
    public record Extension(@NotNull UUID requestId,@NotNull Instant requestedEndAt) {}
    public record Quote(@NotNull @DecimalMin("0") @Digits(integer=13,fraction=2) BigDecimal amount,
                        @NotNull @Pattern(regexp="[A-Z]{3}") String currency) {}
    public record Decision(@NotNull Boolean approved,@Size(max=256) String note) {}
    public record Replacement(@NotBlank @Size(max=64) String newReservationId) {}
    private final JdbcTemplate db;
    private final Optional<RentalOffice> adapter;
    private final BookingAvailability availability;

    public ContractController(JdbcTemplate db, Optional<RentalOffice> adapter, BookingAvailability availability) {
        this.db=db;
        this.adapter=adapter;
        this.availability=availability;
    }
    private RentalOffice office() {
        return adapter.orElseThrow(()->new ApiError(503,"RENTAL_OFFICE_UNAVAILABLE"));
    }

    @PostMapping("/reservations/{rid}/extensions")
    @Transactional
    public Map<String,Object> create(@PathVariable String rid,@Valid @RequestBody Extension input) {
        Actor me=Actor.current();
        me.require("CUSTOMER");
        var reservation=reservation(rid);
        owner(me,reservation);
        String eid=input.requestId().toString();
        Instant until=input.requestedEndAt().truncatedTo(ChronoUnit.MICROS);
        var existing=db.queryForList("SELECT * FROM rental_extension WHERE id=?",eid);
        if (!existing.isEmpty()) {
            var original=existing.get(0);
            if (!rid.equals(original.get("reservation_id")) || !until.equals(Times.utc(original.get("requested_end_at")))) {
                throw new ApiError(409,"EXTENSION_ID_CONFLICT");
            }
            return response(original);
        }
        available(rid,reservation);
        Instant prior=Times.utc(reservation.get("confirmed_end_at"));
        if (!until.isAfter(prior) || !until.isAfter(Instant.now())) throw new ApiError(422,"INVALID_EXTENSION_END");
        if (count("SELECT COUNT(*) FROM rental_extension WHERE active_reservation=?",rid)>0) {
            throw new ApiError(409,"EXTENSION_ALREADY_PENDING");
        }
        db.update("""
                INSERT INTO rental_extension(id,reservation_id,account_id,requested_at,previous_end_at,requested_end_at,status)
                VALUES(?,?,?,UTC_TIMESTAMP(6),?,?,'REQUESTED')
                """,eid,rid,me.id(),reservation.get("confirmed_end_at"),LocalDateTime.ofInstant(until,ZoneOffset.UTC));
        return response(row(eid));
    }

    @GetMapping("/reservations/{rid}/extensions")
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(@PathVariable String rid) {
        Actor me=Actor.current();
        me.require("CUSTOMER","OPERATOR");
        var reservation=single("SELECT * FROM reservation WHERE id=?",rid,"RESERVATION_NOT_FOUND");
        if (me.role().equals("CUSTOMER")) owner(me,reservation);
        return db.queryForList("SELECT * FROM rental_extension WHERE reservation_id=? ORDER BY requested_at,id",rid)
                .stream().map(ContractController::response).toList();
    }

    /** Vehicle projection deliberately excludes quotes, consent history, notes and the next renter's identity. */
    @GetMapping("/sessions/{sid}/contract-status")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> sessionContract(@PathVariable String sid) {
        Actor me=Actor.current();
        me.require("SESSION","CUSTOMER");
        var old=single("SELECT * FROM rental_session WHERE id=?",sid,"SESSION_NOT_FOUND");
        if (("SESSION".equals(me.role()) && !sid.equals(me.id()))
                || ("CUSTOMER".equals(me.role()) && !me.id().equals(old.get("account_id")))) throw new ApiError(403,"FORBIDDEN");
        if ("SESSION".equals(me.role()) && "COMPLETE".equals(old.get("cleanup_state")))
            throw new ApiError(403,"SESSION_ALREADY_CLEANED");
        var current=single("SELECT confirmed_end_at FROM reservation WHERE id=?",(String)old.get("reservation_id"),"RESERVATION_NOT_FOUND");
        var extension=new LinkedHashMap<String,Object>();
        extension.put("status","NONE");
        extension.put("effectiveEndAt",Times.utc(current.get("confirmed_end_at")).toString());
        var changes=db.queryForList("SELECT status,requested_end_at,decided_at FROM rental_extension WHERE reservation_id=? ORDER BY requested_at DESC,id DESC LIMIT 1",
                old.get("reservation_id"));
        if (!changes.isEmpty()) {
            var change=changes.get(0);
            extension.put("status",change.get("status"));
            extension.put("requestedEndAt",Times.utc(change.get("requested_end_at")).toString());
            extension.put("decisionAt",change.get("decided_at")==null?null:Times.utc(change.get("decided_at")).toString());
        }
        return Map.of("sessionId",sid,"extension",extension,"handoff",handoff(old,replacementRow(sid)));
    }

    @PostMapping("/extensions/{eid}/quote")
    @Transactional
    public Map<String,Object> quote(@PathVariable UUID eid,@Valid @RequestBody Quote quote) {
        Actor me=Actor.current();
        me.require("OPERATOR");
        String id=eid.toString();
        var change=lockChange(id);
        var ext=change.extension();
        if (!"REQUESTED".equals(ext.get("status"))) {
            if (sameQuote(ext,quote)) return response(ext);
            throw new ApiError(409,"QUOTE_ALREADY_FIXED");
        }
        try { Currency.getInstance(quote.currency()); } catch(IllegalArgumentException ex) { throw new ApiError(422,"INVALID_CURRENCY"); }
        available(change.reservationId(),change.reservation());
        office().offer(id,quote.amount(),quote.currency());
        db.update("""
                UPDATE rental_extension SET status='WAITING_CONSENT', quote_amount=?,currency=?,quoted_by=?,quoted_at=UTC_TIMESTAMP(6) WHERE id=?
                """,quote.amount(),quote.currency(),me.id(),id);
        return response(row(id));
    }

    @PostMapping("/extensions/{eid}/agree")
    @Transactional
    public Map<String,Object> agree(@PathVariable UUID eid,@Valid @RequestBody Quote quote) {
        Actor me=Actor.current();
        me.require("CUSTOMER");
        String id=eid.toString();
        var change=lockChange(id);
        owner(me,change.reservation());
        var ext=change.extension();
        if (!sameQuote(ext,quote)) throw new ApiError(409,"QUOTED_FEE_MISMATCH");
        if (ext.get("agreed_at")!=null) return response(ext);
        if (!"WAITING_CONSENT".equals(ext.get("status"))) throw new ApiError(409,"CONSENT_NOT_AVAILABLE");
        available(change.reservationId(),change.reservation());
        db.update("UPDATE rental_extension SET status='PENDING_APPROVAL',agreed_at=UTC_TIMESTAMP(6) WHERE id=?",id);
        return response(row(id));
    }

    @PostMapping("/extensions/{eid}/decision")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> decide(@PathVariable UUID eid,@Valid @RequestBody Decision decision) {
        Actor me=Actor.current();
        me.require("OPERATOR");
        String id=eid.toString();
        if (decision.approved()) {
            var identities=single("""
                    SELECT r.account_id,r.vehicle_id FROM reservation r JOIN rental_extension e ON e.reservation_id=r.id
                    WHERE e.id=?
                    """,id,"EXTENSION_NOT_FOUND");
            availability.lock((String)identities.get("account_id"),(String)identities.get("vehicle_id"));
        }
        var change=lockChange(id);
        var ext=change.extension();
        String result=decision.approved()?"APPROVED":"REJECTED";
        if (result.equals(ext.get("status")) && Objects.equals(decision.note(),ext.get("decision_note"))) return response(ext);
        if (ext.get("decided_at")!=null) throw new ApiError(409,"DECISION_ALREADY_FIXED");
        Instant until=Times.utc(ext.get("requested_end_at"));
        if (decision.approved()) {
            if (!"PENDING_APPROVAL".equals(ext.get("status"))) throw new ApiError(409,"FEE_CONSENT_REQUIRED");
            available(change.reservationId(),change.reservation());
            if (!Times.utc(ext.get("previous_end_at")).equals(Times.utc(change.reservation().get("confirmed_end_at")))
                    || !until.isAfter(Instant.now())) throw new ApiError(409,"STALE_EXTENSION");
            var replaced=db.queryForList("""
                    SELECT s.reservation_id FROM session_replacement sr
                    JOIN rental_session s ON s.id=sr.old_session_id WHERE sr.new_reservation_id=?
                    """,String.class,change.reservationId());
            availability.requireAvailable((String)change.reservation().get("account_id"),(String)change.reservation().get("vehicle_id"),
                    Times.utc(change.reservation().get("starts_at")),until,change.reservationId(),
                    replaced.isEmpty()?null:replaced.get(0));
        }
        office().decide(id,change.reservationId(),until,decision.approved());
        if (decision.approved()) db.update("UPDATE reservation SET confirmed_end_at=? WHERE id=?",ext.get("requested_end_at"),change.reservationId());
        db.update("UPDATE rental_extension SET status=?,decided_by=?,decided_at=UTC_TIMESTAMP(6),decision_note=? WHERE id=?",
                result,me.id(),decision.note(),id);
        return response(row(id));
    }

    @PostMapping("/operator/sessions/{sid}/replacement")
    @Transactional
    public Map<String,Object> replacement(@PathVariable String sid,@Valid @RequestBody Replacement input) {
        Actor me=Actor.current();
        me.require("OPERATOR");
        office();
        var next=reservation(input.newReservationId());
        var old=single("SELECT * FROM rental_session WHERE id=? FOR UPDATE",sid,"SESSION_NOT_FOUND");
        if (Objects.equals(old.get("vehicle_id"),next.get("vehicle_id")) && Objects.equals(old.get("account_id"),next.get("account_id"))) {
            throw new ApiError(422,"REPLACEMENT_REQUIRES_CHANGE");
        }
        var rows=db.queryForList("SELECT * FROM session_replacement WHERE old_session_id=? OR new_reservation_id=?",sid,input.newReservationId());
        if (!rows.isEmpty()) {
            var row=rows.get(0);
            if (sid.equals(row.get("old_session_id")) && input.newReservationId().equals(row.get("new_reservation_id"))) {
                return Map.of("status",row.get("status"),"newReservationId",input.newReservationId());
            }
            throw new ApiError(409,"REPLACEMENT_CONFLICT");
        }
        unused(next);
        db.update("""
                INSERT INTO session_replacement(new_reservation_id,old_session_id,status,requested_by,requested_at)
                VALUES(?,?,'PENDING',?,UTC_TIMESTAMP(6))
                """,input.newReservationId(),sid,me.id());
        return Map.of("status","PENDING","newReservationId",input.newReservationId());
    }

    @GetMapping("/operator/sessions/{sid}/replacement")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> replacementStatus(@PathVariable String sid) {
        Actor.current().require("OPERATOR");
        var old=single("SELECT * FROM rental_session WHERE id=?",sid,"SESSION_NOT_FOUND");
        var replacement=replacementRow(sid);
        var out=new LinkedHashMap<String,Object>(handoff(old,replacement));
        if (!replacement.isEmpty()) out.put("newReservationId",replacement.get("new_reservation_id"));
        return out;
    }

    @PostMapping("/operator/sessions/{sid}/replacement/preapprove")
    @Transactional
    public Map<String,Object> preapproveReplacement(@PathVariable String sid,@Valid @RequestBody Replacement input) {
        Actor me=Actor.current();
        me.require("OPERATOR");
        var next=reservation(input.newReservationId());
        var old=single("SELECT * FROM rental_session WHERE id=? FOR UPDATE",sid,"SESSION_NOT_FOUND");
        var row=single("SELECT * FROM session_replacement WHERE new_reservation_id=? FOR UPDATE",input.newReservationId(),"REPLACEMENT_NOT_FOUND");
        if (!sid.equals(row.get("old_session_id"))) throw new ApiError(409,"REPLACEMENT_CONFLICT");
        if (row.get("preapproved_at")==null && !"APPROVED".equals(row.get("status"))) {
            if (!"ACTIVE".equals(old.get("state"))) throw new ApiError(409,"OLD_SESSION_NOT_ACTIVE");
            unused(next);
            office();
            db.update("UPDATE session_replacement SET preapproved_by=?,preapproved_at=UTC_TIMESTAMP(6) WHERE new_reservation_id=?",
                    me.id(),input.newReservationId());
        }
        return replacementStatus(sid);
    }

    @PostMapping("/operator/sessions/{sid}/replacement/approve")
    @Transactional
    public Map<String,Object> approveReplacement(@PathVariable String sid,@Valid @RequestBody Replacement input) {
        Actor me=Actor.current();
        me.require("OPERATOR");
        var next=reservation(input.newReservationId());
        var old=single("SELECT * FROM rental_session WHERE id=? FOR UPDATE",sid,"SESSION_NOT_FOUND");
        var row=single("SELECT * FROM session_replacement WHERE new_reservation_id=? FOR UPDATE",input.newReservationId(),"REPLACEMENT_NOT_FOUND");
        if (!sid.equals(row.get("old_session_id"))) throw new ApiError(409,"REPLACEMENT_CONFLICT");
        if (!"APPROVED".equals(row.get("status"))) {
            if (!"ENDED".equals(old.get("state")) || !"COMPLETE".equals(old.get("cleanup_state"))) throw new ApiError(409,"OLD_SESSION_NOT_CLEARED");
            unused(next);
            office().replace(sid,input.newReservationId());
            db.update("""
                    UPDATE session_replacement SET status='APPROVED',approved_by=?,approved_at=UTC_TIMESTAMP(6),
                        preapproved_by=COALESCE(preapproved_by,?),preapproved_at=COALESCE(preapproved_at,UTC_TIMESTAMP(6))
                    WHERE new_reservation_id=?
                    """,me.id(),me.id(),input.newReservationId());
        }
        return Map.of("status","APPROVED","newReservationId",input.newReservationId(),"nextAction","NEW_AUTHENTICATION");
    }

    @PostMapping("/operator/sessions/{sid}/return")
    @Transactional
    public Map<String,Object> confirmReturn(@PathVariable String sid) {
        Actor.current().require("OPERATOR");
        var old=single("SELECT state,return_state FROM rental_session WHERE id=? FOR UPDATE",sid,"SESSION_NOT_FOUND");
        if (!"ENDED".equals(old.get("state"))) throw new ApiError(409,"END_NOT_ACKNOWLEDGED");
        if (!"CONFIRMED".equals(old.get("return_state"))) {
            office().confirmReturn(sid);
            db.update("UPDATE rental_session SET return_state='CONFIRMED' WHERE id=?",sid);
        }
        return Map.of("sessionId",sid,"returnState","CONFIRMED");
    }

    private record Change(String reservationId,Map<String,Object> reservation,Map<String,Object> extension) {}

    private Map<String,Object> replacementRow(String sid) {
        var rows=db.queryForList("""
                SELECT p.*,r.status AS next_status,r.account_id AS next_account_id,r.vehicle_id AS next_vehicle_id,
                    r.starts_at AS next_starts_at,r.confirmed_end_at AS next_end_at,
                    EXISTS(SELECT 1 FROM rental_session used WHERE used.reservation_id=r.id) AS next_used
                FROM session_replacement p JOIN reservation r ON r.id=p.new_reservation_id WHERE p.old_session_id=?
                """,sid);
        return rows.isEmpty()?Map.of():rows.get(0);
    }
    private static Map<String,Object> handoff(Map<String,Object> old,Map<String,Object> next) {
        if (next.isEmpty()) return Map.of("status","NONE","priorApproval",false,"mayCloseCurrentSession",false,"newAuthenticationReady",false);
        boolean prior=next.get("preapproved_at")!=null || "APPROVED".equals(next.get("status"));
        boolean cleared="ENDED".equals(old.get("state")) && "COMPLETE".equals(old.get("cleanup_state"));
        boolean used=Boolean.TRUE.equals(next.get("next_used")) || (next.get("next_used") instanceof Number n && n.intValue()!=0);
        Instant now=Instant.now();
        boolean available="CONFIRMED".equals(next.get("next_status")) && Times.utc(next.get("next_end_at")).isAfter(now);
        boolean started=!Times.utc(next.get("next_starts_at")).isAfter(now);
        boolean ready="APPROVED".equals(next.get("status")) && cleared && !used && available && started;
        String status=used?"IN_USE":!available?"UNAVAILABLE":ready?"READY_FOR_NEW_AUTH":
                "APPROVED".equals(next.get("status"))?(cleared?"WAITING_FOR_START":"WAITING_FOR_CLEANUP"):prior?"PREAPPROVED":"PENDING";
        return Map.of("status",status,"priorApproval",prior,
                "mayCloseCurrentSession",prior && available && !used && "ACTIVE".equals(old.get("state")),
                "newAuthenticationReady",ready,"vehicleChanged",!Objects.equals(old.get("vehicle_id"),next.get("next_vehicle_id")),
                "userChanged",!Objects.equals(old.get("account_id"),next.get("next_account_id")));
    }

    private Change lockChange(String id) {
        var found=single("SELECT reservation_id FROM rental_extension WHERE id=?",id,"EXTENSION_NOT_FOUND");
        String rid=(String)found.get("reservation_id");
        return new Change(rid,reservation(rid),single("SELECT * FROM rental_extension WHERE id=? FOR UPDATE",id,"EXTENSION_NOT_FOUND"));
    }
    private void available(String rid,Map<String,Object> reservation) {
        // Locking read observes an end which won the reservation lock before this request.
        var states=db.queryForList("SELECT state FROM rental_session WHERE reservation_id=? FOR UPDATE",String.class,rid);
        if (!"CONFIRMED".equals(reservation.get("status")) || states.stream().anyMatch(s->!"ACTIVE".equals(s))) {
            throw new ApiError(409,"RESERVATION_NOT_EXTENDABLE");
        }
    }
    private void unused(Map<String,Object> next) {
        if (!"CONFIRMED".equals(next.get("status"))
                || !Times.utc(next.get("confirmed_end_at")).isAfter(Instant.now())
                || count("SELECT COUNT(*) FROM rental_session WHERE reservation_id=?",next.get("id"))>0) {
            throw new ApiError(409,"NEW_RESERVATION_UNAVAILABLE");
        }
    }
    private int count(String sql,Object... args) { return db.queryForObject(sql,Integer.class,args); }
    private Map<String,Object> reservation(String id) { return single("SELECT * FROM reservation WHERE id=? FOR UPDATE",id,"RESERVATION_NOT_FOUND"); }
    private Map<String,Object> row(String id) { return single("SELECT * FROM rental_extension WHERE id=? FOR UPDATE",id,"EXTENSION_NOT_FOUND"); }
    private Map<String,Object> single(String sql,String id,String code) {
        var rows=db.queryForList(sql,id);
        if (rows.isEmpty()) throw new ApiError(404,code);
        return rows.get(0);
    }
    private static void owner(Actor me, Map<String,Object> reservation) {
        if (!me.id().equals(reservation.get("account_id"))) throw new ApiError(403,"NOT_RESERVATION_OWNER");
    }
    private static boolean sameQuote(Map<String,Object> ext,Quote quote) {
        return ext.get("quote_amount") instanceof BigDecimal value && value.compareTo(quote.amount())==0
                && quote.currency().equals(ext.get("currency"));
    }
    static Map<String,Object> response(Map<String,Object> row) {
        var out=new LinkedHashMap<String,Object>();
        out.put("id",row.get("id"));
        out.put("reservationId",row.get("reservation_id"));
        out.put("status",row.get("status"));
        out.put("amount",row.get("quote_amount"));
        out.put("currency",row.get("currency"));
        for (String name:List.of("requested_at","previous_end_at","requested_end_at","quoted_at","agreed_at","decided_at")) {
            String[] parts=name.split("_");
            String key=parts[0];
            for(int i=1;i<parts.length;i++) key+=Character.toUpperCase(parts[i].charAt(0))+parts[i].substring(1);
            out.put(key,row.get(name)==null?null:Times.utc(row.get(name)).toString());
        }
        out.put("feeConsent",row.get("agreed_at")==null?"NOT_GIVEN":"AGREED");
        out.put("operatorDecision",row.get("decided_at")==null?"PENDING":row.get("status"));
        out.put("decisionNote",row.get("decision_note"));
        out.put("payment","NOT_INTEGRATED");
        return out;
    }
}
