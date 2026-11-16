package pleos.nextkey.core;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.Access;
import pleos.nextkey.common.Actor;
import pleos.nextkey.records.RentalRecords;
import static pleos.nextkey.core.CoreData.*;

@RestController
@RequestMapping("/api")
class CoreController {
    private final BookingService bookings;
    private final RentalRecords records;
    private final Access access;
    private final JdbcTemplate db;
    private final long energyFreshSeconds;
    private final java.util.List<Integer> reminderMinutes;

    CoreController(BookingService bookings,Access access,
                   JdbcTemplate db,RentalRecords records,@Value("${nextkey.records.energy-max-age-seconds:300}") long energyFreshSeconds,
                   @Value("${nextkey.return.reminder-minutes:30,10}") java.util.List<Integer> reminderMinutes){
        this.bookings=bookings;
        this.access=access;this.db=db;this.records=records;this.energyFreshSeconds=energyFreshSeconds;
        if (energyFreshSeconds<1 || reminderMinutes.stream().anyMatch(n->n<1 || n>1440))
            throw new IllegalArgumentException("Invalid return guide time settings");
        this.reminderMinutes=reminderMinutes.stream().distinct().sorted(java.util.Comparator.reverseOrder()).toList();
    }
    @GetMapping("/sessions/{id}/return-guide")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    Object guide(@PathVariable String id){
        Actor actor=Actor.current();actor.require("SESSION","CUSTOMER");
        var s=access.session(id,actor);
        var r=bookings.lookup(string(s,"reservation_id"),false);
        Instant asOf=Instant.now();
        Instant reference=s.get("ended_at")==null?asOf:instant(s.get("ended_at"));
        Instant deadline=r.confirmedEndAt();
        if(s.get("ended_at")!=null) {
            var finalRequests=db.queryForList("SELECT confirmed_due_at FROM end_request WHERE session_id=?",id);
            if(!finalRequests.isEmpty())deadline=instant(finalRequests.get(0).get("confirmed_due_at"));
        }
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("sessionId", s.get("id"));
        out.put("reservationId", s.get("reservation_id"));
        out.put("vehicleId", s.get("vehicle_id"));
        out.put("startedAt", instant(s.get("started_at")));
        out.put("state", s.get("state"));
        out.put("confirmedEndAt",deadline);out.put("asOf",asOf);
        out.put("remainingSeconds",Math.max(0,Duration.between(reference,deadline).toSeconds()));
        out.put("overdue",reference.isAfter(deadline));
        out.put("suggestedReminderMinutes",reminderMinutes);
        out.put("returnPlace",r.returnPlace());out.put("destination",r.destination());
        var reading=records.latestEnergy(id);
        if(reading.isEmpty()){
            String type=db.queryForObject("SELECT energy_type FROM vehicle WHERE id=?",String.class,s.get("vehicle_id"));
            Object field=json(s.get("start_json")).get("BATTERY".equals(type)?"battery":"fuel");
            if (field instanceof Map<?,?> m) {
                var measured=new LinkedHashMap<String,Object>();
                m.forEach((k,v)->measured.put(k.toString(),v));
                reading=measured;
            }
        }
        if ("VALID".equals(reading.get("status")) && reading.get("collectedAt")!=null
                && Instant.parse(reading.get("collectedAt").toString()).isBefore(asOf.minusSeconds(energyFreshSeconds))){
            reading=new LinkedHashMap<>(reading);reading.remove("value");reading.put("status","STALE");
        }
        out.put("energy",reading.isEmpty()?Map.of("status","UNKNOWN","unit","%"):reading);
        out.put("sessionState",s.get("state"));out.put("returnState",s.get("return_state"));
        return out;
    }
}
