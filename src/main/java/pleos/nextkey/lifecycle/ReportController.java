package pleos.nextkey.lifecycle;

import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.*;

import java.util.*;

@RestController
@RequestMapping("/api/me")
public class ReportController {
    private final PersonalReports reports;
    private final JdbcTemplate db;
    private final ReportPdf pdf;
    public ReportController(PersonalReports reports, JdbcTemplate db, ReportPdf pdf) {
        this.reports=reports;
        this.db=db;
        this.pdf=pdf;
    }

    @GetMapping("/history")
    public List<Map<String,Object>> history(@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        Actor actor=Actor.current();
        actor.require("CUSTOMER");
        if (offset<0 || limit<1 || limit>100) throw new ApiError(400,"INVALID_PAGE");
        return db.queryForList("""
                SELECT id,reservation_id,vehicle_id,started_at,ended_at,state,report_state,return_state
                FROM rental_session WHERE account_id=? ORDER BY started_at DESC,id LIMIT ? OFFSET ?
                """,actor.id(),limit,offset).stream().map(row->{
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("sessionId",row.get("id"));
            item.put("reservationId",row.get("reservation_id"));
            item.put("vehicleId",row.get("vehicle_id"));
            item.put("startedAt",Times.utc(row.get("started_at")));
            item.put("endedAt",row.get("ended_at")==null?null:Times.utc(row.get("ended_at")));
            item.put("state",row.get("state"));
            item.put("reportState",row.get("report_state"));
            item.put("returnState",row.get("return_state"));
            return item;
        }).toList();
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<Map<String,Object>> report(@PathVariable String id) {
        Actor actor=Actor.current();
        actor.require("CUSTOMER");
        var report=reports.read(id,actor.id());
        return ResponseEntity.status("READY".equals(report.get("reportState"))?HttpStatus.OK:HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore()).body(report);
    }

    @GetMapping(value="/reports/{id}/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        var actor=Actor.current();
        actor.require("CUSTOMER");
        var report=reports.read(id,actor.id());
        if (!"READY".equals(report.get("reportState"))) throw new ApiError(409,"REPORT_NOT_READY");
        byte[] data=pdf.render(report);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"nextkey-report.pdf\"")
                .contentLength(data.length).body(data);
    }
}
