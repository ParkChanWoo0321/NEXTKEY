package pleos.nextkey.records;

import static pleos.nextkey.records.RecordData.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import pleos.nextkey.common.ApiError;

@Service
class DamageRecords {
    record Submission(String category, String description, Instant discoveredAt, Boolean parked, Instant safetyObservedAt,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String partCode) {}
    record Verification(String result) {}
    record Photo(String mime, byte[] bytes) {}
    record Handover(Instant asOf, String availability, String impactAvailability, List<HandoverRecord> records) {}
    record HandoverRecord(String recordId, String partCode, String category, String verification,
                          Instant recordedAt, Instant occurredAt, String source,
                          String publicDescription, Instant discoveredAt, Instant reportedAt) {}
    private static final Set<String> CATEGORIES = Set.of("SCRATCH","DENT","GLASS","TIRE","CABIN","ACCIDENT","BREAKDOWN","OTHER");
    private static final Set<String> PART_CODES = Set.of("FRONT","REAR","LEFT_FRONT","RIGHT_FRONT",
        "LEFT_REAR","RIGHT_REAR","HOOD","ROOF","UNKNOWN");
    private final RecordData data;
    DamageRecords(RecordData data) { this.data = data; }

    private void parked(Map<String,Object> session, Boolean parked, Instant observed) {
        if ("ENDED".equals(session.get("state"))) return;
        require(Boolean.TRUE.equals(parked),409,"PARKED_CONFIRMATION_REQUIRED");
        safeAt(observed,data.env.getProperty("nextkey.damage.parked-age-seconds",Long.class,30L));
    }

    @Transactional
    public Map<String,Object> create(String id,Submission input) {
        var s = data.session(id,true);
        parked(s,input.parked(),input.safetyObservedAt());
        require(input.category()!=null && CATEGORIES.contains(input.category()) && input.description()!=null &&
            !input.description().isBlank() && input.description().length()<=1200,400,"INVALID_DAMAGE_REPORT");
        String partCode = input.partCode() == null ? "UNKNOWN" : input.partCode();
        require(PART_CODES.contains(partCode),400,"INVALID_DAMAGE_PART");
        Instant now = Instant.now();
        require(input.discoveredAt()!=null && !input.discoveredAt().isAfter(now) &&
            !input.discoveredAt().isBefore(instant(s.get("started_at"))),400,"INVALID_DISCOVERY_TIME");
        var report = uuid();
        data.db.update("INSERT INTO damage_report(id,session_id,vehicle_id,category,description,discovered_at,reported_at,part_code) VALUES(?,?,?,?,?,?,?,?)",
            report,id,s.get("vehicle_id"),input.category(),input.description().strip(),stamp(input.discoveredAt()),stamp(now),partCode);
        return Map.of("id",report,"verification","UNVERIFIED","reportedAt",now,"support",support());
    }

    Map<String,Object> support() {
        String phone = data.env.getProperty("nextkey.support.phone","");
        return phone.isBlank() ? Map.of("status","UNCONFIGURED") : Map.of("status","CONFIGURED","phone",phone);
    }

    @Transactional
    public void verify(String report,String actor,Verification v) {
        require(v.result()!=null && Set.of("CONFIRMED","DISMISSED").contains(v.result()),400,"INVALID_VERIFICATION_RESULT");
        var rows = data.db.queryForList("SELECT verification,verified_at,reported_at FROM damage_report WHERE id=? FOR UPDATE",report);
        require(!rows.isEmpty(),404,"REPORT_NOT_FOUND");
        var previous = rows.get(0);
        Object since = previous.get("verified_at");
        if (since == null && "UNVERIFIED".equals(previous.get("verification"))) since = previous.get("reported_at");
        if (since != null) {
            data.db.update("INSERT INTO damage_verification_history(report_id,verification,effective_at) VALUES(?,?,?)",
                report,previous.get("verification"),since);
        }
        data.db.update("UPDATE damage_report SET verification=?,verified_at=?,verified_by=? WHERE id=?",
            v.result(),stamp(Instant.now()),actor,report);
    }

    List<Map<String,Object>> vehicle(String session) {
        var s = data.session(session,false);
        return data.db.queryForList("SELECT category,verification,COUNT(*) AS reportCount FROM damage_report WHERE vehicle_id=? " +
            "AND (? IS NULL OR reported_at<=? OR session_id=?) GROUP BY category,verification ORDER BY category,verification",
            s.get("vehicle_id"),s.get("ended_at"),s.get("ended_at"),session);
    }

    @Transactional(readOnly=true)
    public Handover handover(String session) {
        var rental = data.session(session,false);
        Instant cutoff = instant(rental.get("started_at"));
        var records = publicRecords(session,rental.get("vehicle_id"),cutoff,true,true);
        return new Handover(cutoff, records.isEmpty() ? "UNAVAILABLE" : "AVAILABLE", "UNAVAILABLE", records);
    }

    private List<HandoverRecord> publicRecords(String session,Object vehicle,Instant cutoff,boolean strict,boolean priorOnly) {
        var cutoffUtc = stamp(cutoff);
        // Comparators are application constants; free text, photos and reporter identity never enter this projection.
        String before = strict ? "<" : "<=";
        return data.db.query("""
            SELECT SHA2(CONCAT('handover:', ?, ':', d.id), 256) AS record_id,
                   d.part_code, d.category, d.discovered_at, d.reported_at,
                   CASE WHEN d.verified_at IS NULL AND d.verification='UNVERIFIED' THEN 'UNVERIFIED'
                        WHEN d.verified_at %s ? THEN d.verification
                        ELSE COALESCE((SELECT h.verification FROM damage_verification_history h
                                       WHERE h.report_id=d.id AND h.effective_at %s ?
                                       ORDER BY h.effective_at DESC,h.sequence_no DESC LIMIT 1), 'UNKNOWN')
                   END AS handover_verification
            FROM damage_report d
            WHERE d.vehicle_id=? AND d.reported_at %s ? AND (?=FALSE OR d.session_id<>?)
            ORDER BY d.reported_at DESC,d.id
            """.formatted(before,before,before), (row,index) -> {
                String category = row.getString("category");
                category = CATEGORIES.contains(category) ? category : "UNKNOWN";
                String part = row.getString("part_code");
                return new HandoverRecord(row.getString("record_id"), row.getString("part_code"),
                    category, row.getString("handover_verification"),
                    instant(row.getObject("reported_at")), null, "CUSTOMER_REPORT",
                    publicDescription(part,category),instant(row.getObject("discovered_at")),instant(row.getObject("reported_at")));
            },session,cutoffUtc,cutoffUtc,vehicle,cutoffUtc,priorOnly,session);
    }

    private static String publicDescription(String part,String category) {
        String area = switch (part) {
            case "FRONT" -> "앞쪽"; case "REAR" -> "뒤쪽"; case "LEFT_FRONT" -> "왼쪽 앞";
            case "RIGHT_FRONT" -> "오른쪽 앞"; case "LEFT_REAR" -> "왼쪽 뒤"; case "RIGHT_REAR" -> "오른쪽 뒤";
            case "HOOD" -> "보닛"; case "ROOF" -> "지붕"; default -> "부위 미확인";
        };
        String type = switch (category) {
            case "SCRATCH" -> "긁힘"; case "DENT" -> "찌그러짐"; case "GLASS" -> "유리"; case "TIRE" -> "타이어";
            case "CABIN" -> "실내"; case "ACCIDENT" -> "사고"; case "BREAKDOWN" -> "고장"; default -> "기타";
        };
        return area + " · " + type + " 제보";
    }

    @Transactional(readOnly=true)
    public Map<String,Object> details(String session) {
        var s = data.session(session,false);
        Instant started = instant(s.get("started_at"));
        var startRecords = publicRecords(session,s.get("vehicle_id"),started,true,true);
        Map<String,Map<String,Object>> grouped = new TreeMap<>();
        for (var record : startRecords) {
            var group=grouped.computeIfAbsent(record.category()+":"+record.verification(),key ->
                new LinkedHashMap<>(Map.of("category",record.category(),"verification",record.verification(),"reportCount",0L)));
            group.put("reportCount",((Number)group.get("reportCount")).longValue()+1);
        }
        var own = data.db.queryForList("SELECT id,category,description,verification,discovered_at,reported_at FROM damage_report " +
            "WHERE session_id=? ORDER BY reported_at",session);
        var photos = data.db.queryForList("SELECT p.id,p.report_id FROM damage_photo p JOIN damage_report d ON p.report_id=d.id " +
            "WHERE d.session_id=? ORDER BY p.id",session);
        own.forEach(d -> d.put("photoIds",photos.stream().filter(p -> p.get("report_id").equals(d.get("id"))).map(p -> p.get("id")).toList()));
        Map<String,Object> atEnd = new LinkedHashMap<>();
        atEnd.put("asOf",s.get("ended_at")==null ? null : instant(s.get("ended_at")));
        if (s.get("ended_at")==null) {
            atEnd.put("availability","PENDING");atEnd.put("records",List.of());
            atEnd.put("unknownVerificationRecords",List.of());
        } else {
            var endRecords=publicRecords(session,s.get("vehicle_id"),instant(s.get("ended_at")),false,false);
            var unverified=endRecords.stream().filter(r -> "UNVERIFIED".equals(r.verification())).toList();
            var unknown=endRecords.stream().filter(r -> "UNKNOWN".equals(r.verification())).toList();
            atEnd.put("availability",!unknown.isEmpty() ? "PARTIAL" : unverified.isEmpty() ? "UNAVAILABLE" : "AVAILABLE");
            atEnd.put("records",unverified);atEnd.put("unknownVerificationRecords",unknown);
        }
        return Map.of("previousVehicleReports",new ArrayList<>(grouped.values()),
            "reportsFromThisSession",own.stream().map(RecordData::withUtcTimes).toList(),
            "existingAtStart",Map.of("asOf",started,"records",startRecords),"allUnverifiedAtEnd",atEnd);
    }

    @Transactional
    public Map<String,Object> attach(String session,String report,MultipartFile file,Boolean isParked,Instant observed) {
        var s = data.session(session,true);
        parked(s,isParked,observed);
        require(!data.db.queryForList("SELECT id FROM damage_report WHERE session_id=? AND id=? FOR UPDATE",session,report).isEmpty(),
            404,"REPORT_NOT_FOUND");
        int count = data.db.queryForObject("SELECT COUNT(*) FROM damage_photo WHERE report_id=?",Integer.class,report);
        require(count < data.env.getProperty("nextkey.damage.max-photos",Integer.class,4),409,"PHOTO_LIMIT");
        Photo safe = decode(file);
        String id = uuid();
        data.db.update("INSERT INTO damage_photo(id,report_id,image_bytes,mime_type) VALUES(?,?,?,?)",id,report,safe.bytes(),safe.mime());
        return Map.of("id",id,"mimeType",safe.mime(),"bytes",safe.bytes().length);
    }

    private Photo decode(MultipartFile file) {
        long max = Math.min(16_000_000L,data.env.getProperty("nextkey.damage.max-bytes",Long.class,2_000_000L));
        require(file!=null && file.getSize()>0 && file.getSize()<=max,413,"PHOTO_SIZE");
        require("image/jpeg".equals(file.getContentType()) || "image/png".equals(file.getContentType()),415,"PHOTO_TYPE");
        try (var input = file.getInputStream(); var stream = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream);
            require(readers.hasNext(),415,"INVALID_IMAGE");
            var reader = readers.next();
            try {
                reader.setInput(stream,true,true);
                String fmt = reader.getFormatName().toLowerCase(Locale.ROOT);
                require(("image/jpeg".equals(file.getContentType()) && (fmt.equals("jpg") || fmt.equals("jpeg")))
                    || ("image/png".equals(file.getContentType()) && fmt.equals("png")),415,"PHOTO_TYPE_MISMATCH");
                int w = reader.getWidth(0), h = reader.getHeight(0);
                require(w>0 && h>0 && w<=4096 && h<=4096 && (long)w*h<=8_000_000,413,"PHOTO_DIMENSIONS");
                BufferedImage decoded = reader.read(0);
                var clean = new BufferedImage(w,h,"png".equals(fmt) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var g = clean.createGraphics();
                try { g.drawImage(decoded,0,0,null); } finally { g.dispose(); }
                var bytes = new ByteArrayOutputStream();
                require(ImageIO.write(clean,fmt,bytes),415,"INVALID_IMAGE");
                require(bytes.size()<=max,413,"PHOTO_SIZE");
                return new Photo(file.getContentType(),bytes.toByteArray());
            } finally { reader.dispose(); }
        } catch (IOException ex) { throw new ApiError(415,"INVALID_IMAGE"); }
    }

    Photo photo(String session,String photo) {
        var rows = data.db.queryForList("SELECT p.mime_type,p.image_bytes FROM damage_photo p JOIN damage_report d ON d.id=p.report_id " +
            "WHERE d.session_id=? AND p.id=?",session,photo);
        require(!rows.isEmpty(),404,"PHOTO_NOT_FOUND");
        return new Photo((String)rows.get(0).get("mime_type"),(byte[])rows.get(0).get("image_bytes"));
    }
}
