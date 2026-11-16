package pleos.nextkey;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.Json;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, authenticated principals, the embedded servlet container, Flyway and a fresh MySQL schema. */
@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlFlowTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    final HttpClient http = HttpClient.newHttpClient();
    final String rid = "demo-reservation";
    String owner, other, car, office, sessionToken, sid, endId, replacement;
    Map<String,Object> endBody;
    String sidPath() { return "/sessions/" + sid; }
    String uuid() { return UUID.randomUUID().toString(); }
    String now() { return Instant.now().toString(); }

    HttpResponse<byte[]> request(String method,String path,String token,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path));
        if (token!=null) builder.header("Authorization","Bearer "+token);
        var publisher=body==null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body));
        return http.send(builder.header("Content-Type","application/json").method(method,publisher).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    String call(String method,String path,String token,Object body,int... statuses) throws Exception {
        var response=request(method,path,token,body);
        String text=new String(response.body(),StandardCharsets.UTF_8);
        assertTrue(Arrays.stream(statuses).anyMatch(i -> i==response.statusCode()),
                () -> method+" "+path+" => "+response.statusCode()+" ("+safeCode(text)+")");
        return text;
    }
    String safeCode(String text) {
        try { return String.valueOf(Json.read(text).getOrDefault("code","no error code")); }
        catch (Exception e) { return "non-object response"; }
    }
    Map<String,Object> post(String path,String token,Object body) throws Exception {
        return Json.read(call("POST",path,token,body,200,201,202));
    }
    String login(String name) throws Exception {
        return (String) post("/auth/token",null,Map.of("username",name,"password",System.getenv("NEXTKEY_DEV_PASSWORD"))).get("token");
    }
    Map<String,Object> sample(int km, int batteryPercent) {
        return Map.of("odometer",Map.of("source","DEV_SIMULATOR","value",km,"unit","km","status","VALID","collectedAt",now()),
                "battery",Map.of("source","DEV_SIMULATOR","value",batteryPercent,"unit","%","status","VALID","collectedAt",now()));
    }
    Map<String,Object> challenge(String method,String vehicle) throws Exception {
        return post("/vehicle/challenges",vehicle,Map.of("method",method,"snapshot",sample(32230,78)));
    }
    String challengePath(Map<String,Object> challenge) { return "/auth/challenges/"+challenge.get("id"); }
    Map<String,Object> qrBody(Map<String,Object> ch,String reservation) {
        return Map.of("qrToken",ch.get("qrToken"),"reservationId",reservation);
    }
    Map<String,Object> verify(Map<String,Object> ch,String actor) throws Exception {
        return post(challengePath(ch)+"/verify",actor,Map.of("code",System.getenv("NEXTKEY_DEV_OTP")));
    }
    String claim(Map<String,Object> ch,String vehicle) throws Exception {
        return (String) post(challengePath(ch)+"/claim",vehicle,Map.of("pollSecret",ch.get("pollSecret"))).get("token");
    }
    int count(String sql,Object...args) { return db.queryForObject(sql,Integer.class,args); }

    @BeforeAll void accounts() throws Exception {
        assertEquals("nextkey_test",db.queryForObject("SELECT DATABASE()",String.class));
        owner=login("demo-customer"); other=login("demo-customer-2");
        office=login("demo-operator"); car=login("demo-car");
    }

    @Test @Order(1) void schemaMigrationsConstraintsAndRoles() throws Exception {
        var migrations = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        var scripts = Arrays.stream(migrations).map(org.springframework.core.io.Resource::getFilename).toList();
        var applied = db.queryForList("SELECT script FROM flyway_schema_history WHERE success AND version IS NOT NULL",String.class);
        assertFalse(scripts.isEmpty());
        assertEquals(new TreeSet<>(scripts),new TreeSet<>(applied));
        var checkFailure=assertThrows(org.springframework.dao.DataAccessException.class,()->db.update(
                "UPDATE reservation SET confirmed_end_at=starts_at WHERE id=?",rid));
        assertInstanceOf(java.sql.SQLException.class,checkFailure.getMostSpecificCause());
        assertEquals(3819,((java.sql.SQLException)checkFailure.getMostSpecificCause()).getErrorCode());
        assertThrows(DataIntegrityViolationException.class,()->db.update(
                "UPDATE reservation SET vehicle_id='no-such-vehicle' WHERE id=?",rid));
        call("GET","/reservations/"+rid,null,null,401);
        call("GET","/reservations/"+rid,other,null,403);
        call("POST","/auth/token",null,Map.of("username","demo-customer","password","incorrect"),401);
        call("POST","/vehicle/challenges",owner,Map.of("method","QR"),403);
        String destination="/reservations/"+rid+"/destination";
        var bad=Json.read(call("PUT",destination,owner,Map.of("name","가상 목적지","latitude",95,"longitude",127),200));
        var place=(Map<?,?>)bad.get("destination");
        assertEquals("UNKNOWN",place.get("coordinateStatus"));
        assertNull(place.get("latitude"));
        assertNull(place.get("longitude"));
        call("PUT",destination,other,Map.of("latitude",37,"longitude",127),403);
        call("PUT",destination,owner,Map.of("name","가상 목적지","latitude",37,"longitude",127),200);
        call("PUT","/operator/reservations/"+rid+"/return-place",owner,Map.of("name","forbidden"),403);
    }

    @Test @Order(2) void qrExpiryReuseAndOneConcurrentStart() throws Exception {
        var expired=challenge("QR",car);
        db.update("UPDATE auth_challenge SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE WHERE id=?",expired.get("id"));
        call("POST",challengePath(expired)+"/qr",owner,qrBody(expired,rid),400,409,410);
        var qr=challenge("QR",car);
        post(challengePath(qr)+"/qr",owner,qrBody(qr,rid));
        call("POST",challengePath(qr)+"/qr",owner,qrBody(qr,rid),409,410);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> verify=()->request("POST",challengePath(qr)+"/verify",owner,
                    Map.of("code",System.getenv("NEXTKEY_DEV_OTP"))).statusCode();
            var results=pool.invokeAll(List.of(verify,verify));
            var codes=List.of(results.get(0).get(),results.get(1).get());
            assertTrue(codes.contains(200),codes::toString);
            assertTrue(codes.stream().allMatch(n->Set.of(200,409,410).contains(n)),codes::toString);
        } finally { pool.shutdownNow(); }
        assertEquals(1,count("SELECT COUNT(*) FROM rental_session WHERE reservation_id=? AND state='ACTIVE'",rid));
        sid=db.queryForObject("SELECT id FROM rental_session WHERE reservation_id=?",String.class,rid);
        sessionToken=claim(qr,car);
        assertEquals(0,count("SELECT COUNT(*) FROM location_consent WHERE session_id=?",sid));
        assertEquals(32230,count("SELECT JSON_EXTRACT(start_json,'$.odometer.value') FROM rental_session WHERE id=?",sid));
    }

    @Test @Order(3) void restartFailedThenSuccessfulReauthenticationPreservesStart() throws Exception {
        String start=db.queryForObject("SELECT start_json FROM rental_session WHERE id=?",String.class,sid);
        assertEquals(Map.of("state","ACTIVE","requiresAuthentication",true),
                Json.read(call("GET","/vehicle/recovery",car,null,200)));
        call("GET",sidPath()+"/return-guide",car,null,403);
        var firstGuide=Json.read(call("GET",sidPath()+"/return-guide",sessionToken,null,200));
        var persisted=db.queryForObject("SELECT started_at FROM rental_session WHERE id=?",
                java.time.LocalDateTime.class,sid).toInstant(java.time.ZoneOffset.UTC);
        assertEquals(persisted,Instant.parse((String)firstGuide.get("startedAt")));
        assertEquals(sid,firstGuide.get("sessionId"));
        assertEquals(rid,firstGuide.get("reservationId"));
        assertEquals("demo-car",firstGuide.get("vehicleId"));
        assertEquals("ACTIVE",firstGuide.get("state"));
        assertEquals(firstGuide.get("state"),firstGuide.get("sessionState"));
        assertFalse(Instant.parse((String)firstGuide.get("asOf")).isBefore(persisted));
        String intakePath=sidPath()+"/damage/vehicle?view=handover";
        var intake=Json.read(call("GET",intakePath,sessionToken,null,200));
        assertEquals(firstGuide.get("startedAt"),intake.get("asOf"));
        assertEquals("UNAVAILABLE",intake.get("availability"));
        assertEquals("UNAVAILABLE",intake.get("impactAvailability"));
        assertEquals(List.of(),intake.get("records"));
        call("GET",intakePath,car,null,403);
        call("GET",intakePath,other,null,403);
        var direct=challenge("DIRECT",car);
        call("POST",challengePath(direct)+"/direct",car,Map.of("username","demo-customer","password","wrong","reservationId",rid),401);
        call("GET",sidPath()+"/return-guide",sessionToken,null,401,403);
        post(challengePath(direct)+"/direct",car,Map.of("username","demo-customer","password",System.getenv("NEXTKEY_DEV_PASSWORD"),"reservationId",rid));
        assertEquals(sid,verify(direct,car).get("sessionId"));
        sessionToken=claim(direct,car);
        assertEquals(start,db.queryForObject("SELECT start_json FROM rental_session WHERE id=?",String.class,sid));
        var recovered=Json.read(call("GET",sidPath()+"/return-guide",sessionToken,null,200));
        for (String key : List.of("startedAt","sessionId","reservationId","vehicleId")) {
            assertEquals(firstGuide.get(key),recovered.get(key),key);
        }
    }

    @Test @Order(4) void locationWithdrawalRejectsNewCoordinatesAndReplayIsIdempotent() throws Exception {
        String consentPath=sidPath()+"/location/consent";
        var state=Json.read(call("PUT",consentPath,owner,Map.of("granted",true,"policyVersion","v1"),200));
        Object consentId=((Map<?,?>)state.get("latest")).get("id");
        var event=new HashMap<String,Object>(Map.of("eventId",uuid(),"kind","LOCATION","source","DEV_SIMULATOR",
                "permissionGranted",true,"supported",true,"consentId",consentId,"latitude",37.1234,"longitude",127.1234,"accuracyMeters",8));
        event.put("fixAt",now()); event.put("collectedAt",now());
        assertEquals("VALID",post(sidPath()+"/events",sessionToken,event).get("status"));
        assertEquals(true,post(sidPath()+"/events",sessionToken,event).get("replayed"));
        call("PUT",consentPath,owner,Map.of("granted",false,"policyVersion","v1"),200);
        event.put("eventId",uuid()); event.put("latitude",36.9876);
        assertEquals("NO_CURRENT_GRANT",post(sidPath()+"/events",sessionToken,event).get("status"));
        assertEquals(0,count("SELECT JSON_CONTAINS_PATH(payload,'one','$.latitude') FROM vehicle_event WHERE id=?",event.get("eventId")));
        call("GET",sidPath()+"/location/route",other,null,403);
        assertTrue(call("GET",sidPath()+"/location/route",owner,null,200).contains("37.1234"));
    }

    @Test @Order(5) void pendingRejectedAndApprovedFeesAreSeparateFromOvertime() throws Exception {
        String path="/reservations/"+rid+"/extensions";
        var original=db.queryForObject("SELECT confirmed_end_at FROM reservation WHERE id=?",java.time.LocalDateTime.class,rid);
        var deadline=original.toInstant(java.time.ZoneOffset.UTC).plusSeconds(7200).toString();
        for (boolean approved : List.of(false,true)) {
            String id=uuid();
            post(path,owner,Map.of("requestId",id,"requestedEndAt",deadline));
            var quote=Map.of("amount",1000,"currency","KRW");
            post("/extensions/"+id+"/quote",office,quote);
            call("POST","/extensions/"+id+"/agree",other,quote,403);
            post("/extensions/"+id+"/agree",owner,quote);
            call("POST","/extensions/"+id+"/decision",owner,Map.of("approved",true),403);
            assertEquals(original,db.queryForObject("SELECT confirmed_end_at FROM reservation WHERE id=?",java.time.LocalDateTime.class,rid));
            post("/extensions/"+id+"/decision",office,Map.of("approved",approved));
            assertEquals(approved?"APPROVED":"REJECTED",db.queryForObject("SELECT status FROM rental_extension WHERE id=?",String.class,id));
            assertEquals(approved,db.queryForObject("SELECT confirmed_end_at FROM reservation WHERE id=?",java.time.LocalDateTime.class,rid).isAfter(original));
        }
    }

    @Test @Order(6) void safeFinalConfirmationAndRecoverableCleanup() throws Exception {
        replacement=(String)post("/operator/reservations",office,Map.of("accountId","demo-customer-2","vehicleId","demo-car-2",
                "startsAt",Instant.now().minusSeconds(60).toString(),"confirmedEndAt",Instant.now().plusSeconds(3600).toString())).get("id");
        var change=Map.of("newReservationId",replacement);
        post("/operator/sessions/"+sid+"/replacement",office,change);
        call("POST","/operator/sessions/"+sid+"/replacement/approve",office,change,409);
        var secondCar=login("demo-car-2");
        var premature=challenge("QR",secondCar);
        post(challengePath(premature)+"/qr",other,qrBody(premature,replacement));
        assertEquals("REPLACEMENT_PENDING",safeCode(call("POST",challengePath(premature)+"/verify",other,
                Map.of("code",System.getenv("NEXTKEY_DEV_OTP")),409)));
        // Advance the fixture's contract clock without ending its app session.
        db.update("UPDATE reservation SET confirmed_end_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",rid);
        var guide=Json.read(call("GET",sidPath()+"/return-guide",sessionToken,null,200));
        assertEquals(true,guide.get("overdue"));
        assertEquals("ACTIVE",guide.get("sessionState"));
        var activeState=Json.read(call("GET",sidPath()+"/state",sessionToken,null,200));
        assertTrue(activeState.containsKey("startSnapshot"));
        assertEquals("NOT_ENDED",((Map<?,?>)activeState.get("distanceKm")).get("status"));
        call("GET",sidPath()+"/state",car,null,403);
        call("GET",sidPath()+"/state",owner,null,403);
        post(sidPath()+"/damage",owner,Map.of("category","DENT","description","private-end-summary-note","discoveredAt",now(),
                "parked",true,"safetyObservedAt",now()));
        endId=uuid();
        String eventId=uuid(), captured=now();
        post(sidPath()+"/events",sessionToken,Map.of("eventId",uuid(),"kind","WARNING","source","DEV_SIMULATOR",
                "collectedAt",captured,"warningCode","BRAKE_LOW"));
        post(sidPath()+"/events",sessionToken,Map.of("eventId",uuid(),"kind","SYNC","source","DEV_SIMULATOR",
                "collectedAt",captured,"reason","OFFLINE","intervalId",uuid(),"intervalKind","NETWORK",
                "from",captured,"to",captured));
        var safety=Map.of("parked",true,"interactionAllowed",true,"collectedAt",now(),"source","DEV_SIMULATOR");
        endBody=Map.of("requestId",endId,"confirmed",true,"safety",safety,"snapshot",sample(32412,43),"pendingEventIds",List.of(eventId));
        assertEquals("ACTIVE",post(sidPath()+"/end/cancel",sessionToken,Map.of()).get("state"));
        var cancel=new HashMap<>(endBody); cancel.put("confirmed",false);
        call("POST",sidPath()+"/end",sessionToken,cancel,409,422);
        assertEquals(0,count("SELECT COUNT(*) FROM end_request WHERE session_id=?",sid));
        var unsafe=new HashMap<>(endBody);
        unsafe.put("safety",Map.of("parked",false,"interactionAllowed",true,"collectedAt",now(),"source","DEV_SIMULATOR"));
        call("POST",sidPath()+"/end",sessionToken,unsafe,409);
        assertEquals("END_PENDING",post(sidPath()+"/end",sessionToken,endBody).get("state"));
        assertEquals("END_PENDING",post(sidPath()+"/end",sessionToken,endBody).get("state"));
        assertTrue(call("GET","/vehicle/recovery",car,null,200).contains("END_PENDING"));
        var changed=new HashMap<>(endBody); changed.put("snapshot",sample(33000,43));
        call("POST",sidPath()+"/end",sessionToken,changed,409);
        assertEquals("ENDED",post(sidPath()+"/end/ack",sessionToken,Map.of("requestId",endId)).get("state"));
        assertTrue(call("GET","/vehicle/recovery",car,null,200).contains("CLEANUP_PENDING"));
        assertEquals("ENDED",post(sidPath()+"/end/ack",sessionToken,Map.of("requestId",endId)).get("state"));
        var finalState=Json.read(call("GET",sidPath()+"/state",sessionToken,null,200));
        assertEquals(182,((Number)((Map<?,?>)finalState.get("distanceKm")).get("value")).intValue());
        assertTrue(finalState.containsKey("finalSettings"));
        assertFalse(finalState.containsKey("privateRoute"));
        var details=(Map<?,?>)finalState.get("details");
        assertEquals(1,((List<?>)details.get("warningEvents")).size());
        assertEquals(1,((List<?>)details.get("syncDelays")).size());
        assertTrue(details.containsKey("damage"));
        var endDamage=(Map<?,?>)((Map<?,?>)details.get("damage")).get("allUnverifiedAtEnd");
        assertEquals(1,((Number)endDamage.get("count")).intValue());
        assertEquals(1,((Number)((Map<?,?>)endDamage.get("byCategory")).get("DENT")).intValue());
        String safeDetails=Json.write(details);
        for (String forbidden:List.of("latitude","longitude","description","photoIds","customerId","segments","private-end-summary-note"))
            assertFalse(safeDetails.contains(forbidden),forbidden);
        var waiting=post(sidPath()+"/report/retry",sessionToken,Map.of());
        assertEquals("PENDING",waiting.get("reportState"));
        assertEquals(false,waiting.get("dataComplete"));
        assertFalse(waiting.containsKey("records"));
        assertEquals("PENDING",Json.read(call("GET","/me/reports/"+sid,owner,null,202)).get("reportState"));
        call("GET","/me/reports/"+sid+"/pdf",owner,null,409);
        var clean=Map.of("requestId",endId,"localRecordsSettled",true,"personalCacheCleared",true);
        call("POST",sidPath()+"/cleanup/ack",sessionToken,clean,409);
        post(sidPath()+"/events",sessionToken,Map.of("eventId",eventId,"kind","GAP","source","DEV_SIMULATOR","collectedAt",captured,"reason","POWER_OFF"));
        // A new HTTP read needs only the session credential after the app loses its local end detail cache.
        var recoveredState=Json.read(call("GET",sidPath()+"/state",sessionToken,null,200));
        var recoveredDetails=(Map<?,?>)recoveredState.get("details");
        assertEquals(1,((List<?>)recoveredDetails.get("dataGaps")).size());
        assertEquals(1,((List<?>)recoveredDetails.get("syncDelays")).size());
        assertEquals(details.get("warningEvents"),recoveredDetails.get("warningEvents"));
        assertTrue(call("GET",sidPath()+"/cleanup",sessionToken,null,200).contains("CLEAR_PERSONAL_CACHE_THEN_ACK"));
        // Reproduce a delayed/failed report worker independently of the device acknowledgement.
        db.update("UPDATE rental_session SET report_state='FAILED' WHERE id=?",sid);
        assertEquals("READY",post(sidPath()+"/report/retry",sessionToken,Map.of()).get("reportState"));
        assertEquals("COMPLETE",post(sidPath()+"/cleanup/ack",sessionToken,clean).get("cleanupState"));
        call("GET",sidPath()+"/state",sessionToken,null,401,403);
        call("POST",sidPath()+"/report/retry",sessionToken,Map.of(),401,403);
        assertEquals("COMPLETE",post(sidPath()+"/cleanup/ack",sessionToken,clean).get("cleanupState"));
        call("GET",sidPath()+"/return-guide",sessionToken,null,401,403);
        assertEquals("UNCONFIRMED",db.queryForObject("SELECT return_state FROM rental_session WHERE id=?",String.class,sid));
    }

    @Test @Order(7) void postReturnDamageAndPrivateDownload() throws Exception {
        String original=db.queryForObject("SELECT end_json FROM rental_session WHERE id=?",String.class,sid);
        post(sidPath()+"/damage",owner,Map.of("category","SCRATCH","description","가상 시연 점검 메모","discoveredAt",now()));
        var shared=call("GET",sidPath()+"/damage/vehicle",owner,null,200);
        assertFalse(shared.contains("가상 시연 점검 메모"));
        assertFalse(shared.contains("demo-customer"));
        assertEquals(original,db.queryForObject("SELECT end_json FROM rental_session WHERE id=?",String.class,sid));
        var report="/me/reports/"+sid;
        call("GET",report,other,null,403,404);
        call("GET",report+"/pdf",null,null,401);
        String json="";
        for (int i=0;i<30;i++) {
            json=call("GET",report,owner,null,200,202);
            if (json.contains("182")) break;
            Thread.sleep(200);
        }
        var reportBody=Json.read(json);
        var distance=(Map<?,?>)reportBody.get("distanceKm");
        assertEquals("VALID",distance.get("status"));
        assertEquals(182,((Number)distance.get("value")).intValue());
        assertEquals(-35,((Number)((Map<?,?>)reportBody.get("energyChange")).get("value")).intValue());
        assertEquals(true,reportBody.get("overdue"));
        var pdf=request("GET",report+"/pdf",owner,null);
        assertEquals(200,pdf.statusCode());
        assertTrue(pdf.headers().firstValue("Content-Type").orElse("").startsWith("application/pdf"));
        assertTrue(new String(pdf.body(),0,5,StandardCharsets.US_ASCII).equals("%PDF-"));
        var path=Path.of("build","reports","nextkey-fictional.pdf");
        Files.createDirectories(path.getParent());
        Files.write(path,pdf.body());
        assertEquals(1,count("SELECT COUNT(*) FROM rental_report WHERE session_id=?",sid));
    }

    @Test @Order(8) void replacementStartsASeparateTripWithNewCredentials() throws Exception {
        var body=Map.of("newReservationId",replacement);
        var approval=post("/operator/sessions/"+sid+"/replacement/approve",office,body);
        assertEquals("NEW_AUTHENTICATION",approval.get("nextAction"));
        var secondCar=login("demo-car-2");
        var fresh=challenge("QR",secondCar);
        post(challengePath(fresh)+"/qr",other,qrBody(fresh,replacement));
        var newSession=verify(fresh,other);
        String newId=(String)newSession.get("sessionId");
        assertNotEquals(sid,newId);
        String newToken=claim(fresh,secondCar);
        call("GET",sidPath()+"/return-guide",newToken,null,403);
        assertFalse(call("GET","/me/history",other,null,200).contains(sid));
        assertEquals(1,count("SELECT COUNT(*) FROM rental_session WHERE id=? AND state='ENDED'",sid));
    }
}
