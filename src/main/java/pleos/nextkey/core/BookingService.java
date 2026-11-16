package pleos.nextkey.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.Json;
import pleos.nextkey.core.ReservationView.Place;
import static pleos.nextkey.core.CoreData.*;

@Service
class BookingService {
    private final JdbcTemplate db;
    private final ReservationSource source;
    private final BookingAvailability availability;
    BookingService(JdbcTemplate db,ReservationSource source,BookingAvailability availability){
        this.db=db;this.source=source;this.availability=availability;
    }

    static Place clean(Place place) {
        if (place==null) return new Place(null,null,null,null,"UNKNOWN");
        if ((place.name()!=null && place.name().length()>150) || (place.address()!=null && place.address().length()>500))
            throw fail(400,"PLACE_TOO_LONG");
        boolean valid=place.latitude()!=null && place.longitude()!=null && Double.isFinite(place.latitude())
                && Double.isFinite(place.longitude()) && Math.abs(place.latitude())<=90 && Math.abs(place.longitude())<=180;
        return new Place(place.name(),place.address(),valid?place.latitude():null,valid?place.longitude():null,valid?"AVAILABLE":"UNKNOWN");
    }
    private Place fromJson(Object raw){
        var m=json(raw);
        Double lat=m.get("latitude") instanceof Number n?n.doubleValue():null;
        Double lon=m.get("longitude") instanceof Number n?n.doubleValue():null;
        return clean(new Place((String)m.get("name"),(String)m.get("address"),lat,lon,null));
    }
    ReservationView view(Map<String,Object> r){
        return new ReservationView(string(r,"id"),string(r,"account_id"),string(r,"vehicle_id"),string(r,"status"),
            instant(r.get("starts_at")),instant(r.get("confirmed_end_at")),fromJson(r.get("destination_json")),fromJson(r.get("return_json")));
    }
    ReservationView lookup(String id,boolean lock){
        var r=first(db,"SELECT * FROM reservation WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(r.isEmpty()) throw fail(404,"RESERVATION_NOT_FOUND");
        return view(r);
    }
    ReservationView lockAllocation(String id) {
        var owner=lookup(id,false);
        availability.lock(owner.accountId(),owner.vehicleId());
        return lookup(id,true);
    }
    ReservationView authorize(ReservationView r,Actor a){
        a.require("CUSTOMER","OPERATOR");
        if ("CUSTOMER".equals(a.role()) && !a.id().equals(r.accountId())) throw fail(403,"RESERVATION_FORBIDDEN");
        return r;
    }
    List<ReservationView> list(Actor actor){
        actor.require("CUSTOMER");
        return db.queryForList("SELECT * FROM reservation WHERE account_id=? ORDER BY starts_at DESC",actor.id())
            .stream().map(this::view).toList();
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ReservationView create(Actor actor,ReservationView request){
        return create(actor,request,null);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ReservationView replacement(Actor actor,String oldSessionId,ReservationView request){
        actor.require("OPERATOR");
        var normalized=normalizedRequest(request);
        String legacyDigest=replacementDigest(request);
        request=normalized;
        String digest=replacementDigest(request);
        availability.lock(request.accountId(),request.vehicleId());
        var old=first(db,"SELECT * FROM rental_session WHERE id=? FOR UPDATE",oldSessionId);
        if (old.isEmpty()) throw fail(404,"SESSION_NOT_FOUND");
        if (old.get("account_id").equals(request.accountId()) && old.get("vehicle_id").equals(request.vehicleId()))
            throw fail(422,"REPLACEMENT_REQUIRES_CHANGE");
        var existing=first(db,"SELECT * FROM session_replacement WHERE old_session_id=? FOR UPDATE",oldSessionId);
        if (!existing.isEmpty()) {
            if (digest.equals(existing.get("preparation_digest")) || legacyDigest.equals(existing.get("preparation_digest")))
                return preparedResult(existing.get("preparation_result"));
            throw fail(409,"REPLACEMENT_CONFLICT");
        }
        var next=create(actor,request,string(old,"reservation_id"));
        db.update("""
            INSERT INTO session_replacement(new_reservation_id,old_session_id,status,requested_by,requested_at,
                preparation_digest,preparation_result)
            VALUES (?,?,'PENDING',?,UTC_TIMESTAMP(6),?,?)
            """,next.id(),oldSessionId,actor.id(),digest,Json.write(preparedSnapshot(next)));
        return next;
    }

    // The old session admits one replacement. Its normalized request is the retry key, while the
    // immutable response survives later reservation edits and a lost first HTTP response.
    private static String replacementDigest(ReservationView request) {
        var canonical=new LinkedHashMap<String,Object>();
        canonical.put("accountId",request.accountId());
        canonical.put("vehicleId",request.vehicleId());
        canonical.put("startsAt",request.startsAt().toString());
        canonical.put("confirmedEndAt",request.confirmedEndAt().toString());
        canonical.put("destination",clean(request.destination()));
        canonical.put("returnPlace",clean(request.returnPlace()));
        return hash(Json.write(canonical));
    }
    private static Map<String,Object> preparedSnapshot(ReservationView result) {
        return Map.of("id",result.id(),"accountId",result.accountId(),"vehicleId",result.vehicleId(),"status",result.status(),
                "startsAt",result.startsAt().toString(),"confirmedEndAt",result.confirmedEndAt().toString(),
                "destination",result.destination(),"returnPlace",result.returnPlace());
    }
    private ReservationView preparedResult(Object stored) {
        var result=json(stored);
        return new ReservationView((String)result.get("id"),(String)result.get("accountId"),(String)result.get("vehicleId"),
                (String)result.get("status"),Instant.parse((String)result.get("startsAt")),
                Instant.parse((String)result.get("confirmedEndAt")),fromJson(Json.write(result.get("destination"))),
                fromJson(Json.write(result.get("returnPlace"))));
    }

    private ReservationView create(Actor actor,ReservationView request,String replacing){
        actor.require("OPERATOR","CUSTOMER");
        request=normalizedRequest(request);
        if("CUSTOMER".equals(actor.role()) && !actor.id().equals(request.accountId())) throw fail(403,"RESERVATION_FORBIDDEN");
        availability.lock(request.accountId(),request.vehicleId());
        availability.requireAvailable(request.accountId(),request.vehicleId(),request.startsAt(),request.confirmedEndAt(),null,replacing);
        var requested=new ReservationView(id(),request.accountId(),request.vehicleId(),"CONFIRMED",request.startsAt(),
                request.confirmedEndAt(),clean(request.destination()),clean(request.returnPlace()));
        var accepted=checked(requested,source.change(requested));
        if(!requested.startsAt().equals(accepted.startsAt()) || !requested.confirmedEndAt().equals(accepted.confirmedEndAt()))
            availability.requireAvailable(accepted.accountId(),accepted.vehicleId(),accepted.startsAt(),accepted.confirmedEndAt(),null,replacing);
        db.update("""
            INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at,destination_json,return_json)
            VALUES (?,?,?,?,?,?,?,?)
            """,accepted.id(),accepted.accountId(),accepted.vehicleId(),accepted.status(),sql(accepted.startsAt()),
            sql(accepted.confirmedEndAt()),Json.write(accepted.destination()),Json.write(accepted.returnPlace()));
        return accepted;
    }

    private static ReservationView normalizedRequest(ReservationView request) {
        if (request==null || !validId(request.accountId()) || !validId(request.vehicleId()) ||
                request.startsAt()==null || request.confirmedEndAt()==null)
            throw fail(400,"INVALID_RESERVATION");
        Instant startsAt=request.startsAt().truncatedTo(ChronoUnit.MICROS);
        Instant confirmedEndAt=request.confirmedEndAt().truncatedTo(ChronoUnit.MICROS);
        if (!confirmedEndAt.isAfter(startsAt)) throw fail(400,"INVALID_RESERVATION");
        return new ReservationView(request.id(),request.accountId(),request.vehicleId(),request.status(),
                startsAt,confirmedEndAt,request.destination(),request.returnPlace());
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ReservationView update(String id,Actor actor,String field,Place place){
        var r=authorize(lockAllocation(id),actor);
        if (!"destination".equals(field)) actor.require("OPERATOR");
        var session=first(db,"SELECT state FROM rental_session WHERE reservation_id=? FOR UPDATE",id);
        if (!"CONFIRMED".equals(r.status()) || (!session.isEmpty() &&
            (!"ACTIVE".equals(session.get("state")) || "cancel".equals(field)))) throw fail(409,"RESERVATION_CHANGE_CLOSED");
        var requested=new ReservationView(id,r.accountId(),r.vehicleId(),"cancel".equals(field)?"CANCELLED":r.status(),
            r.startsAt(),r.confirmedEndAt(),"destination".equals(field)?clean(place):r.destination(),
            "return".equals(field)?clean(place):r.returnPlace());
        var accepted=checked(r,source.change(requested));
        persist(r,accepted);
        return accepted;
    }
    ReservationView refresh(ReservationView current){
        var authoritative=checked(current,source.verify(current));
        if (!authoritative.equals(current)) persist(current,authoritative);
        return authoritative;
    }
    private ReservationView checked(ReservationView old,ReservationView v){
        if (v==null || !old.id().equals(v.id()) || !old.accountId().equals(v.accountId()) || !old.vehicleId().equals(v.vehicleId())
            || !List.of("CONFIRMED","CANCELLED").contains(v.status()) || v.startsAt()==null || v.confirmedEndAt()==null)
            throw fail(503,"INVALID_PROVIDER_RESERVATION");
        Instant startsAt=v.startsAt().truncatedTo(ChronoUnit.MICROS);
        Instant confirmedEndAt=v.confirmedEndAt().truncatedTo(ChronoUnit.MICROS);
        if (!confirmedEndAt.isAfter(startsAt)) throw fail(503,"INVALID_PROVIDER_RESERVATION");
        return new ReservationView(v.id(),v.accountId(),v.vehicleId(),v.status(),startsAt,confirmedEndAt,
                clean(v.destination()),clean(v.returnPlace()));
    }
    private void persist(ReservationView old,ReservationView r){
        if ("CONFIRMED".equals(r.status()) && (!old.startsAt().equals(r.startsAt()) ||
                !old.confirmedEndAt().equals(r.confirmedEndAt()) || !old.status().equals(r.status()))) {
            var replacement=first(db,"""
                SELECT s.reservation_id FROM session_replacement p
                JOIN rental_session s ON s.id=p.old_session_id WHERE p.new_reservation_id=?
                """,r.id());
            availability.requireAvailable(r.accountId(),r.vehicleId(),r.startsAt(),r.confirmedEndAt(),r.id(),
                    string(replacement,"reservation_id"));
        }
        db.update("UPDATE reservation SET status=?,starts_at=?,confirmed_end_at=?,destination_json=?,return_json=? WHERE id=?",
                r.status(),sql(r.startsAt()),sql(r.confirmedEndAt()),Json.write(r.destination()),Json.write(r.returnPlace()),r.id());
    }
    static boolean validId(String id){return id!=null && !id.isBlank() && id.length()<=64;}
}
