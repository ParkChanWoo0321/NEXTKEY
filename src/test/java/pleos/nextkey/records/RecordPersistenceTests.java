package pleos.nextkey.records;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;

@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
@Transactional
class RecordPersistenceTests {
    @Autowired RecordData data;
    @Autowired VehicleEvents events;
    @Autowired DamageRecords damage;
    @Autowired RecordRequests requests;
    @Autowired PersonalSettings settings;
    @Autowired RentalRecords records;
    String id;
    String car;

    @BeforeEach void sampleSession() {
        id=RecordData.uuid();
        car=RecordData.uuid();
        String customer=RecordData.uuid(), booking=RecordData.uuid();
        data.db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,'CUSTOMER','DISABLED')",customer,customer);
        data.db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'VIRTUAL-EV','BATTERY',?)",car,car);
        data.db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) " +
            "VALUES(?,?,?,'CONFIRMED',UTC_TIMESTAMP()-INTERVAL 1 HOUR, UTC_TIMESTAMP()+INTERVAL 1 HOUR)",booking,customer,car);
        data.db.update("INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,start_json) " +
            "VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP()-INTERVAL 30 MINUTE,JSON_OBJECT())",id,booking,customer,car);
    }

    VehicleEvents.Input point(String event,String consent,Instant collected,Instant fix, int accuracy) {
        return new VehicleEvents.Input(event,"LOCATION",collected,"DEV_SIMULATOR",null,null,null,null,null,consent,
            true,true,fix,BigDecimal.valueOf(36.6),BigDecimal.valueOf(126.5),BigDecimal.valueOf(accuracy));
    }

    @Test void revokedAndStaleLocationsLeaveNoCoordinatesAndRegrantStartsNewSegment() {
        String first=(String)((Map<?,?>)events.consent(id,new VehicleEvents.Consent(true,"v1")).get("latest")).get("id");
        Instant firstAt=Instant.now();
        var sample=point(RecordData.uuid(),first,firstAt,firstAt,12);
        assertEquals("VALID",events.accept(id,sample).get("status"));
        assertEquals(true,events.accept(id,sample).get("replayed"));
        events.consent(id,new VehicleEvents.Consent(false,"v1"));
        var denied=point(RecordData.uuid(),first,Instant.now(),firstAt,12);
        assertEquals("NO_CURRENT_GRANT",events.accept(id,denied).get("status"));
        assertEquals("{}",data.db.queryForObject("SELECT payload FROM vehicle_event WHERE id=?",String.class,denied.eventId()));
        String next=(String)((Map<?,?>)events.consent(id,new VehicleEvents.Consent(true,"v1")).get("latest")).get("id");
        var stale=point(RecordData.uuid(),next,Instant.now(),firstAt.minusSeconds(60),12);
        assertEquals("STALE_FIX",events.accept(id,stale).get("status"));
        Instant after=Instant.now();
        assertEquals("VALID",events.accept(id,point(RecordData.uuid(),next,after,after,12)).get("status"));
        var route=events.route(events.timeline(id));
        assertEquals(2,((List<?>)route.get("segments")).size());
        assertEquals(2,((List<?>)route.get("gaps")).size());
        assertTrue(records.accepted(id,List.of(sample.eventId(),denied.eventId())));
        assertFalse(records.accepted(id,List.of(RecordData.uuid())));
    }

    @Test void delayedEnergyDoesNotReplaceNewerReadingAndUnitMismatchIsUnknown() {
        Instant now=Instant.now();
        var newest=new VehicleEvents.Input(RecordData.uuid(),"MEASUREMENT",now,"DEV_SIMULATOR","BATTERY",
            BigDecimal.valueOf(15),"kWh",null,null,null,null,null,null,null,null,null);
        var older=new VehicleEvents.Input(RecordData.uuid(),"MEASUREMENT",now.minusSeconds(10),"DEV_SIMULATOR","BATTERY",
            BigDecimal.valueOf(70),"%",null,null,null,null,null,null,null,null,null);
        events.accept(id,newest);
        events.accept(id,older);
        var energy=records.latestEnergy(id);
        assertEquals("UNIT_MISMATCH",energy.get("status"));
        assertNull(energy.get("value"));
    }

    @Test void profileReadbackReportsPartialFailureAndOnlyStoresSupportedSettings() {
        assertEquals(false,settings.profile(id).get("hasSavedProfile"));
        settings.capability(car,new PersonalSettings.Capability("CLIMATE_TARGET_C",List.of(BigDecimal.valueOf(22),BigDecimal.valueOf(23))));
        settings.capability(car,new PersonalSettings.Capability("SEAT_MEMORY_SLOT",List.of(BigDecimal.ONE)));
        settings.save(id,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22,"SEAT_MEMORY_SLOT",1,"MIRROR",123)));
        var match=(Map<?,?>)settings.profile(id).get("compatibleProfile");
        assertFalse(((Map<?,?>)match.get("readings")).containsKey("MIRROR"));
        var gate=new PersonalSettings.Gate(true,true,Instant.now());
        String app=(String)settings.request(id,gate).get("id");
        assertEquals("APPLYING",settings.applying(id,app,gate).get("state"));
        var result=settings.readback(id,app,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",23,"SEAT_MEMORY_SLOT",1)));
        assertEquals("PARTIAL",result.get("state"));
    }

    @Test void postEndPhotoIsPrivateAndKeepsFixedSnapshots() throws Exception {
        data.db.update("UPDATE rental_session SET state='ENDED',ended_at=UTC_TIMESTAMP(6),end_json=JSON_OBJECT('fixed',true),cleanup_state='COMPLETE' WHERE id=?",id);
        String report=(String)damage.create(id,new DamageRecords.Submission("DENT","personal note",Instant.now(),null,null,null)).get("id");
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var file=new MockMultipartFile("file","photo.png","image/png",bytes.toByteArray());
        String photo=(String)damage.attach(id,report,file,null,null).get("id");
        assertEquals("image/png",damage.photo(id,photo).mime());
        assertEquals(Set.of("category","verification","reportCount"),damage.vehicle(id).get(0).keySet());
        assertTrue(data.db.queryForObject("SELECT end_json FROM rental_session WHERE id=?",String.class,id).contains("fixed"));
    }

    @Test void falseMimeAndInvalidPixelsAreRejected() {
        String report=(String)damage.create(id,new DamageRecords.Submission("DENT","dent",Instant.now(),true,Instant.now(),null)).get("id");
        assertThrows(ApiError.class,() -> damage.attach(id,report,new MockMultipartFile("file","fake.png","image/png",new byte[]{1,2}),true,Instant.now()));
    }

    @Test void identicalClientRetryKeepsOneDamageReportAndRejectsChangedBody() {
        var submission=new DamageRecords.Submission("DENT","가상 테스트",Instant.now(),true,Instant.now(),null);
        record PriorSubmission(String category, String description, Instant discoveredAt, Boolean parked, Instant safetyObservedAt) {}
        var originalShape = new PriorSubmission(submission.category(),submission.description(),submission.discoveredAt(),
            submission.parked(),submission.safetyObservedAt());
        String key=RecordData.uuid();
        var first=requests.once(id,"DAMAGE",key,originalShape,()->damage.create(id,submission));
        var replay=requests.once(id,"DAMAGE",key,submission,()->damage.create(id,submission));
        assertEquals(first.get("id"),replay.get("id"));
        assertEquals(1,data.db.queryForObject("SELECT COUNT(*) FROM damage_report WHERE session_id=?",Integer.class,id));
        var withPart = new DamageRecords.Submission(submission.category(),submission.description(),submission.discoveredAt(),
            submission.parked(),submission.safetyObservedAt(),"LEFT_FRONT");
        assertThrows(ApiError.class,()->requests.once(id,"DAMAGE",key,withPart,()->damage.create(id,withPart)));
    }

    @Test void reorderedNestedSettingsJsonReplaysOneOriginalSave() {
        settings.capability(car,new PersonalSettings.Capability("CLIMATE_TARGET_C",List.of(BigDecimal.valueOf(21))));
        settings.capability(car,new PersonalSettings.Capability("CLIMATE_FAN_LEVEL",List.of(BigDecimal.valueOf(2))));
        var readings=new LinkedHashMap<String,Object>();
        readings.put("CLIMATE_TARGET_C",21);
        readings.put("CLIMATE_FAN_LEVEL",2);
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("status","OK");
        metadata.put("unit","C");
        Instant at=Instant.now();
        var input=new PersonalSettings.Observation(at,"DEV_SIMULATOR",readings,
                Map.of("CLIMATE_TARGET_C",metadata),null,null);
        String key=RecordData.uuid();
        var first=requests.once(id,"SAVE_SETTINGS",key,input,()->settings.save(id,input));

        var reorderedReadings=new LinkedHashMap<String,Object>();
        reorderedReadings.put("CLIMATE_FAN_LEVEL",2);
        reorderedReadings.put("CLIMATE_TARGET_C",21);
        var reorderedMetadata=new LinkedHashMap<String,Object>();
        reorderedMetadata.put("unit","C");
        reorderedMetadata.put("status","OK");
        var replayInput=new PersonalSettings.Observation(at,"DEV_SIMULATOR",reorderedReadings,
                Map.of("CLIMATE_TARGET_C",reorderedMetadata),null,null);
        var replay=requests.once(id,"SAVE_SETTINGS",key,replayInput,()->settings.save(id,replayInput));
        assertEquals(first.get("id"),replay.get("id"));
        assertEquals(1,data.db.queryForObject("SELECT COUNT(*) FROM setting_activity WHERE session_id=?",Integer.class,id));
        var changed=new PersonalSettings.Observation(at,"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT",assertThrows(ApiError.class,
                ()->requests.once(id,"SAVE_SETTINGS",key,changed,()->settings.save(id,changed))).code());
    }

    @Test void legacyUnsortedRequestDigestStillReplays() throws Exception {
        var original=new LinkedHashMap<String,Object>();
        original.put("z",Map.of("second",2,"first",1));
        original.put("a",true);
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Json.write(original).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String key=RecordData.uuid();
        data.db.update("INSERT INTO record_request(session_id,operation_name,request_id,request_hash,result) VALUES(?,?,?,?,?)",
                id,"LEGACY_TEST",key,digest,Json.write(Map.of("id","legacy-result")));
        var result=requests.once(id,"LEGACY_TEST",key,original,()->{
            fail("A saved legacy request must not execute again");
            return Map.of();
        });
        assertEquals("legacy-result",result.get("id"));
    }

    @Test void handoverShowsOnlyPriorRecordsAndTheVerificationKnownAtStart() {
        Instant cutoff = RecordData.instant(data.session(id,false).get("started_at"));
        String previous = completedRentalBefore(cutoff);
        assertEquals("UNAVAILABLE",damage.handover(id).availability());
        assertEquals("UNAVAILABLE",damage.handover(id).impactAvailability());

        String record = historicalReport(previous,cutoff.minusSeconds(20),"LEFT_FRONT");
        String legacy = historicalReport(previous,cutoff.minusSeconds(35),null);
        historicalReport(previous,cutoff,null); // simultaneous and late reports are outside the handover
        historicalReport(previous,cutoff.plusSeconds(1),"REAR");
        damage.create(id,new DamageRecords.Submission("DENT","same trip",Instant.now(),true,Instant.now(),"ROOF"));
        // A legacy report was verified more than once; only the most recent decision survived before V5.
        data.db.update("UPDATE damage_report SET verification='CONFIRMED',verified_at=? WHERE id=?",
            RecordData.stamp(cutoff.plusSeconds(15)),legacy);
        damage.verify(record,"demo-operator",new DamageRecords.Verification("CONFIRMED"));
        damage.verify(record,"demo-operator",new DamageRecords.Verification("DISMISSED"));

        var atStart = damage.handover(id);
        assertEquals(cutoff,atStart.asOf());
        assertEquals("AVAILABLE",atStart.availability());
        assertEquals(2,atStart.records().size());
        var first = atStart.records().get(0);
        assertEquals("LEFT_FRONT",first.partCode());
        assertEquals("DENT",first.category());
        assertEquals("CUSTOMER_REPORT",first.source());
        assertNull(first.occurredAt());
        assertEquals(cutoff.minusSeconds(20),first.recordedAt());
        assertEquals("UNVERIFIED",first.verification());
        assertNotEquals(record,first.recordId());
        assertTrue(first.recordId().matches("[0-9a-f]{64}"));
        assertEquals(atStart,damage.handover(id));
        assertEquals("UNKNOWN",atStart.records().get(1).partCode());
        assertEquals("UNKNOWN",atStart.records().get(1).verification());
        var fields = pleos.nextkey.common.Json.read(pleos.nextkey.common.Json.write(first));
        assertEquals(Set.of("recordId","partCode","category","verification","recordedAt","occurredAt","source",
            "publicDescription","discoveredAt","reportedAt"),fields.keySet());
        assertEquals("왼쪽 앞 · 찌그러짐 제보",first.publicDescription());
        assertEquals(cutoff.minusSeconds(25),first.discoveredAt());
        assertEquals(first.recordedAt(),first.reportedAt());
        assertFalse(pleos.nextkey.common.Json.write(first).contains("private reporter note"));
        assertEquals("INVALID_DAMAGE_PART",assertThrows(ApiError.class,()->damage.create(id,
            new DamageRecords.Submission("DENT","invalid",Instant.now(),true,Instant.now(),"UNSTRUCTURED"))).code());
    }

    private String completedRentalBefore(Instant cutoff) {
        String previous = RecordData.uuid(), customer = RecordData.uuid(), booking = RecordData.uuid();
        data.db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,'CUSTOMER','DISABLED')",customer,customer);
        var from = RecordData.stamp(cutoff.minusSeconds(3600));
        var until = RecordData.stamp(cutoff.minusSeconds(100));
        data.db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) VALUES(?,?,?,'CONFIRMED',?,?)",
            booking,customer,car,from,until);
        data.db.update("""
            INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,ended_at,start_json,cleanup_state)
            VALUES(?,?,?,?,'ENDED',?,?,JSON_OBJECT(),'COMPLETE')
            """,previous,booking,customer,car,from,until);
        return previous;
    }

    @Test void damageReportKeepsVerificationAtStartAndEndAfterLaterOperatorChanges() {
        Instant start=RecordData.instant(data.session(id,false).get("started_at"));
        Instant end=start.plusSeconds(60);
        String previous=completedRentalBefore(start);
        String confirmedBeforeEnd=historicalReport(previous,start.minusSeconds(20),"FRONT");
        String unverifiedAtEnd=historicalReport(previous,start.minusSeconds(15),"REAR");
        data.db.update("INSERT INTO damage_verification_history(report_id,verification,effective_at) VALUES(?,'UNVERIFIED',?)",
            confirmedBeforeEnd,RecordData.stamp(start.minusSeconds(20)));
        data.db.update("UPDATE damage_report SET verification='CONFIRMED',verified_at=? WHERE id=?",
            RecordData.stamp(start.plusSeconds(10)),confirmedBeforeEnd);
        data.db.update("UPDATE rental_session SET state='ENDED',ended_at=? WHERE id=?",RecordData.stamp(end),id);
        damage.verify(unverifiedAtEnd,"demo-operator",new DamageRecords.Verification("CONFIRMED"));
        historicalReport(previous,end.plusSeconds(1),"ROOF");

        var details=damage.details(id);
        var atStart=(Map<?,?>)details.get("existingAtStart");
        var startRecords=(List<?>)atStart.get("records");
        assertEquals(start,atStart.get("asOf"));assertEquals(2,startRecords.size());
        assertTrue(startRecords.stream().allMatch(r -> "UNVERIFIED".equals(((DamageRecords.HandoverRecord)r).verification())));
        var atEnd=(Map<?,?>)details.get("allUnverifiedAtEnd");
        var endRecords=(List<?>)atEnd.get("records");
        assertEquals(end,atEnd.get("asOf"));assertEquals(1,endRecords.size());
        assertEquals("REAR",((DamageRecords.HandoverRecord)endRecords.get(0)).partCode());
        assertEquals("UNVERIFIED",((DamageRecords.HandoverRecord)endRecords.get(0)).verification());
        assertFalse(pleos.nextkey.common.Json.write(atEnd).contains("private reporter note"));
    }

    private String historicalReport(String previous, Instant recorded, String part) {
        var input = new DamageRecords.Submission("DENT","private reporter note",recorded.minusSeconds(5),null,null,part);
        String report = (String)damage.create(previous,input).get("id");
        data.db.update("UPDATE damage_report SET reported_at=? WHERE id=?",RecordData.stamp(recorded),report);
        return report;
    }
}
