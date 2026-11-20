package pleos.nextkey.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.lifecycle.EndController;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
class PleosClaimDeadlineTests {
    @Autowired PleosDemoService demo;
    @Autowired ChallengeService challenges;
    @Autowired BookingService bookings;
    @Autowired JdbcTemplate db;
    @Autowired EndController end;

    @Test void qrClaimStartsTheCountdownAndDirectReconnectPreservesIt() {
        checkClaim("QR","DIRECT");
    }

    @Test void directClaimStartsTheCountdownAndQrReconnectPreservesIt() {
        checkClaim("DIRECT","QR");
    }

    @Test void anAuthenticatedClaimCannotIssueAnOldSessionTokenAfterReset() {
        String password=CoreData.secret();
        Actor presenter=new Actor("demo-operator","OPERATOR");
        var run=demo.create(presenter,new PleosDemoService.Options("NORMAL",null,null,null,null,null,password));
        var vehicle=new Actor((String)run.get("vehicleId"),"VEHICLE");
        var reservation=(ReservationView)run.get("reservation");
        var challenge=verified("QR",vehicle,reservation.id(),(String)run.get("directUsername"),password);
        // The security filter identified this actor before the concurrent reset committed.
        demo.reset(presenter,(String)run.get("runId"),new PleosDemoService.Options("NORMAL",null,null,null,null,null,null));
        assertRetiredClaim(vehicle,challenge);
    }

    @Test void anAuthenticatedReplacementVehicleClaimAlsoHonorsRunRetirement() {
        String password=CoreData.secret();
        Actor presenter=new Actor("demo-operator","OPERATOR");
        var run=demo.create(presenter,new PleosDemoService.Options("NORMAL",null,null,null,null,null,password));
        String runId=(String)run.get("runId");
        var initialVehicle=new Actor((String)run.get("vehicleId"),"VEHICLE");
        var active=demo.bootstrap(initialVehicle,runId,null);
        demo.transition(initialVehicle,runId,"EXCHANGE_PREAPPROVED");
        finish((String)active.get("sessionId"));
        demo.transition(initialVehicle,runId,"HANDOFF_APPROVED");
        var next=demo.replacementVehicle(initialVehicle,runId);
        var replacementVehicle=new Actor((String)next.get("vehicleId"),"VEHICLE");
        var reservation=(ReservationView)next.get("reservation");
        var challenge=verified("DIRECT",replacementVehicle,reservation.id(),(String)next.get("directUsername"),password);
        demo.reset(presenter,runId,new PleosDemoService.Options("NORMAL",null,null,null,null,null,null));
        assertRetiredClaim(replacementVehicle,challenge);
    }

    private void assertRetiredClaim(Actor vehicle,Map<String,Object> challenge) {
        String id=(String)challenge.get("id");
        assertEquals("DEMO_RUN_RETIRED",assertThrows(ApiError.class,
                ()->challenges.claim(id,vehicle,(String)challenge.get("pollSecret"))).code());
        String session=db.queryForObject("SELECT session_id FROM auth_challenge WHERE id=?",String.class,id);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM auth_token WHERE session_id=?",Integer.class,session));
    }

    private void finish(String sessionId) {
        var original=SecurityContextHolder.getContext();
        try {
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new Actor(sessionId,"SESSION"),null,List.of()));
            SecurityContextHolder.setContext(context);
            UUID requestId=UUID.randomUUID();
            Instant observedAt=Instant.now();
            end.submit(sessionId,new EndController.End(requestId,true,observedAt,
                    new EndController.Safety(true,true,observedAt,"DEV_SIMULATOR"),Map.of(),Map.of(),List.of()));
            end.acknowledge(sessionId,new EndController.Ack(requestId));
            end.clean(sessionId,new EndController.Clean(requestId,true,true));
        } finally { SecurityContextHolder.setContext(original); }
    }

    private void checkClaim(String firstMethod,String nextMethod) {
        String password=CoreData.secret();
        var run=demo.create(new Actor("demo-operator","OPERATOR"),new PleosDemoService.Options(
                "OVERDUE_AFTER_5_SECONDS",null,null,null,null,null,password));
        var vehicle=new Actor((String)run.get("vehicleId"),"VEHICLE");
        var original=(ReservationView)run.get("reservation");
        String rid=original.id(),username=(String)run.get("directUsername");
        var first=verified(firstMethod,vehicle,rid,username,password);
        String firstId=(String)first.get("id");
        assertEquals("CLAIM_FORBIDDEN",assertThrows(ApiError.class,
                ()->challenges.claim(firstId,vehicle,"invalid")).code());
        assertEquals(original.confirmedEndAt(),bookings.lookup(rid,false).confirmedEndAt());
        Instant earliest=Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        var claim=challenges.claim(firstId,vehicle,(String)first.get("pollSecret"));
        Instant deadline=bookings.lookup(rid,false).confirmedEndAt();
        assertFalse(deadline.isBefore(earliest));
        assertFalse(deadline.isAfter(Instant.now().plusSeconds(5)));

        var next=verified(nextMethod,vehicle,rid,username,password);
        var resumed=challenges.claim((String)next.get("id"),vehicle,(String)next.get("pollSecret"));
        assertEquals(claim.get("sessionId"),resumed.get("sessionId"));
        assertEquals(deadline,bookings.lookup(rid,false).confirmedEndAt());
    }

    private Map<String,Object> verified(String method,Actor vehicle,String rid,String username,String password) {
        var challenge=challenges.create(vehicle,method,Map.of());
        String id=(String)challenge.get("id");
        Actor user=new Actor(username,"CUSTOMER");
        if("QR".equals(method)) {
            challenges.qr(id,user,(String)challenge.get("qrToken"),rid);
            challenges.verify(id,user,System.getenv("NEXTKEY_DEV_OTP"));
        } else {
            challenges.direct(id,vehicle,username,password,rid);
            challenges.verify(id,vehicle,System.getenv("NEXTKEY_DEV_OTP"));
        }
        return challenge;
    }
}
