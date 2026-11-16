package pleos.nextkey.lifecycle;

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
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NEXTKEY_TEST_MYSQL",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class BookingRaceTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    private final HttpClient client=HttpClient.newHttpClient();

    @Test void approvalAndCreationContendOnCustomerEvenOnDifferentCars() throws Exception { race(true); }
    @Test void approvalAndCreationContendOnCarEvenForDifferentCustomers() throws Exception { race(false); }

    private void race(boolean sameCustomer) throws Exception {
        String owner=account(), car=vehicle();
        Instant start=Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.SECONDS);
        Instant oldEnd=start.plusSeconds(3600), extended=oldEnd.plusSeconds(7200);
        String existing=UUID.randomUUID().toString(), eid=UUID.randomUUID().toString();
        db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) VALUES(?,?,?,'CONFIRMED',?,?)",
                existing,owner,car,utc(start),utc(oldEnd));
        db.update("""
                INSERT INTO rental_extension(id,reservation_id,account_id,requested_at,previous_end_at,requested_end_at,status,
                quote_amount,currency,quoted_at,agreed_at) VALUES(?,?,?,UTC_TIMESTAMP(6),?,?,'PENDING_APPROVAL',3000,'KRW',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """,eid,existing,owner,utc(oldEnd),utc(extended));
        String who=sameCustomer?owner:account(), which=sameCustomer?vehicle():car;
        var newBooking=Map.of("accountId",who,"vehicleId",which,"startsAt",oldEnd.toString(),"confirmedEndAt",extended.plusSeconds(3600).toString());
        var login=post("/auth/token",null,Map.of("username","demo-operator","password",System.getenv("NEXTKEY_DEV_PASSWORD")));
        assertEquals(200,login.statusCode());
        String token=(String)Json.read(login.body()).get("token");
        var gate=new CyclicBarrier(2);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var approve=pool.submit(()->{gate.await();return post("/extensions/"+eid+"/decision",token,Map.of("approved",true));});
            var book=pool.submit(()->{gate.await();return post("/operator/reservations",token,newBooking);});
            var approved=approve.get(20,TimeUnit.SECONDS);
            var booked=book.get(20,TimeUnit.SECONDS);
            assertEquals(Set.of(200,409),new HashSet<>(List.of(approved.statusCode(),booked.statusCode())));
            if (approved.statusCode()==409) {
                assertEquals("RESERVATION_OVERLAP",Json.read(approved.body()).get("code"));
                assertEquals(200,post("/extensions/"+eid+"/decision",token,Map.of("approved",false)).statusCode());
                assertEquals(utc(oldEnd),db.queryForObject("SELECT confirmed_end_at FROM reservation WHERE id=?",LocalDateTime.class,existing));
            } else {
                assertEquals("RESERVATION_OVERLAP",Json.read(booked.body()).get("code"));
                assertEquals(utc(extended),db.queryForObject("SELECT confirmed_end_at FROM reservation WHERE id=?",LocalDateTime.class,existing));
            }
        } finally { pool.shutdownNow(); }
    }
    private HttpResponse<String> post(String path,String token,Map<String,?> body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if(token!=null) request.header("Authorization","Bearer "+token);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String account() {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO account(id,role,username,password_hash) VALUES(?,'CUSTOMER',?,'DISABLED-FIXTURE-CREDENTIAL')",id,id);
        return id;
    }
    private String vehicle() {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'race-test','FUEL',?)",id,id);
        return id;
    }
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time,ZoneOffset.UTC); }
}
