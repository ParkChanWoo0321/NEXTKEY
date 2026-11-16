package pleos.nextkey.lifecycle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;
import pleos.nextkey.records.RentalRecords;

import java.util.LinkedHashMap;
import java.util.Map;

/** Only the authenticated current app session may read this limited vehicle summary. */
@RestController
@RequestMapping("/api/sessions/{id}")
public class SessionStateController {
    private final JdbcTemplate db;
    private final PersonalReports reports;
    private final RentalRecords records;

    SessionStateController(JdbcTemplate db,PersonalReports reports,RentalRecords records) {
        this.db=db;
        this.reports=reports;
        this.records=records;
    }

    private Map<String,Object> session(String id,boolean lock) {
        var actor=Actor.current();
        actor.require("SESSION");
        if (!actor.id().equals(id)) throw new ApiError(403,"WRONG_SESSION");
        var rows=db.queryForList("SELECT * FROM rental_session WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if (rows.isEmpty()) throw new ApiError(404,"SESSION_NOT_FOUND");
        var session=rows.get(0);
        if ("COMPLETE".equals(session.get("cleanup_state"))) throw new ApiError(403,"SESSION_ALREADY_CLEANED");
        return session;
    }

    @GetMapping("/state")
    @Transactional
    public Map<String,Object> state(@PathVariable String id) {
        var session=session(id,true);
        var start=Json.read((String)session.get("start_json"));
        var end=session.get("end_json")==null?null:Json.read((String)session.get("end_json"));
        var energy=db.queryForObject("SELECT energy_type FROM vehicle WHERE id=?",String.class,session.get("vehicle_id"));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("sessionId",id);
        result.put("reservationId",session.get("reservation_id"));
        result.put("vehicleId",session.get("vehicle_id"));
        result.put("state",session.get("state"));
        result.put("startedAt",Times.utc(session.get("started_at")));
        result.put("endedAt",session.get("ended_at")==null?null:Times.utc(session.get("ended_at")));
        result.put("startSnapshot",start);
        result.put("endSnapshot",end);
        result.put("distanceKm",end==null?Map.of("status","NOT_ENDED"):Snapshots.difference(start,end,"odometer"));
        result.put("energyChange",end==null?Map.of("status","NOT_ENDED"):
                Snapshots.difference(start,end,"FUEL".equals(energy)?"fuel":"battery"));
        result.put("warningChange",end==null?Map.of("status","NOT_ENDED"):Snapshots.warningDifference(start,end));
        result.put("cleanupState",session.get("cleanup_state"));
        result.put("reportState",session.get("report_state"));
        result.put("returnState",session.get("return_state"));
        var requests=db.queryForList("SELECT request_id,final_settings,final_safety FROM end_request WHERE session_id=?",id);
        if (!requests.isEmpty()) {
            var request=requests.get(0);
            result.put("requestId",request.get("request_id"));
            result.put("finalSettings",Json.read((String)request.get("final_settings")));
            result.put("finalSafety",Json.read((String)request.get("final_safety")));
            result.put("eventStatus",reports.eventStatus(id));
            result.put("details",records.vehicleSummary(id));
        }
        return result;
    }

    @PostMapping("/report/retry")
    public Map<String,Object> retry(@PathVariable String id) {
        session(id,false);
        return reports.retryStatus(id);
    }
}
