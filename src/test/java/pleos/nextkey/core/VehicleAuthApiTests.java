package pleos.nextkey.core;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.Json;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "NEXTKEY_TEST_MYSQL", matches = "true")
class VehicleAuthApiTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired Credentials credentials;
    @Autowired PasswordEncoder encoder;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> request(String path, String token, Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token);
        if (body == null) req.GET();
        else req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String,Object> post(String path, String token, Object body) throws Exception {
        var response = request(path, token, body);
        assertEquals(200, response.statusCode());
        return Json.read(response.body());
    }

    @Test
    void qrConcurrentVerificationAndDirectReauthenticationRecoverOneOriginalSession() throws Exception {
        assertEquals("nextkey_test", db.queryForObject("SELECT DATABASE()", String.class));
        String customer = UUID.randomUUID().toString();
        String vehicle = UUID.randomUUID().toString();
        String reservation = UUID.randomUUID().toString();
        String password = UUID.randomUUID().toString();
        db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,'CUSTOMER',?)",
                customer, customer, encoder.encode(password));
        db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,'VEHICLE','DISABLED')", vehicle, vehicle);
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'VIRTUAL','BATTERY','TEST')", vehicle);
        Instant now = Instant.now();
        db.update("INSERT INTO reservation(id,account_id,vehicle_id,status,starts_at,confirmed_end_at) VALUES(?,?,?,'CONFIRMED',?,?)",
                reservation, customer, vehicle, CoreData.sql(now.minusSeconds(30)), CoreData.sql(now.plusSeconds(3600)));
        String owner = credentials.issue(customer, null), device = credentials.issue(vehicle, null);
        var qr = post("/vehicle/challenges", device, Map.of("method", "QR"));
        String path = "/auth/challenges/" + qr.get("id");
        var bind = Map.of("reservationId", reservation, "qrToken", qr.get("qrToken"));
        post(path + "/qr", owner, bind);
        assertEquals(409, request(path + "/qr", owner, bind).statusCode());
        var proof = Map.of("code", System.getenv("NEXTKEY_DEV_OTP"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            String verifyPath = path + "/verify";
            var results = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> request(verifyPath, owner, proof).statusCode(),
                    () -> request(verifyPath, owner, proof).statusCode()));
            var statuses = new ArrayList<>(List.of(results.get(0).get(), results.get(1).get()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        } finally {
            executor.shutdownNow();
        }
        var first = db.queryForMap("SELECT id,started_at,start_json FROM rental_session WHERE reservation_id=?", reservation);
        String session = (String) first.get("id");
        String token = (String) post(path + "/claim", device, Map.of("pollSecret", qr.get("pollSecret"))).get("token");
        var recovery = Json.read(request("/vehicle/recovery", token, null).body());
        assertEquals(session, recovery.get("sessionId"));
        assertEquals("ACTIVE", recovery.get("state"));
        assertEquals(false, recovery.get("requiresAuthentication"));
        assertEquals(Map.of("state", "ACTIVE", "requiresAuthentication", true),
                Json.read(request("/vehicle/recovery", device, null).body()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM rental_session WHERE reservation_id=?", Integer.class, reservation));
        var direct = post("/vehicle/challenges", device, Map.of("method", "DIRECT"));
        path = "/auth/challenges/" + direct.get("id");
        assertEquals(401, request("/vehicle/recovery", token, null).statusCode());
        post(path + "/direct", device, Map.of("username", customer, "password", password, "reservationId", reservation));
        assertEquals(session, post(path + "/verify", device, proof).get("sessionId"));
        String recoveredToken = (String) post(path + "/claim", device, Map.of("pollSecret", direct.get("pollSecret"))).get("token");
        assertEquals(session, Json.read(request("/vehicle/recovery", recoveredToken, null).body()).get("sessionId"));
        var after = db.queryForMap("SELECT id,started_at,start_json FROM rental_session WHERE reservation_id=?", reservation);
        assertEquals(first, after);
    }
}
