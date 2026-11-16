package pleos.nextkey.records;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import pleos.nextkey.common.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic contract checks; no HTTP listener, MySQL server or native SDK is required. */
class VehicleEventsContractTests {
    private static final Instant AT=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    private final MemoryDb db=new MemoryDb();
    private final VehicleEvents events=new VehicleEvents(new RecordData(db,new MockEnvironment()));

    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

    private VehicleEvents.Input sdk(String event,BigDecimal confidence,Boolean accepted,Long millis,Instant fix,BigDecimal accuracy) {
        return new VehicleEvents.Input(event,"LOCATION",AT,"VEHICLE_SDK",null,null,null,null,null,"grant",
            true,true,fix,new BigDecimal("37.5"),new BigDecimal("127"),accuracy,
            confidence,accepted,millis,null,null,null,null);
    }

    @Test void confidenceIsStoredWithoutInventingMetersAndTimestampMillisCanSupplyFixTime() {
        var input=sdk("sdk",new BigDecimal("0.85"),true,AT.toEpochMilli(),null,null);
        assertEquals("VALID",events.accept("trip",input).get("status"));
        var payload=Json.read((String)db.saved.get("sdk").get("payload"));
        assertEquals("SDK_CONFIDENCE",payload.get("qualityBasis"));
        assertEquals(new BigDecimal("0.85"),new BigDecimal(payload.get("confidence").toString()));
        assertFalse(payload.containsKey("accuracyMeters"));
        assertEquals(Instant.ofEpochMilli(AT.toEpochMilli()),Instant.parse((String)payload.get("fixAt")));
        assertEquals(true,events.accept("trip",input).get("replayed"));
    }

    @Test void invalidSdkQualityAndConflictingTimesLeaveNoCoordinates() {
        var probes=List.of(
            sdk("quality-rejected",new BigDecimal("0.85"),false,null,AT,null),
            sdk("quality-missing",new BigDecimal("0.85"),null,null,AT,null),
            sdk("quality-range",new BigDecimal("1.01"),true,null,AT,null),
            sdk("time-mismatch",new BigDecimal("0.85"),true,AT.minusSeconds(1).toEpochMilli(),AT,null),
            sdk("meters-invalid",new BigDecimal("0.85"),true,null,AT,new BigDecimal("51")));
        var statuses=List.of("LOW_CONFIDENCE","LOW_CONFIDENCE","LOW_CONFIDENCE","FIX_TIME_MISMATCH","LOW_ACCURACY");
        for(int i=0;i<probes.size();i++) {
            assertEquals(statuses.get(i),events.accept("trip",probes.get(i)).get("status"));
            assertEquals("{}",db.saved.get(probes.get(i).eventId()).get("payload"));
        }
        assertEquals("EVENT_ID_CONFLICT",assertThrows(ApiError.class,()->events.accept("trip",
            sdk("quality-rejected",new BigDecimal("0.85"),true,null,AT,null))).code());
    }

    @Test void oldMeterBasedLocationsKeepTheirQualityAndSerializationContract() {
        var input=new VehicleEvents.Input("legacy","LOCATION",AT,"VEHICLE_SDK",null,null,null,null,null,"grant",
            true,true,AT,BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN);
        record LegacyInput(String eventId,String kind,Instant collectedAt,String source,String channel,BigDecimal value,
                           String unit,String warningCode,String reason,String consentId,Boolean permissionGranted,
                           Boolean supported,Instant fixAt,BigDecimal latitude,BigDecimal longitude,BigDecimal accuracyMeters) {}
        assertEquals(Json.write(new LegacyInput("legacy","LOCATION",AT,"VEHICLE_SDK",null,null,null,null,null,"grant",
            true,true,AT,BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN)),Json.write(input));
        assertEquals("VALID",events.accept("trip",input).get("status"));
        assertEquals("ACCURACY_METERS",Json.read((String)db.saved.get("legacy").get("payload")).get("qualityBasis"));
    }

    @Test void snapshotWarningIdentifiersAndLongSdkCodesCanBeRecordedWithoutRenaming() {
        for(String warning:List.of("washer-fluid","brake-fluid","vendor.Warning_1","A".repeat(64))) {
            String id="warning-"+db.saved.size();
            var input=new VehicleEvents.Input(id,"WARNING",AT,"VEHICLE_SDK",null,null,null,warning,null,null,
                null,null,null,null,null,null);
            assertEquals("REPORTED",events.accept("trip",input).get("status"));
            assertEquals(warning,Json.read((String)db.saved.get(id).get("payload")).get("warningCode"));
        }
        var invalid=new VehicleEvents.Input("bad","WARNING",AT,"VEHICLE_SDK",null,null,null,"<script>",null,null,
            null,null,null,null,null,null);
        assertEquals("INVALID_WARNING_CODE",assertThrows(ApiError.class,()->events.accept("trip",invalid)).code());
    }

    @Test void claimedSessionCanChooseAndWithdrawConsentButCannotChooseForAnotherSession() {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(new Actor("trip","SESSION"),null,List.of()));
        var controller=new VehicleEventController(new Access(db),events,new RecordRequests(new RecordData(db,new MockEnvironment())));
        assertEquals(true,controller.consent("trip",new VehicleEvents.Consent(true,"v1"),null).get("collectionAllowed"));
        assertEquals(false,controller.consent("trip",new VehicleEvents.Consent(false,"v1"),null).get("collectionAllowed"));
        assertEquals("FORBIDDEN",assertThrows(ApiError.class,()->controller.consent("other-trip",new VehicleEvents.Consent(true,"v1"),null)).code());
    }

    @Test void oldGrantReplayCannotUndoAWithdrawalAndStaleNewDecisionCannotOverwriteIt() {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(new Actor("trip","SESSION"),null,List.of()));
        var controller=new VehicleEventController(new Access(db),events,new RecordRequests(new RecordData(db,new MockEnvironment())));
        db.consent=new LinkedHashMap<>();
        var grant=new VehicleEvents.Consent(true,"v1","");
        var granted=controller.consent("trip",grant,"grant-request");
        String grantedId=(String)((Map<?,?>)granted.get("latest")).get("id");
        var withdrew=controller.consent("trip",new VehicleEvents.Consent(false,"v1",grantedId),"withdraw-request");
        String latest=(String)((Map<?,?>)withdrew.get("latest")).get("id");
        var replay=controller.consent("trip",grant,"grant-request");
        assertEquals(false,replay.get("collectionAllowed"));
        assertEquals(latest,((Map<?,?>)replay.get("latest")).get("id"));
        assertEquals("CONSENT_STATE_CHANGED",assertThrows(ApiError.class,()->controller.consent("trip",
            new VehicleEvents.Consent(true,"v1",grantedId),"new-stale-request")).code());
        assertEquals(false,db.consent.get("granted"));
    }

    @Test void endingStopsCollectionWithoutErasingTheEarlierConsentDecision() {
        db.sessionState="ENDED";
        var state=events.consentState("trip");
        assertEquals(false,state.get("collectionAllowed"));
        assertEquals(true,((Map<?,?>)state.get("latest")).get("granted"));
        assertEquals("SESSION_NOT_ACTIVE",assertThrows(ApiError.class,()->events.consent("trip",
            new VehicleEvents.Consent(true,"v1"))).code());
    }

    @Test void vehicleIdentityCanReadCompanySupportBeforeAPersonalSessionExists() {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(new Actor("car","VEHICLE"),null,List.of()));
        var data=new RecordData(db,new MockEnvironment().withProperty("nextkey.support.phone","TEST-CONTACT"));
        var api=new RecordApi(new Access(db),null,new DamageRecords(data),null);
        assertEquals(Map.of("status","CONFIGURED","phone","TEST-CONTACT"),api.support());
        assertEquals("FORBIDDEN",assertThrows(ApiError.class,()->api.support("trip")).code());
    }

    private VehicleEvents.Input gap(String event,String kind,String reason,String interval,Instant from,Instant to,Instant at) {
        return new VehicleEvents.Input(event,kind,at,"VEHICLE_SDK",null,null,null,null,reason,null,
            null,null,null,null,null,null,null,null,null,interval,"runtime",from,to);
    }

    @Test void closedIntervalCanArriveBeforeOpenAndCollectionGapsStaySeparateFromSyncDelays() {
        Instant from=AT.minusSeconds(30),to=AT.minusSeconds(10);
        events.accept("trip",gap("closed","GAP","HIDDEN","gap-1",from,to,AT));
        events.accept("trip",gap("open","GAP","HIDDEN","gap-1",from,null,from));
        events.accept("trip",gap("sync","SYNC","OFFLINE","sync-1",from,to,AT));
        var route=events.route(new ArrayList<>(db.saved.values()));
        var gaps=(List<?>)route.get("dataGaps");var delays=(List<?>)route.get("syncDelays");
        assertEquals(1,gaps.size());assertEquals(1,delays.size());
        var interval=(Map<?,?>)gaps.get(0);
        assertEquals(from,interval.get("from"));assertEquals(to,interval.get("to"));
        assertEquals(true,interval.get("closed"));assertEquals(20L,interval.get("durationSeconds"));
        assertEquals("HIDDEN",interval.get("reason"));assertEquals("OFFLINE",((Map<?,?>)delays.get(0)).get("reason"));
        assertEquals("INTERVAL_ID_CONFLICT",assertThrows(ApiError.class,()->events.accept("trip",
            gap("conflict","GAP","HIDDEN","gap-1",from,to.plusSeconds(1),AT))).code());
        assertEquals("INVALID_INTERVAL_TIME",assertThrows(ApiError.class,()->events.accept("trip",
            gap("invalid","GAP","HIDDEN","gap-2",to,from,AT))).code());
    }

    @Test void legacyGapRemainsAPointAndSyncDoesNotBreakAValidRoute() {
        var legacy=new VehicleEvents.Input("legacy-gap","GAP",AT,"VEHICLE_SDK",null,null,null,null,"UNAVAILABLE",null,
            null,null,null,null,null,null);
        events.accept("trip",legacy);
        var gaps=events.intervals(new ArrayList<>(db.saved.values()),"GAP");
        assertEquals(Map.of("at",AT,"reason","UNAVAILABLE","interval",false),gaps.get(0));
        var sync=Map.<String,Object>of("kind","SYNC","collected_at",AT.minusSeconds(30),"status","OFFLINE","payload","{}");
        var result=events.route(List.of(point(AT.minusSeconds(60)),sync,point(AT)));
        assertEquals(1,((List<?>)result.get("segments")).size());
        assertEquals(1,((List<?>)result.get("estimatedStops")).size());
    }

    @Test void explicitGapPreventsAnInferredStopEvenWhenRecoveryEventArrivesAfterTheSamples() {
        var gap=Map.<String,Object>of("kind","GAP","collected_at",AT,"status","HIDDEN","payload",Json.write(Map.of(
            "intervalId","gap","intervalKind","runtime","from",AT.minusSeconds(45),"to",AT.minusSeconds(15))));
        var result=events.route(List.of(point(AT.minusSeconds(60)),point(AT),gap));
        assertEquals(2,((List<?>)result.get("segments")).size());
        assertTrue(((List<?>)result.get("estimatedStops")).isEmpty());
    }

    private Map<String,Object> point(Instant at) {
        return Map.of("kind","LOCATION","collected_at",at,"status","VALID","consent_id","grant",
            "payload","{\"latitude\":37.5,\"longitude\":127}");
    }

    private static class MemoryDb extends JdbcTemplate {
        final Map<String,Map<String,Object>> saved=new LinkedHashMap<>();
        final Map<String,Map<String,Object>> memos=new LinkedHashMap<>();
        String sessionState="ACTIVE";
        Map<String,Object> consent=new LinkedHashMap<>(Map.of("id","grant","granted",true,"policy_version","v1","changed_at",AT.minusSeconds(60)));
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
            if(sql.startsWith("SELECT * FROM rental_session")) return List.of(new LinkedHashMap<>(Map.of(
                "id",args[0],"account_id","owner","state",sessionState,"cleanup_state","PENDING","started_at",AT.minusSeconds(3600))));
            if(sql.startsWith("SELECT id,granted")) return List.of(consent);
            if(sql.startsWith("SELECT session_id,request_hash")) return saved.containsKey(args[0]) ? List.of(saved.get(args[0])) : List.of();
            if(sql.startsWith("SELECT kind,status,payload")) return saved.values().stream().filter(r -> Objects.equals(
                Json.read((String)r.get("payload")).get("intervalId"),args[1])).toList();
            if(sql.startsWith("SELECT request_hash,result FROM record_request")) {
                var memo=memos.get(args[0]+":"+args[1]+":"+args[2]);
                return memo==null ? List.of() : List.of(memo);
            }
            throw new AssertionError(sql);
        }
        @Override public int update(String sql,Object... a) {
            if(sql.startsWith("INSERT INTO vehicle_event")) {
                Map<String,Object> r=new LinkedHashMap<>();
                String[] names={"id","session_id","kind","collected_at","received_at","source","verification","status","consent_id","payload","request_hash"};
                for(int i=0;i<names.length;i++)r.put(names[i],a[i]);
                saved.put((String)a[0],r);return 1;
            }
            if(sql.startsWith("INSERT INTO location_consent")) {
                consent=new LinkedHashMap<>(Map.of("id",a[0],"granted",a[2],"policy_version",a[3],"changed_at",a[4]));return 1;
            }
            if(sql.startsWith("INSERT INTO record_request")) {
                memos.put(a[0]+":"+a[1]+":"+a[2],Map.of("request_hash",a[3],"result",a[4]));return 1;
            }
            throw new AssertionError(sql);
        }
    }
}
