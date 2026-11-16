package pleos.nextkey.records;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.Json;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit probes for the supplied FINAL_FIXED HTML/PDF contract.
 * Contract regressions for fixes identified by the supplied HTML/PDF audit.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_SPEC_AUDIT",matches="true")
class AuditRecordsScenarioTests {
    @Autowired RecordData data;
    @Autowired VehicleEvents events;
    @LocalServerPort int port;
    String session, customer, vehicle, token;

    @BeforeEach void isolatedFixture() throws Exception {
        assertEquals("nextkey_test",data.db.queryForObject("SELECT DATABASE()",String.class));
        session=RecordData.uuid();customer=RecordData.uuid();vehicle=RecordData.uuid();
        String reservation=RecordData.uuid();
        data.db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,'CUSTOMER','DISABLED')",customer,customer);
        data.db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'AUDIT-EV','BATTERY',?)",vehicle,vehicle);
        data.db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) VALUES(?,?,?,'CONFIRMED',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR)",reservation,customer,vehicle);
        data.db.update("INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,start_json) VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP()-INTERVAL 30 MINUTE,JSON_OBJECT())",session,reservation,customer,vehicle);
        byte[] rawToken=new byte[32];
        new java.security.SecureRandom().nextBytes(rawToken);
        token=Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        data.db.update("INSERT INTO auth_token(token_hash,account_id,session_id,expires_at,dev_issued) VALUES(?,?,?,UTC_TIMESTAMP()+INTERVAL 1 HOUR,TRUE)",hash,customer,session);
    }

    private String grant() {
        return (String)((Map<?,?>)events.consent(session,new VehicleEvents.Consent(true,"v1")).get("latest")).get("id");
    }

    private HttpResponse<String> post(Map<String,Object> body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/sessions/"+session+"/events"))
            .timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void suppliedSdkLocationConfidenceDoesNotNeedInventedMeterAccuracy() throws Exception {
        String consent=grant();Instant at=Instant.now();
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("eventId",RecordData.uuid());body.put("kind","LOCATION");body.put("source","DEV_SIMULATOR");body.put("collectedAt",at.toString());
        body.put("consentId",consent);body.put("permissionGranted",true);body.put("supported",true);body.put("fixAt",at.toString());
        body.put("latitude",37.5665);body.put("longitude",126.978);body.put("confidence",0.85);body.put("qualityAccepted",true);
        // PDF wide pp.109/165 and HTML sampleVehicle() explicitly prohibit relabelling confidence as metres.
        var response=post(body);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals("VALID",Json.read(response.body()).get("status"),
            "A fresh, consented SDK-shaped point should be representable without fabricating accuracyMeters");
    }

    @Test void sdkWarningCodeAcceptedBySnapshotsCanAlsoBeRecordedAsAnEvent() throws Exception {
        var response=post(Map.of("eventId",RecordData.uuid(),"kind","WARNING","source","DEV_SIMULATOR",
            "collectedAt",Instant.now().toString(),"warningCode","washer-fluid"));
        assertEquals(200,response.statusCode(),
            "The supplied HTML WARNING_CATALOG uses washer-fluid/brake-fluid; snapshot warnings also allow hyphens: "+response.body());
        assertEquals("REPORTED",Json.read(response.body()).get("status"));
    }

    @Test void consentChoiceShownInsideTheVehicleAppCanUseTheClaimedSessionToken() throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:"+port+"/api/sessions/"+session+"/location/consent"))
            .timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token)
            .header("Content-Type","application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("granted",true,"policyVersion","v1"))))
            .build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),
            "A05-1 must accept the authenticated vehicle session's consent choice: "+response.body());
    }

    @Test void sixtySecondStationaryIntervalMatchesTheSuppliedHtmlStopCandidateRule() {
        Instant start=Instant.parse("2026-11-11T01:42:00Z");
        var first=Map.<String,Object>of("kind","LOCATION","collected_at",start,"status","VALID","consent_id","same-grant",
            "payload","{\"latitude\":37.5665,\"longitude\":126.978}");
        var next=Map.<String,Object>of("kind","LOCATION","collected_at",start.plusSeconds(60),"status","VALID","consent_id","same-grant",
            "payload","{\"latitude\":37.5665,\"longitude\":126.978}");
        assertEquals(1,((List<?>)events.route(List.of(first,next)).get("estimatedStops")).size(),
            "HTML sampleVehicle() creates a stop candidate after 60000 ms");
    }

    @Test void currentDocumentedWithdrawalPolicyDropsAnOlderQueuedPointAfterWithdrawal() {
        String consent=grant();Instant at=Instant.now();
        var sample=point(consent,at,at,true,true,new BigDecimal("37.5665"),new BigDecimal("126.978"),BigDecimal.TEN);
        events.consent(session,new VehicleEvents.Consent(false,"v1"));
        var ack=events.accept(session,sample);
        assertEquals("NO_CURRENT_GRANT",ack.get("status"));
        assertEquals("{}",data.db.queryForObject("SELECT payload FROM vehicle_event WHERE id=?",String.class,sample.eventId()));
        // Characterisation, not a claimed implementation defect: docs/records.md explicitly chooses this policy.
    }

    @Test void rejectedPositionQualityReasonsNeverPersistCoordinatesAndAnEdgeValidPointDoes() {
        String consent=grant();Instant at=Instant.now();
        var lat=new BigDecimal("37.5665");var lon=new BigDecimal("126.978");
        record Probe(String expected,VehicleEvents.Input input) {}
        var probes=List.of(
            new Probe("PERMISSION_MISSING",point(consent,at,at,false,true,lat,lon,BigDecimal.TEN)),
            new Probe("UNSUPPORTED",point(consent,at,at,true,false,lat,lon,BigDecimal.TEN)),
            new Probe("NO_FIX",point(consent,at,null,true,true,lat,lon,BigDecimal.TEN)),
            new Probe("STALE_FIX",point(consent,at,at.plusSeconds(1),true,true,lat,lon,BigDecimal.TEN)),
            new Probe("INVALID_COORDINATES",point(consent,at,at,true,true,BigDecimal.valueOf(91),lon,BigDecimal.TEN)),
            new Probe("LOW_ACCURACY",point(consent,at,at,true,true,lat,lon,null)),
            new Probe("LOW_ACCURACY",point(consent,at,at,true,true,lat,lon,BigDecimal.valueOf(51))),
            new Probe("VALID",point(consent,at,at,true,true,BigDecimal.valueOf(90),BigDecimal.valueOf(180),BigDecimal.valueOf(50)))
        );
        for(var probe:probes) {
            assertEquals(probe.expected(),events.accept(session,probe.input()).get("status"));
            String stored=data.db.queryForObject("SELECT payload FROM vehicle_event WHERE id=?",String.class,probe.input().eventId());
            assertEquals("VALID".equals(probe.expected()),stored.contains("latitude"));
        }
    }

    private VehicleEvents.Input point(String consent,Instant at,Instant fix,boolean permission,boolean supported,
                                      BigDecimal latitude,BigDecimal longitude,BigDecimal accuracy) {
        return new VehicleEvents.Input(RecordData.uuid(),"LOCATION",at,"DEV_SIMULATOR",null,null,null,null,null,consent,
            permission,supported,fix,latitude,longitude,accuracy);
    }
}
