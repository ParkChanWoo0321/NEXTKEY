package pleos.nextkey.records;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
class VehicleEventPersistenceTests {
    @Autowired RecordData data;
    @Autowired VehicleEvents events;
    @LocalServerPort int port;
    String id;
    String car;
    String owner;

    @BeforeEach void sampleSession() {
        id=RecordData.uuid();
        car=RecordData.uuid();
        String customer=RecordData.uuid(), booking=RecordData.uuid();
        owner=customer;
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
    }


    @Test void measurementsKeepCollectionOrderAndRetryIdentity() {
        Instant at=Instant.now();
        var newest=new VehicleEvents.Input(RecordData.uuid(),"MEASUREMENT",at,"DEV_SIMULATOR","BATTERY",
                BigDecimal.valueOf(15),"kWh",null,null,null,null,null,null,null,null,null);
        var older=new VehicleEvents.Input(RecordData.uuid(),"MEASUREMENT",at.minusSeconds(10),"DEV_SIMULATOR","BATTERY",
                BigDecimal.valueOf(70),"%",null,null,null,null,null,null,null,null,null);
        assertEquals("UNIT_MISMATCH",events.accept(id,newest).get("status"));
        assertEquals(true,events.accept(id,newest).get("replayed"));
        events.accept(id,older);
        var rows=events.timeline(id);
        assertEquals(2,rows.size());
        var last=rows.get(rows.size()-1);
        assertEquals(newest.eventId(),last.get("id"));
        assertEquals("UNIT_MISMATCH",last.get("status"));
        assertNull(Json.read(last.get("payload").toString()).get("value"));
        var changed=new VehicleEvents.Input(newest.eventId(),"MEASUREMENT",at,"DEV_SIMULATOR","BATTERY",
                BigDecimal.valueOf(99),"%",null,null,null,null,null,null,null,null,null);
        assertEquals("EVENT_ID_CONFLICT",assertThrows(ApiError.class,()->events.accept(id,changed)).code());
    }

    private String token(String account, String session) throws Exception {
        byte[] raw=new byte[32];
        new SecureRandom().nextBytes(raw);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        data.db.update("INSERT INTO auth_token(token_hash,account_id,session_id,expires_at,dev_issued) VALUES(?,?,?,UTC_TIMESTAMP()+INTERVAL 1 HOUR,TRUE)",
                hash,account,session);
        return token;
    }

    private HttpResponse<String> get(String path,String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+port+"/api/sessions/"+id+path))
                .timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void onlyOwnerReadsRouteAndTransmissionFailureIsRecordedAsGap() throws Exception {
        assertEquals("nextkey_test",data.db.queryForObject("SELECT DATABASE()",String.class));
        String other=RecordData.uuid();
        data.db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,'CUSTOMER','DISABLED')",other,other);
        String ownerToken=token(owner,null), otherToken=token(other,null), sessionToken=token(owner,id);
        assertEquals(200,get("/location/route",ownerToken).statusCode());
        assertEquals(403,get("/location/route",otherToken).statusCode());
        assertEquals(403,get("/location/route",sessionToken).statusCode());
        assertEquals(403,get("/location/consent",otherToken).statusCode());
        var payload=Map.of("eventId",RecordData.uuid(),"kind","GAP","reason","STORAGE_FAILURE",
                "source","DEV_SIMULATOR","collectedAt",Instant.now().toString());
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:"+port+"/api/sessions/"+id+"/events"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+sessionToken)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(payload))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        assertEquals("STORAGE_FAILURE",Json.read(response.body()).get("status"));
        assertEquals(1,((List<?>)events.route(events.timeline(id)).get("gaps")).size());
    }
}
