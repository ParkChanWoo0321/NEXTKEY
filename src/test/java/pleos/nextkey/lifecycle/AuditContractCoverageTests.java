package pleos.nextkey.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.Json;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Screen-contract regression coverage, isolated from the normal integration suite by explicit opt-in. */
@EnabledIfEnvironmentVariable(named="NEXTKEY_SPEC_AUDIT", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AuditContractCoverageTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    private final HttpClient http=HttpClient.newHttpClient();
    private String customer, vehicle, reservation, session, token;
    private Instant due;

    @BeforeEach void fixture() throws Exception {
        assertEquals("nextkey_test", db.queryForObject("SELECT DATABASE()", String.class));
        customer=account("CUSTOMER");
        vehicle=account("VEHICLE");
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'AUDIT-FICTIONAL','BATTERY',?)",vehicle,vehicle);
        reservation=UUID.randomUUID().toString();
        session=UUID.randomUUID().toString();
        due=Instant.now().plusSeconds(3600);
        db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) VALUES(?,?,?,'CONFIRMED',?,?)",
                reservation,customer,vehicle,utc(Instant.now().minusSeconds(60)),utc(due));
        var battery=Map.of("value",78,"unit","%","status","VALID","source","DEV_SIMULATOR","collectedAt",Instant.now().toString());
        db.update("INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,start_json) VALUES(?,?,?,?,'ACTIVE',?,?)",
                session,reservation,customer,vehicle,utc(Instant.now().minusSeconds(30)),Json.write(Map.of("battery",battery)));
        token=token(vehicle,session);
    }

    @Test void pendingExtensionMustBeReadableByTheCurrentVehicleSession() throws Exception {
        extension("PENDING_APPROVAL");
        // Keeping the customer's complete fee/consent history private is correct. The vehicle still needs a sanitized status.
        assertEquals(403,get("/reservations/"+reservation+"/extensions").statusCode());
        var result=get("/sessions/"+session+"/contract-status");
        assertEquals(200,result.statusCode());
        assertEquals("PENDING_APPROVAL",section(result,"extension").get("status"));
        assertEquals(Set.of("status","effectiveEndAt","requestedEndAt","decisionAt"),section(result,"extension").keySet());
        assertFalse(result.body().contains("KRW"));
        assertFalse(result.body().contains("quote_amount"));
        assertFalse(result.body().contains(customer));
    }

    @Test void contractStatusDistinguishesRejectedExtensionFromNoExtensionRequest() throws Exception {
        assertEquals("NONE",section(get("/sessions/"+session+"/contract-status"),"extension").get("status"));
        extension("REJECTED");
        var result=get("/sessions/"+session+"/contract-status");
        assertEquals(200,result.statusCode());
        assertEquals("REJECTED",section(result,"extension").get("status"));
        assertNotNull(section(result,"extension").get("decisionAt"));
        assertEquals(Times.utc(db.queryForObject("SELECT confirmed_end_at FROM reservation WHERE id=?",Object.class,reservation)),
                Instant.parse((String)section(result,"extension").get("effectiveEndAt")));
    }

    @Test void vehicleStatusTracksTheCompleteExtensionApprovalFlowWithoutFeeHistory() throws Exception {
        String customerToken=token(customer,null),operatorToken=token(account("OPERATOR"),null);
        String eid=UUID.randomUUID().toString(),path="/extensions/"+eid;
        Instant requested=due.plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var creation=post("/reservations/"+reservation+"/extensions",customerToken,
                Map.of("requestId",eid,"requestedEndAt",requested.toString()));
        assertEquals(200,creation.statusCode(),creation.body());
        String vehiclePath="/sessions/"+session+"/contract-status";
        assertEquals("REQUESTED",section(get(vehiclePath),"extension").get("status"));
        var fee=Map.of("amount",1350,"currency","KRW");
        assertEquals(200,post(path+"/quote",operatorToken,fee).statusCode());
        assertEquals("WAITING_CONSENT",section(get(vehiclePath),"extension").get("status"));
        assertEquals(200,post(path+"/agree",customerToken,fee).statusCode());
        var pending=section(get(vehiclePath),"extension");
        assertEquals("PENDING_APPROVAL",pending.get("status"));
        assertNotEquals(requested,Instant.parse((String)pending.get("effectiveEndAt")));
        var decision=post(path+"/decision",operatorToken,Map.of("approved",true,"note","CUSTOMER-PRIVATE-NOTE"));
        assertEquals(200,decision.statusCode(),decision.body());
        var approved=get(vehiclePath);
        assertEquals("APPROVED",section(approved,"extension").get("status"));
        assertEquals(requested,Instant.parse((String)section(approved,"extension").get("effectiveEndAt")));
        assertNotNull(section(approved,"extension").get("decisionAt"));
        assertFalse(approved.body().contains("KRW"));
        assertFalse(approved.body().contains("CUSTOMER-PRIVATE-NOTE"));
    }

    @Test void companyPreapprovalCanBeReadBeforeClosingButFinalApprovalRequiresCleanup() throws Exception {
        String operator=account("OPERATOR");
        String next=linkedReservation(operator,customer),operatorToken=token(operator,null);
        var change=Map.of("newReservationId",next);
        String path="/operator/sessions/"+session+"/replacement";
        assertEquals(403,post(path+"/preapprove",token,change).statusCode());
        assertEquals(403,post(path+"/preapprove",token(customer,null),change).statusCode());
        assertEquals("PENDING",section(get("/sessions/"+session+"/contract-status"),"handoff").get("status"));
        var preapproval=post(path+"/preapprove",operatorToken,change);
        assertEquals(200,preapproval.statusCode(),preapproval.body());
        assertEquals("PREAPPROVED",Json.read(preapproval.body()).get("status"));
        assertEquals(200,post(path+"/preapprove",operatorToken,change).statusCode());
        var result=get("/sessions/"+session+"/contract-status");
        assertEquals(200,result.statusCode());
        var handoff=section(result,"handoff");
        assertEquals(true,handoff.get("priorApproval"));
        assertEquals(true,handoff.get("mayCloseCurrentSession"));
        assertEquals(false,handoff.get("newAuthenticationReady"));
        assertEquals(true,handoff.get("vehicleChanged"));
        assertFalse(result.body().contains(next));
        assertEquals(409,post(path+"/approve",operatorToken,change).statusCode());
        db.update("UPDATE rental_session SET state='ENDED',cleanup_state='PENDING',ended_at=UTC_TIMESTAMP(6) WHERE id=?",session);
        assertEquals(200,get("/sessions/"+session+"/contract-status").statusCode());
        assertEquals(409,post(path+"/approve",operatorToken,change).statusCode());
        db.update("UPDATE rental_session SET cleanup_state='COMPLETE' WHERE id=?",session);
        assertEquals(401,get("/sessions/"+session+"/contract-status").statusCode());
        assertEquals(200,post(path+"/approve",operatorToken,change).statusCode());
        var recovered=get(path,operatorToken);
        assertEquals(200,recovered.statusCode(),recovered.body());
        assertEquals("READY_FOR_NEW_AUTH",Json.read(recovered.body()).get("status"));
        assertEquals(true,Json.read(recovered.body()).get("newAuthenticationReady"));
        assertEquals(next,Json.read(recovered.body()).get("newReservationId"));
    }

    @Test void expiredPreparedReplacementCannotBecomeApprovedAfterOldCleanup() throws Exception {
        String operator=account("OPERATOR"),operatorToken=token(operator,null);
        String next=linkedReservation(operator,customer);
        String path="/operator/sessions/"+session+"/replacement";
        var change=Map.of("newReservationId",next);
        assertEquals(200,post(path+"/preapprove",operatorToken,change).statusCode());
        db.update("UPDATE rental_session SET state='ENDED',cleanup_state='COMPLETE',ended_at=UTC_TIMESTAMP(6) WHERE id=?",session);
        db.update("UPDATE reservation SET confirmed_end_at=? WHERE id=?",utc(Instant.now().minusSeconds(1)),next);

        var denied=post(path+"/approve",operatorToken,change);
        assertEquals(409,denied.statusCode(),denied.body());
        assertEquals("NEW_RESERVATION_UNAVAILABLE",Json.read(denied.body()).get("code"));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM session_replacement WHERE old_session_id=?",String.class,session));
        assertNull(db.queryForObject("SELECT approved_at FROM session_replacement WHERE old_session_id=?",Object.class,session));
    }

    @Test void futureReplacementCanBeApprovedAndItsAcceptedDecisionRemainsReplayable() throws Exception {
        String operator=account("OPERATOR"),operatorToken=token(operator,null);
        String next=linkedReservation(operator,customer);
        String path="/operator/sessions/"+session+"/replacement";
        var change=Map.of("newReservationId",next);
        db.update("UPDATE reservation SET starts_at=? WHERE id=?",utc(Instant.now().plusSeconds(60)),next);
        db.update("UPDATE rental_session SET state='ENDED',cleanup_state='COMPLETE',ended_at=UTC_TIMESTAMP(6) WHERE id=?",session);
        var approved=post(path+"/approve",operatorToken,change);
        assertEquals(200,approved.statusCode(),approved.body());
        var waiting=Json.read(get(path,operatorToken).body());
        assertEquals("WAITING_FOR_START",waiting.get("status"));
        assertEquals(false,waiting.get("newAuthenticationReady"));

        db.update("UPDATE reservation SET starts_at=?,confirmed_end_at=? WHERE id=?",
                utc(Instant.now().minusSeconds(60)),utc(Instant.now().minusSeconds(1)),next);
        var replay=post(path+"/approve",operatorToken,change);
        assertEquals(200,replay.statusCode(),replay.body());
        assertEquals(Json.read(approved.body()),Json.read(replay.body()));
        assertEquals("UNAVAILABLE",Json.read(get(path,operatorToken).body()).get("status"));
    }

    @Test void retryingReplacementPreparationAfterLostResponseMustRecoverTheCreatedReservation() throws Exception {
        String operator=account("OPERATOR"),operatorToken=token(operator,null);
        String newVehicle=account("VEHICLE");
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'AUDIT-NEW-CAR','BATTERY',?)",newVehicle,newVehicle);
        var body=Map.of("accountId",customer,"vehicleId",newVehicle,"startsAt",Instant.now().minusSeconds(20).toString(),
                "confirmedEndAt",due.toString());
        String path="/operator/sessions/"+session+"/replacement-reservations";
        var first=post(path,operatorToken,body);
        assertEquals(200,first.statusCode(),first.body());
        var retry=post(path,operatorToken,body);
        assertEquals(200,retry.statusCode(),
                "After a committed response is lost, retrying the same replacement preparation must return the original reservation; actual body="+retry.body());
        assertEquals(Json.read(first.body()),Json.read(retry.body()));
        String next=(String)Json.read(first.body()).get("id");
        db.update("UPDATE reservation SET status='CANCELLED' WHERE id=?",next);
        var lateRetry=post(path,operatorToken,body);
        assertEquals(200,lateRetry.statusCode(),lateRetry.body());
        assertEquals(Json.read(first.body()),Json.read(lateRetry.body()),"A response retry recovers the immutable original response, not a changed reservation");
        var changed=new LinkedHashMap<String,Object>(body);
        changed.put("confirmedEndAt",due.plusSeconds(60).toString());
        assertEquals(409,post(path,operatorToken,changed).statusCode());
        var recovered=get("/operator/sessions/"+session+"/replacement",operatorToken);
        assertEquals(next,Json.read(recovered.body()).get("newReservationId"));
        assertEquals("UNAVAILABLE",Json.read(recovered.body()).get("status"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM session_replacement WHERE old_session_id=?",Integer.class,session));
    }

    @Test void sessionContractStatusIsScopedAndDoesNotRevealNextRenterIdentity() throws Exception {
        String nextCustomer=account("CUSTOMER"),operator=account("OPERATOR");
        String next=linkedReservation(operator,nextCustomer);
        String path="/sessions/"+session+"/contract-status";
        var own=get(path);
        assertEquals(200,own.statusCode());
        assertEquals(true,section(own,"handoff").get("userChanged"));
        assertFalse(own.body().contains(nextCustomer));
        assertFalse(own.body().contains(next));
        assertEquals(200,get(path,token(customer,null)).statusCode());
        assertEquals(403,get(path,token(nextCustomer,null)).statusCode());
        assertEquals(403,get(path,token(vehicle,null)).statusCode());
        assertEquals(403,get(path,token(operator,null)).statusCode());
        assertEquals(403,get("/operator/sessions/"+session+"/replacement").statusCode());
        assertEquals(403,get("/operator/sessions/"+session+"/replacement",token(customer,null)).statusCode());
        String otherSession=UUID.randomUUID().toString();
        String otherVehicle=db.queryForObject("SELECT vehicle_id FROM reservation WHERE id=?",String.class,next);
        db.update("INSERT INTO rental_session(id,reservation_id,account_id,vehicle_id,state,started_at,start_json) VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),'{}')",
                otherSession,next,nextCustomer,otherVehicle);
        assertEquals(403,get("/sessions/"+otherSession+"/contract-status").statusCode());
    }

    @Test void simultaneousDuplicatePreparationsShareOneReservation() throws Exception {
        String operatorToken=token(account("OPERATOR"),null),newVehicle=account("VEHICLE");
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'AUDIT-NEW-CAR','BATTERY',?)",newVehicle,newVehicle);
        var body=Map.of("accountId",customer,"vehicleId",newVehicle,"startsAt",Instant.now().minusSeconds(20).toString(),
                "confirmedEndAt",due.toString());
        String path="/operator/sessions/"+session+"/replacement-reservations";
        var start=new CyclicBarrier(3);
        var workers=Executors.newFixedThreadPool(2);
        try {
            Callable<HttpResponse<String>> prepare=()->{start.await(5,TimeUnit.SECONDS);return post(path,operatorToken,body);};
            var one=workers.submit(prepare);
            var two=workers.submit(prepare);
            start.await(5,TimeUnit.SECONDS);
            var first=one.get(20,TimeUnit.SECONDS);
            var second=two.get(20,TimeUnit.SECONDS);
            assertEquals(200,first.statusCode(),first.body());
            assertEquals(200,second.statusCode(),second.body());
            assertEquals(Json.read(first.body()),Json.read(second.body()));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM session_replacement WHERE old_session_id=?",Integer.class,session));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void replacementNeedsFinalApprovalAndThenNewAuthenticationKeepsTheOldSnapshot() throws Exception {
        String operator=account("OPERATOR"),operatorToken=token(operator,null);
        String next=linkedReservation(operator,customer);
        String nextVehicle=db.queryForObject("SELECT vehicle_id FROM reservation WHERE id=?",String.class,next);
        String device=token(nextVehicle,null),owner=token(customer,null);
        var original=db.queryForMap("SELECT started_at,start_json FROM rental_session WHERE id=?",session);
        var created=post("/vehicle/challenges",device,Map.of("method","QR"));
        assertEquals(200,created.statusCode(),created.body());
        var qr=Json.read(created.body());
        String path="/auth/challenges/"+qr.get("id");
        assertEquals(200,post(path+"/qr",owner,Map.of("qrToken",qr.get("qrToken"),"reservationId",next)).statusCode());
        var proof=Map.of("code",System.getenv("NEXTKEY_DEV_OTP"));
        var denied=post(path+"/verify",owner,proof);
        assertEquals(409,denied.statusCode(),denied.body());
        assertEquals("REPLACEMENT_PENDING",Json.read(denied.body()).get("code"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM rental_session WHERE reservation_id=?",Integer.class,next));

        var replacement=Map.of("newReservationId",next);
        String approval="/operator/sessions/"+session+"/replacement";
        assertEquals(200,post(approval+"/preapprove",operatorToken,replacement).statusCode());
        db.update("UPDATE rental_session SET state='ENDED',cleanup_state='COMPLETE',ended_at=UTC_TIMESTAMP(6) WHERE id=?",session);
        denied=post(path+"/verify",owner,proof);
        assertEquals(409,denied.statusCode(),denied.body());
        assertEquals("REPLACEMENT_PENDING",Json.read(denied.body()).get("code"));
        assertEquals(200,post(approval+"/approve",operatorToken,replacement).statusCode());

        var verified=post(path+"/verify",owner,proof);
        assertEquals(200,verified.statusCode(),verified.body());
        String nextSession=(String)Json.read(verified.body()).get("sessionId");
        assertNotEquals(session,nextSession);
        var claim=post(path+"/claim",device,Map.of("pollSecret",qr.get("pollSecret")));
        assertEquals(200,claim.statusCode(),claim.body());
        assertEquals(nextSession,Json.read(claim.body()).get("sessionId"));
        assertEquals(original,db.queryForMap("SELECT started_at,start_json FROM rental_session WHERE id=?",session));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rental_session WHERE reservation_id=?",Integer.class,next));
    }

    private String linkedReservation(String operator,String nextCustomer) {
        String nextVehicle=account("VEHICLE"),next=UUID.randomUUID().toString();
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'AUDIT-REPLACEMENT','BATTERY',?)",nextVehicle,nextVehicle);
        db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) VALUES(?,?,?,'CONFIRMED',?,?)",
                next,nextCustomer,nextVehicle,utc(Instant.now().minusSeconds(60)),utc(due));
        db.update("INSERT INTO session_replacement(new_reservation_id,old_session_id,status,requested_by,requested_at) VALUES(?,?,'PENDING',?,UTC_TIMESTAMP(6))",
                next,session,operator);
        return next;
    }

    private static Map<?,?> section(HttpResponse<String> response,String field) { return (Map<?,?>)Json.read(response.body()).get(field); }

    private void extension(String status) {
        String operator=account("OPERATOR");
        db.update("""
                INSERT INTO rental_extension(id,reservation_id,account_id,requested_at,previous_end_at,requested_end_at,status,
                    quote_amount,currency,quoted_by,quoted_at,agreed_at,decided_by,decided_at)
                VALUES(?,?,?,UTC_TIMESTAMP(6),?,?,?,1000,'KRW',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?)
                """,UUID.randomUUID().toString(),reservation,customer,utc(due),utc(due.plusSeconds(3600)),status,operator,
                "REJECTED".equals(status)?operator:null,"REJECTED".equals(status)?utc(Instant.now()):null);
    }
    private String account(String role) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO account(id,role,username,password_hash) VALUES(?,?,?,'AUDIT-NONLOGIN-FIXTURE')",id,role,id);
        return id;
    }
    private String token(String account,String currentSession) throws Exception {
        byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
        String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        db.update("INSERT INTO auth_token(token_hash,account_id,session_id,expires_at,dev_issued) VALUES(?,?,?,?,TRUE)",
                hash,account,currentSession,utc(Instant.now().plusSeconds(3600)));
        return raw;
    }
    private HttpResponse<String> get(String path) throws Exception {
        return get(path,token);
    }
    private HttpResponse<String> get(String path,String auth) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path))
                .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+auth).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path,String auth,Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path))
                .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+auth).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
}
