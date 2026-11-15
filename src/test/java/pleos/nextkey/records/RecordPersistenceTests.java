package pleos.nextkey.records;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    @Autowired RecordRequests requests;
    @Autowired PersonalSettings settings;
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
}
