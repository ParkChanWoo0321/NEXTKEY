package pleos.nextkey.records;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.ApiError;

/** Regression specifications derived from the uploaded HTML/PDF settings states. */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_SPEC_AUDIT", matches="true")
@Transactional
class AuditSettingsContractTests {
    @Autowired RecordData data;
    @Autowired PersonalSettings settings;
    String owner;
    String car;
    String session;
    String compatibility;

    @BeforeEach void fixture() {
        owner = RecordData.uuid();
        compatibility = RecordData.uuid();
        data.db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,'CUSTOMER','DISABLED')", owner, owner);
        car = vehicle();
        session = session(car);
    }

    private String vehicle() {
        String id = RecordData.uuid();
        data.db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'VIRTUAL-EV','BATTERY',?)", id, compatibility);
        return id;
    }

    private String session(String vehicle) {
        String id = RecordData.uuid(), reservation = RecordData.uuid();
        data.db.update("""
            INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at)
            VALUES(?,?,?,'CONFIRMED',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR)
            """, reservation, owner, vehicle);
        data.db.update("""
            INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,start_json)
            VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP()-INTERVAL 30 MINUTE,JSON_OBJECT())
            """, id, reservation, owner, vehicle);
        return id;
    }

    private void supported(String vehicle, String name, int... values) {
        settings.capability(vehicle, new PersonalSettings.Capability(name,
            java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList()));
    }

    @Test void memorySlotFromAnotherVehicleMustNotBecomeAnAutomaticTarget() {
        // HTML 671/693: both source vehicle and memory reference vehicle must match.
        supported(car, "SEAT_MEMORY_SLOT", 1, 2);
        supported(car, "CLIMATE_TARGET_C", 22);
        settings.save(session, new PersonalSettings.Observation(Instant.now(), "DEV_SIMULATOR", Map.of("SEAT_MEMORY_SLOT", 1,"CLIMATE_TARGET_C",22)));
        data.db.update("UPDATE rental_session SET state='ENDED',cleanup_state='COMPLETE',ended_at=UTC_TIMESTAMP(6) WHERE id=?", session);
        String nextCar = vehicle();
        supported(nextCar, "SEAT_MEMORY_SLOT", 1, 2);
        supported(nextCar, "CLIMATE_TARGET_C", 22);
        String nextSession = session(nextCar);

        var application = settings.request(nextSession, new PersonalSettings.Gate(true, true, Instant.now()));
        var detail = (Map<?,?>) application.get("detail");
        var desired = (Map<?,?>) detail.get("desired");
        assertFalse(desired.containsKey("SEAT_MEMORY_SLOT"),
            "A memory number on the previous vehicle does not identify the same pre-existing memory on the next vehicle.");
    }

    @Test void readableSeatPositionMustBePersistableWithoutAReadableTemperature() {
        // PDF wide p218: REV-manual-seat-only must preserve one real seat reading.
        var result = settings.save(session, new PersonalSettings.Observation(Instant.now(), "DEV_SIMULATOR",
            Map.of("SEAT_FORE_AFT_POSITION", 48)));
        assertEquals("SAVED", result.get("state"));
        var profile = (Map<?,?>) settings.profile(session).get("compatibleProfile");
        var readings = (Map<?,?>) profile.get("readings");
        assertEquals(48, ((Number) readings.get("SEAT_FORE_AFT_POSITION")).intValue());
        var fractional = assertThrows(ApiError.class,()->settings.save(session,
            new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("SEAT_FORE_AFT_POSITION",48.5))));
        assertEquals("NO_SUPPORTED_READINGS",fractional.code());
    }

    @Test void explicitDraftIsVerifiedWithoutOverwritingTheObservedProfile() {
        supported(car, "CLIMATE_TARGET_C", 22, 23);
        settings.save(session, new PersonalSettings.Observation(Instant.now(), "DEV_SIMULATOR", Map.of("CLIMATE_TARGET_C", 22)));
        var application = settings.request(session, new PersonalSettings.Gate(true, true, Instant.now(),Map.of("CLIMATE_TARGET_C",23)));
        String id = (String) application.get("id");
        var detail = (Map<?,?>) application.get("detail");
        assertEquals(23, ((Number) ((Map<?,?>) detail.get("desired")).get("CLIMATE_TARGET_C")).intValue());
        settings.applying(session, id, new PersonalSettings.Gate(true, true, Instant.now()));
        var result = settings.readback(session, id,
            new PersonalSettings.Readback(Instant.now(), "DEV_SIMULATOR", Map.of("CLIMATE_TARGET_C", 23)));
        assertEquals("APPLIED", result.get("state"));
        var profile=(Map<?,?>)settings.profile(session).get("compatibleProfile");
        assertEquals(22,((Number)((Map<?,?>)profile.get("readings")).get("CLIMATE_TARGET_C")).intValue());
        assertEquals(23,((Number)((Map<?,?>)((Map<?,?>)result.get("detail")).get("originalReadback")).get("CLIMATE_TARGET_C")).intValue());
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",23)));
        var updated=(Map<?,?>)settings.profile(session).get("compatibleProfile");
        assertEquals(23,((Number)((Map<?,?>)updated.get("readings")).get("CLIMATE_TARGET_C")).intValue());
    }

    @Test void readOnlyObservationAndOneFailedReadKeepIndependentMetadata() {
        settings.capability(car,new PersonalSettings.Capability("SEAT_FORE_AFT_POSITION",List.of(),"READ","ROW_1_LEFT",null,null,null));
        supported(car,"CLIMATE_TARGET_C",22,23);
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",
            Map.of("SEAT_FORE_AFT_POSITION",48),
            Map.of("SEAT_FORE_AFT_POSITION",Map.of("status","OK","unit","native-position","area","ROW_1_LEFT"),
                   "CLIMATE_TARGET_C",Map.of("status","READ_FAILED")),null,"MANUAL_ADJUSTMENT"));
        var profile=(Map<?,?>)settings.profile(session).get("compatibleProfile");
        assertEquals("READ_FAILED",((Map<?,?>)((Map<?,?>)profile.get("observation_details")).get("CLIMATE_TARGET_C")).get("status"));
        var error=assertThrows(ApiError.class,()->settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),Map.of("SEAT_FORE_AFT_POSITION",48))));
        assertEquals("READ_ONLY_SETTING",error.code());
        var zero=assertThrows(ApiError.class,()->settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of())));
        assertEquals("NO_SUPPORTED_READINGS",zero.code());
    }

    @Test void writableRangeAndReadCapabilityAreIndependent() {
        settings.capability(car,new PersonalSettings.Capability("CLIMATE_TARGET_C",List.of(),"READ_WRITE","CABIN",BigDecimal.valueOf(16),BigDecimal.valueOf(30),BigDecimal.valueOf(.5)));
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22)));
        var rejected=settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),Map.of("CLIMATE_TARGET_C",31)));
        assertEquals("FAILED",rejected.get("state"));
        assertEquals("OUT_OF_RANGE",field(rejected,"CLIMATE_TARGET_C").get("status"));
        settings.capability(car,new PersonalSettings.Capability("CLIMATE_TARGET_C",List.of(),"READ","CABIN",null,null,null));
        // A real readable value is retained even though no write range is available.
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",23)));
        var readonly=settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),Map.of("CLIMATE_TARGET_C",23)));
        assertEquals("WRITE_UNAVAILABLE",field(readonly,"CLIMATE_TARGET_C").get("status"));
    }

    @Test void retryOnlyFailedFieldPreservesMatchedEvidence() {
        supported(car,"CLIMATE_TARGET_C",22,23);
        supported(car,"CLIMATE_FAN_LEVEL",1,2);
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22,"CLIMATE_FAN_LEVEL",1)));
        var first=settings.request(session,new PersonalSettings.Gate(true,true,Instant.now()));
        String firstId=(String)first.get("id");
        settings.applying(session,firstId,new PersonalSettings.Gate(true,true,Instant.now()));
        var partial=settings.readback(session,firstId,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22,"CLIMATE_FAN_LEVEL",2)));
        assertEquals("PARTIAL",partial.get("state"));
        var retry=settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),null,firstId,List.of("CLIMATE_FAN_LEVEL"),null));
        var desired=(Map<?,?>)((Map<?,?>)retry.get("detail")).get("desired");
        assertEquals(java.util.Set.of("CLIMATE_FAN_LEVEL"),desired.keySet());
        String retryId=(String)retry.get("id");
        settings.applying(session,retryId,new PersonalSettings.Gate(true,true,Instant.now()));
        var applied=settings.readback(session,retryId,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_FAN_LEVEL",1)));
        assertEquals("APPLIED",applied.get("state"));
        assertEquals("MATCHED",field(applied,"CLIMATE_TARGET_C").get("status"));
        assertEquals("PARTIAL",settings.application(session,firstId).get("state"));
        var invalid=assertThrows(ApiError.class,()->settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),null,firstId,List.of("CLIMATE_TARGET_C"),null)));
        assertEquals("RETRY_MATCHED_FIELD",invalid.code());
    }

    @Test void matchingSlotNumberAloneNeverVerifiesActualSeatPosition() {
        supported(car,"SEAT_MEMORY_SLOT",1,2);
        var reference=new PersonalSettings.MemoryReference(car,1,"VERIFIED_EXISTING_VEHICLE_MEMORY_REFERENCE",BigDecimal.valueOf(48));
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("SEAT_FORE_AFT_POSITION",48),null,reference,null));
        var first=settings.request(session,new PersonalSettings.Gate(true,true,Instant.now()));
        String app=(String)first.get("id");
        settings.applying(session,app,new PersonalSettings.Gate(true,true,Instant.now()));
        var unverified=settings.readback(session,app,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("SEAT_MEMORY_SLOT",1)));
        assertEquals("UNVERIFIED",field(unverified,"SEAT_MEMORY_SLOT").get("status"));
        assertNotEquals("APPLIED",unverified.get("state"));
        var retry=settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),null,app,null,null));
        String retryId=(String)retry.get("id");
        settings.applying(session,retryId,new PersonalSettings.Gate(true,true,Instant.now()));
        var verified=settings.readback(session,retryId,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("SEAT_FORE_AFT_POSITION",48)));
        assertEquals("MATCHED",field(verified,"SEAT_MEMORY_SLOT").get("status"));
        assertEquals("APPLIED",verified.get("state"));
    }

    @Test void retryWithUnavailableWriteKeepsThePreviousMatchedFieldPartial() {
        supported(car,"CLIMATE_TARGET_C",22);
        supported(car,"CLIMATE_FAN_LEVEL",1,2);
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",
            Map.of("CLIMATE_TARGET_C",22,"CLIMATE_FAN_LEVEL",1)));
        String first=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now())).get("id");
        settings.applying(session,first,new PersonalSettings.Gate(true,true,Instant.now()));
        var partial=settings.readback(session,first,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",
            Map.of("CLIMATE_TARGET_C",22,"CLIMATE_FAN_LEVEL",2)));
        assertEquals("PARTIAL",partial.get("state"));

        supported(car,"CLIMATE_FAN_LEVEL");
        var retry=settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),
            null,first,List.of("CLIMATE_FAN_LEVEL"),null));
        assertEquals("PARTIAL",retry.get("state"));
        assertEquals("MATCHED",field(retry,"CLIMATE_TARGET_C").get("status"));
        assertEquals("WRITE_UNAVAILABLE",field(retry,"CLIMATE_FAN_LEVEL").get("status"));
        assertEquals(Map.of(),((Map<?,?>)retry.get("detail")).get("desired"));

        var explicitRetry=settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),
            Map.of("CLIMATE_FAN_LEVEL",1),first,List.of("CLIMATE_FAN_LEVEL"),null));
        assertEquals("PARTIAL",explicitRetry.get("state"));
        assertEquals("MATCHED",field(explicitRetry,"CLIMATE_TARGET_C").get("status"));
        assertEquals("WRITE_UNAVAILABLE",field(explicitRetry,"CLIMATE_FAN_LEVEL").get("status"));
        assertEquals(Map.of(),((Map<?,?>)explicitRetry.get("detail")).get("desired"));
        assertEquals("PARTIAL",settings.application(session,first).get("state"));
    }

    @Test void retrySubsetDoesNotEraseAnUnselectedFailure() {
        supported(car,"CLIMATE_TARGET_C",22,23);
        supported(car,"CLIMATE_FAN_LEVEL",1,2);
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",
            Map.of("CLIMATE_TARGET_C",22,"CLIMATE_FAN_LEVEL",1,"SEAT_FORE_AFT_POSITION",48)));
        String first=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now())).get("id");
        settings.applying(session,first,new PersonalSettings.Gate(true,true,Instant.now()));
        settings.readback(session,first,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22,"CLIMATE_FAN_LEVEL",2)));
        String retry=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),null,first,List.of("CLIMATE_FAN_LEVEL"),null)).get("id");
        settings.applying(session,retry,new PersonalSettings.Gate(true,true,Instant.now()));
        var result=settings.readback(session,retry,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_FAN_LEVEL",1)));
        assertEquals("PARTIAL",result.get("state"));
        assertEquals("READ_ONLY_SETTING",field(result,"SEAT_FORE_AFT_POSITION").get("status"));
        assertEquals("MATCHED",field(result,"CLIMATE_TARGET_C").get("status"));
    }

    @Test void negativeReadbackAndSafetyChangesCannotBecomeVerifiedSuccess() {
        supported(car,"CLIMATE_TARGET_C",22,23);
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22)));
        String app=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now())).get("id");
        settings.applying(session,app,new PersonalSettings.Gate(true,true,Instant.now()));
        var result=settings.readback(session,app,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",
            Map.of("CLIMATE_TARGET_C",22),Map.of("CLIMATE_TARGET_C","READ_FAILED"),true,true,Instant.now()));
        assertEquals("FAILED",result.get("state"));
        assertEquals("READ_FAILED",field(result,"CLIMATE_TARGET_C").get("status"));
        String unsafe=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now())).get("id");
        settings.applying(session,unsafe,new PersonalSettings.Gate(true,true,Instant.now()));
        var cancelled=settings.readback(session,unsafe,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",
            Map.of("CLIMATE_TARGET_C",22),null,true,false,Instant.now()));
        assertEquals("CANCELLED",cancelled.get("state"));
        assertEquals("UNSAFE_OPERATION",((Map<?,?>)cancelled.get("detail")).get("cancelReason"));
    }

    @Test void cancellationIsRecoverableAndLateCallbackCannotOverwriteIt() {
        supported(car,"CLIMATE_TARGET_C",22);
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22)));
        String app=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now())).get("id");
        settings.applying(session,app,new PersonalSettings.Gate(true,true,Instant.now()));
        assertEquals("CANCELLED",settings.cancel(session,app,new PersonalSettings.Cancel("POWER_OFF",Instant.now())).get("state"));
        assertEquals("CANCELLED",settings.readback(session,app,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22))).get("state"));
        assertEquals("POWER_OFF",((Map<?,?>)settings.application(session,app).get("detail")).get("cancelReason"));
        assertFalse(((List<?>)settings.applications(session,50).get("applications")).isEmpty());
        String pending=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now())).get("id");
        settings.end(session,Map.of());
        assertEquals("CANCELLED",settings.application(session,pending).get("state"));
    }

    @Test void legacyPendingActivityStillPreservesItsSkippedField() {
        supported(car,"CLIMATE_TARGET_C",22);
        String app=RecordData.uuid();
        data.db.update("INSERT INTO setting_activity(id,session_id,created_at,state,detail) VALUES(?,?,?,'APPLYING',?)",
            app,session,RecordData.stamp(Instant.now()),pleos.nextkey.common.Json.write(Map.of(
                "desired",Map.of("CLIMATE_TARGET_C",22),"skipped",List.of("CLIMATE_FAN_LEVEL"),
                "applyingAt",Instant.now().minusSeconds(1))));
        var result=settings.readback(session,app,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22)));
        assertEquals("PARTIAL",result.get("state"));
    }

    @Test void chainedSubsetRetriesKeepEveryRemainingFailureAndOriginalMemoryReference() {
        supported(car,"CLIMATE_TARGET_C",22);
        supported(car,"CLIMATE_FAN_LEVEL",1,2);
        supported(car,"SEAT_MEMORY_SLOT",1);
        var reference=new PersonalSettings.MemoryReference(car,1,
            "VERIFIED_EXISTING_VEHICLE_MEMORY_REFERENCE",BigDecimal.valueOf(48));
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",
            Map.of("CLIMATE_TARGET_C",22,"CLIMATE_FAN_LEVEL",1,"SEAT_FORE_AFT_POSITION",48),
            null,reference,null));
        String first=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now())).get("id");
        settings.applying(session,first,new PersonalSettings.Gate(true,true,Instant.now()));
        settings.readback(session,first,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",
            Map.of("CLIMATE_TARGET_C",22,"CLIMATE_FAN_LEVEL",2,"SEAT_FORE_AFT_POSITION",49)));

        // A later temperature-only save must not rewrite the historical application's reference.
        settings.save(session,new PersonalSettings.Observation(Instant.now(),"DEV_SIMULATOR",Map.of("CLIMATE_TARGET_C",22)));
        String fan=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),
            null,first,List.of("CLIMATE_FAN_LEVEL"),null)).get("id");
        settings.applying(session,fan,new PersonalSettings.Gate(true,true,Instant.now()));
        var stillPartial=settings.readback(session,fan,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",
            Map.of("CLIMATE_FAN_LEVEL",1)));
        assertEquals("PARTIAL",stillPartial.get("state"));

        String seat=(String)settings.request(session,new PersonalSettings.Gate(true,true,Instant.now(),
            null,fan,List.of("SEAT_MEMORY_SLOT"),null)).get("id");
        var desired=(Map<?,?>)((Map<?,?>)settings.application(session,seat).get("detail")).get("desired");
        assertEquals(java.util.Set.of("SEAT_MEMORY_SLOT"),desired.keySet());
        settings.applying(session,seat,new PersonalSettings.Gate(true,true,Instant.now()));
        var complete=settings.readback(session,seat,new PersonalSettings.Readback(Instant.now(),"DEV_SIMULATOR",
            Map.of("SEAT_FORE_AFT_POSITION",48)));
        assertEquals("APPLIED",complete.get("state"));
        assertEquals("MATCHED",field(complete,"CLIMATE_TARGET_C").get("status"));
        assertEquals("MATCHED",field(complete,"CLIMATE_FAN_LEVEL").get("status"));
    }

    @Test void enumeratedValuesCannotBypassAnExplicitWritableRange() {
        var error=assertThrows(ApiError.class,()->settings.capability(car,new PersonalSettings.Capability(
            "CLIMATE_TARGET_C",List.of(BigDecimal.valueOf(99)),"READ_WRITE","ROW_1_LEFT",
            BigDecimal.valueOf(18),BigDecimal.valueOf(28),BigDecimal.valueOf(.5))));
        assertEquals("INVALID_SETTING_RANGE",error.code());
    }

    private Map<?,?> field(Map<String,Object> result,String name) {
        return (Map<?,?>)((Map<?,?>)((Map<?,?>)result.get("detail")).get("fieldResults")).get(name);
    }
}
