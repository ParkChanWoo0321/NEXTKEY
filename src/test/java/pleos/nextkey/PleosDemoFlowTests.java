package pleos.nextkey;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import pleos.nextkey.common.Json;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** The presenter sets up the run once. Every subsequent action uses a vehicle or current session credential. */
@SpringBootTest(properties="nextkey.report.interval-ms=600000")
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL", matches="true")
class PleosDemoFlowTests {
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    private Map<String,Object> call(String method,String path,String token,Object body,int expected) throws Exception {
        return call(method,path,token,body,expected,null);
    }

    private Map<String,Object> call(String method,String path,String token,Object body,int expected,String requestKey) throws Exception {
        var req=request(HttpMethod.valueOf(method),"/api"+path).contentType("application/json");
        if (token!=null) req.header("Authorization","Bearer "+token);
        if (requestKey!=null) req.header("Idempotency-Key",requestKey);
        if (body!=null) req.content(Json.write(body));
        var reply=mvc.perform(req).andReturn().getResponse();
        var data=Json.read(reply.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected,reply.getStatus(),()->method+" "+path+" "+data.get("code"));
        return data;
    }
    private String now() { return Instant.now().toString(); }
    private Map<String,Object> snapshot(int km) {
        return Map.of("odometer",Map.of("source","DEV_SIMULATOR","unit","km","status","VALID","collectedAt",now(),"value",km));
    }
    private Map<String,Object> gate() {
        return Map.of("observedAt",now(),"permissionGranted",true,"safeToOperate",true);
    }

    @Test void nextRenterCanUseDirectLoginAfterAUserChange() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String operator=(String)call("POST","/auth/token",null,Map.of("username","demo-operator","password",System.getenv("NEXTKEY_DEV_PASSWORD")),200).get("token");
        String password=UUID.randomUUID().toString();
        var run=call("POST","/dev/pleos/runs",operator,Map.of("customerPassword",password),200);
        String runPath="/dev/pleos/runs/"+run.get("runId"),vehicle=(String)run.get("vehicleToken");
        var first=call("POST",runPath+"/bootstrap",vehicle,Map.of(),200);
        String trip="/sessions/"+first.get("sessionId"),token=(String)first.get("token");
        call("POST",runPath+"/preset",vehicle,Map.of("preset","USER_CHANGE_PREAPPROVED"),200);
        String endId=UUID.randomUUID().toString();
        call("POST",trip+"/end",token,Map.of("requestId",endId,"confirmed",true,"pendingEventIds",List.of(),
                "safety",Map.of("parked",true,"interactionAllowed",true,"collectedAt",now(),"source","DEV_SIMULATOR")),200);
        call("POST",trip+"/end/ack",token,Map.of("requestId",endId),200);
        call("POST",trip+"/cleanup/ack",token,Map.of("requestId",endId,"personalCacheCleared",true,"localRecordsSettled",true),200);
        call("POST",runPath+"/preset",vehicle,Map.of("preset","HANDOFF_APPROVED"),200);
        var next=call("POST",runPath+"/replacement-vehicle",vehicle,Map.of(),200);
        assertEquals(run.get("vehicleId"),next.get("vehicleId"));
        assertNotEquals(run.get("directUsername"),next.get("directUsername"));
        assertFalse(Json.write(next).contains(password));
        vehicle=(String)next.get("vehicleToken");
        var challenge=call("POST","/vehicle/challenges",vehicle,Map.of("method","DIRECT"),200);
        String auth="/auth/challenges/"+challenge.get("id");
        var reservation=(Map<?,?>)next.get("reservation");
        var login=call("POST",auth+"/direct",vehicle,Map.of("username",next.get("directUsername"),
                "password",password,"reservationId",reservation.get("id")),200);
        assertEquals("SECOND_FACTOR_REQUIRED",login.get("state"));
        call("POST",auth+"/verify",vehicle,Map.of("code",System.getenv("NEXTKEY_DEV_OTP")),200);
        var claim=call("POST",auth+"/claim",vehicle,Map.of("pollSecret",challenge.get("pollSecret")),200);
        var state=call("GET","/sessions/"+claim.get("sessionId")+"/state",(String)claim.get("token"),null,200);
        assertEquals(reservation.get("id"),state.get("reservationId"));
        assertNotEquals(first.get("sessionId"),state.get("sessionId"));
    }

    @Test void vehicleCompletesTripWithPreparedPhoneDataAndStartsAFreshRun() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String operator=(String)call("POST","/auth/token",null,Map.of("username","demo-operator","password",System.getenv("NEXTKEY_DEV_PASSWORD")),200).get("token");
        var predecessor=call("POST","/dev/pleos/runs",operator,Map.of(),200);
        String predecessorReset="/dev/pleos/runs/"+predecessor.get("runId")+"/reset";
        var setup=Map.of("preset","NORMAL","savedProfile",true,"priorDamage",true);
        var run=call("POST",predecessorReset,operator,setup,200);
        String runPath="/dev/pleos/runs/"+run.get("runId");
        String vehicle=(String)run.get("vehicleToken");
        var session=call("POST",runPath+"/bootstrap",vehicle,Map.of("snapshot",snapshot(32230)),200);
        var resumed=call("POST",runPath+"/bootstrap",vehicle,Map.of("snapshot",snapshot(32231)),200);
        assertEquals(session.get("sessionId"),resumed.get("sessionId"));
        String token=(String)resumed.get("token"), trip="/sessions/"+session.get("sessionId");
        String handover=trip+"/damage/vehicle?view=handover";
        var intake=call("GET",handover,token,null,200);
        assertEquals(1,((List<?>)intake.get("records")).size());
        var incoming=call("POST",runPath+"/preset",vehicle,Map.of("preset","NEW_DAMAGE"),200);
        var replay=call("POST",runPath+"/preset",vehicle,Map.of("preset","NEW_DAMAGE"),200);
        assertEquals(incoming,replay);
        assertEquals(intake,call("GET",handover,token,null,200));
        var gate=new java.util.HashMap<>(gate());
        gate.put("desired",Map.of("CLIMATE_TARGET_C",23));
        var application=call("POST",trip+"/settings/applications",token,gate,200);
        String app=trip+"/settings/applications/"+application.get("id");
        call("POST",app+"/applying",token,gate(),200);
        var readings=Map.of("CLIMATE_TARGET_C",23);
        assertEquals("APPLIED",call("POST",app+"/readback",token,Map.of("observedAt",now(),"source","DEV_SIMULATOR","readings",readings),200).get("state"));
        call("POST",trip+"/settings",token,Map.of("observedAt",now(),"source","DEV_SIMULATOR","readings",readings),200);
        String consentPath=trip+"/location/consent";
        var grant=Map.of("granted",true,"policyVersion","v1","expectedConsentId","");
        String grantKey=UUID.randomUUID().toString();
        var granted=call("PUT",consentPath,token,grant,200,grantKey);
        assertEquals(true,granted.get("collectionAllowed"));
        var grantId=((Map<?,?>)granted.get("latest")).get("id");
        var withdrawn=call("PUT",consentPath,token,Map.of("granted",false,"policyVersion","v1","expectedConsentId",grantId),
                200,UUID.randomUUID().toString());
        assertEquals(false,withdrawn.get("collectionAllowed"));
        assertEquals(withdrawn,call("PUT",consentPath,token,grant,200,grantKey));
        call("PUT",consentPath,token,grant,409,UUID.randomUUID().toString());
        call("POST",runPath+"/preset",vehicle,Map.of("preset","EXTENSION_APPROVED"),200);
        var contract=call("GET",trip+"/contract-status",token,null,200);
        assertEquals("APPROVED",((Map<?,?>)contract.get("extension")).get("status"));
        call("POST",runPath+"/preset",vehicle,Map.of("preset","EXCHANGE_PENDING"),200);
        call("POST",runPath+"/preset",vehicle,Map.of("preset","EXCHANGE_PREAPPROVED"),200);
        var handoff=(Map<?,?>)call("GET",trip+"/contract-status",token,null,200).get("handoff");
        assertEquals("PREAPPROVED",handoff.get("status"));
        call("POST",runPath+"/replacement-vehicle",vehicle,Map.of(),409);

        var armed=call("POST",runPath+"/preset",vehicle,Map.of("preset","REPORT_FAILED"),200);
        assertEquals("ARMED",((Map<?,?>)armed.get("report")).get("faultState"));
        assertEquals("ARMED",((Map<?,?>)call("POST",runPath+"/preset",vehicle,Map.of("preset","REPORT_FAILED"),200).get("report")).get("faultState"));

        String endId=UUID.randomUUID().toString(), lateId=UUID.randomUUID().toString(), collectedAt=now();
        var end=Map.of("requestId",endId,"confirmed",true,
            "safety",Map.of("parked",true,"interactionAllowed",true,"collectedAt",now(),"source","DEV_SIMULATOR"),
            "pendingEventIds",List.of(lateId),"snapshot",snapshot(32412),
            "settings",Map.of("observedAt",now(),"source","DEV_SIMULATOR","readings",readings));
        call("POST",trip+"/end",token,end,200);
        call("POST",trip+"/end/ack",token,Map.of("requestId",endId),200);
        assertEquals("APPLIED",call("GET",app,token,null,200).get("state"));
        var applications=(List<?>)call("GET",trip+"/settings/applications",token,null,200).get("applications");
        assertTrue(applications.stream().anyMatch(value->application.get("id").equals(((Map<?,?>)value).get("id"))));
        call("GET",trip+"/settings",token,null,401);
        call("POST",trip+"/settings",token,Map.of(),401);
        call("POST",trip+"/settings/applications",token,gate(),401);
        call("POST",app+"/readback",token,Map.of(),401);
        assertEquals("DEMO_REPORT_FAULT_REQUIRES_PRE_ACK",call("POST",runPath+"/preset",vehicle,Map.of("preset","REPORT_FAILED"),409).get("code"));
        assertEquals("PENDING",call("POST",trip+"/report/retry",token,Map.of(),200).get("reportState"));
        call("POST",trip+"/events",token,Map.of("eventId",lateId,"kind","GAP","collectedAt",collectedAt,"source","DEV_SIMULATOR","reason","POWER_OFF"),200);
        assertEquals("REPORT_GENERATION_FAILED",call("POST",trip+"/report/retry",token,Map.of(),503).get("code"));
        assertEquals("FAILED",call("GET",trip+"/state",token,null,200).get("reportState"));
        assertEquals("FAILED",call("GET",trip+"/state",token,null,200).get("reportState"));
        assertEquals("READY",call("POST",trip+"/report/retry",token,Map.of(),200).get("reportState"));
        assertEquals("DEMO_READY_REPORT_PRESERVED",call("POST",runPath+"/preset",vehicle,Map.of("preset","REPORT_FAILED"),409).get("code"));
        var summary=call("GET",trip+"/state",token,null,200);
        assertEquals(182,((Number)((Map<?,?>)summary.get("distanceKm")).get("value")).intValue());
        var damage=(Map<?,?>)((Map<?,?>)summary.get("details")).get("damage");
        assertEquals(1,((Map<?,?>)damage.get("existingAtStart")).get("count"));
        assertEquals(2,((Map<?,?>)damage.get("allUnverifiedAtEnd")).get("count"));
        var finalSettings=(Map<?,?>)summary.get("finalSettings");
        assertEquals("SAVED",finalSettings.get("state"));
        var finalReadings=(Map<?,?>)((Map<?,?>)finalSettings.get("detail")).get("readings");
        assertEquals(23,((Number)finalReadings.get("CLIMATE_TARGET_C")).intValue());
        call("POST",trip+"/cleanup/ack",token,Map.of("requestId",endId,"personalCacheCleared",true,"localRecordsSettled",true),200);
        call("GET",trip+"/state",token,null,401);
        call("GET",trip+"/settings/applications",token,null,401);
        call("GET",app,token,null,401);
        call("POST",runPath+"/preset",vehicle,Map.of("preset","HANDOFF_APPROVED"),200);
        var replacement=call("POST",runPath+"/replacement-vehicle",vehicle,Map.of(),200);
        assertNotEquals(run.get("vehicleId"),replacement.get("vehicleId"));
        var operatorTrip=call("POST",runPath+"/bootstrap",operator,Map.of(),200);
        var operatorState=call("GET","/sessions/"+operatorTrip.get("sessionId")+"/state",
                (String)operatorTrip.get("token"),null,200);
        assertEquals(replacement.get("vehicleId"),operatorState.get("vehicleId"));
        var separateTrip=call("POST",runPath+"/bootstrap",(String)replacement.get("vehicleToken"),Map.of(),200);
        assertEquals(operatorTrip.get("sessionId"),separateTrip.get("sessionId"));
        assertNotEquals(session.get("sessionId"),separateTrip.get("sessionId"));
        call("GET",trip+"/state",(String)separateTrip.get("token"),null,403);
        var afterExchange=call("POST",predecessorReset,operator,setup,200);
        assertEquals(run.get("runId"),afterExchange.get("runId"));
        assertEquals(replacement.get("vehicleId"),afterExchange.get("vehicleId"));
        var reconnected=call("POST",runPath+"/bootstrap",(String)afterExchange.get("vehicleToken"),Map.of(),200);
        assertEquals(separateTrip.get("sessionId"),reconnected.get("sessionId"));
        var next=call("POST",runPath+"/reset",vehicle,Map.of("preset","NORMAL"),200);
        assertNotEquals(run.get("runId"),next.get("runId"));
        assertNotEquals(vehicle,next.get("vehicleToken"));
        call("GET",runPath,vehicle,null,401);
        var recovered=call("POST",runPath+"/reset",operator,Map.of("preset","NORMAL"),200);
        assertEquals(next.get("runId"),recovered.get("runId"));
        assertEquals(next.get("vehicleId"),recovered.get("vehicleId"));
        assertNotEquals(next.get("vehicleToken"),recovered.get("vehicleToken"));
        call("POST",runPath+"/reset",operator,Map.of("preset","MISSING_START"),409);
        call("POST","/dev/pleos/runs/"+recovered.get("runId")+"/bootstrap",(String)recovered.get("vehicleToken"),Map.of(),200);
        var newest=call("POST","/dev/pleos/runs/"+recovered.get("runId")+"/reset",operator,Map.of("preset","NORMAL"),200);
        assertNotEquals(recovered.get("runId"),newest.get("runId"));
        call("GET","/dev/pleos/runs/"+recovered.get("runId"),(String)recovered.get("vehicleToken"),null,401);
        call("POST",runPath+"/reset",operator,Map.of("preset","NORMAL"),409);
        call("POST",runPath+"/bootstrap",operator,Map.of(),409);
    }

    @Test void overdueCountdownStartsOnlyAfterActiveAndNeverMovesOnReconnect() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String operator=(String)call("POST","/auth/token",null,Map.of("username","demo-operator","password",System.getenv("NEXTKEY_DEV_PASSWORD")),200).get("token");
        var run=call("POST","/dev/pleos/runs",operator,Map.of("preset","OVERDUE_AFTER_5_SECONDS"),200);
        String path="/dev/pleos/runs/"+run.get("runId"),vehicle=(String)run.get("vehicleToken");
        // Preparing the app can take longer than the requested post-ACTIVE countdown.
        Thread.sleep(5100);
        var first=call("POST",path+"/bootstrap",vehicle,Map.of("snapshot",snapshot(40000)),200);
        String trip="/sessions/"+first.get("sessionId"),token=(String)first.get("token");
        var before=call("GET",trip+"/state",token,null,200);
        var guide=call("GET",trip+"/return-guide",token,null,200);
        assertEquals(false,guide.get("overdue"));
        String fixedDeadline=(String)guide.get("confirmedEndAt");
        Thread.sleep(5100);
        assertEquals(true,call("GET",trip+"/return-guide",token,null,200).get("overdue"));
        var resumed=call("POST",path+"/bootstrap",vehicle,Map.of("snapshot",snapshot(40010)),200);
        assertEquals(first.get("sessionId"),resumed.get("sessionId"));
        var after=call("GET",trip+"/state",(String)resumed.get("token"),null,200);
        assertEquals(before.get("startedAt"),after.get("startedAt"));
        assertEquals(before.get("startSnapshot"),after.get("startSnapshot"));
        var resumedGuide=call("GET",trip+"/return-guide",(String)resumed.get("token"),null,200);
        assertEquals(fixedDeadline,resumedGuide.get("confirmedEndAt"));
        assertEquals(true,resumedGuide.get("overdue"));
    }
}
