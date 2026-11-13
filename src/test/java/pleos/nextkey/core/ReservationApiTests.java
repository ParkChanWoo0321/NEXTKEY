package pleos.nextkey.core;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.Json;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "NEXTKEY_TEST_MYSQL", matches = "true")
class ReservationApiTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired Credentials credentials;
    private final HttpClient http = HttpClient.newHttpClient();
    private String owner, other, operator, car;
    private Instant from, until;

    @BeforeEach
    void fixtures() {
        assertEquals("nextkey_test", db.queryForObject("SELECT DATABASE()", String.class));
        owner = account("CUSTOMER");
        other = account("CUSTOMER");
        operator = account("OPERATOR");
        car = UUID.randomUUID().toString();
        db.update("INSERT INTO vehicle(id,model,energy_type,compatibility_key) VALUES(?,'VIRTUAL','BATTERY','TEST')", car);
        from = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        until = from.plusSeconds(3600);
    }

    private String account(String role) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO account(id,username,role,password_hash) VALUES(?,?,?,'DISABLED')", id, id, role);
        return credentials.issue(id, null);
    }

    private Map<String,Object> booking() {
        return Map.of("vehicleId", car, "startsAt", from.toString(), "confirmedEndAt", until.toString());
    }

    private HttpResponse<String> call(String method, String path, String token, Object input) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) req.header("Authorization", "Bearer " + token);
        req.method(method, input == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(input)));
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ownersReadTheirBookingAndInvalidCoordinatesRemainUnknown() throws Exception {
        var created = call("POST", "/reservations", owner, booking());
        assertEquals(200, created.statusCode());
        String id = (String) Json.read(created.body()).get("id");
        assertEquals(200, call("GET", "/reservations/" + id, owner, null).statusCode());
        assertTrue(call("GET", "/reservations", owner, null).body().contains(id));
        assertEquals(403, call("GET", "/reservations/" + id, other, null).statusCode());
        assertEquals(401, call("GET", "/reservations/" + id, null, null).statusCode());
        var changed = call("PUT", "/reservations/" + id + "/destination", owner,
                Map.of("name", "Virtual destination", "latitude", 95, "longitude", 127));
        assertEquals(200, changed.statusCode());
        var place = (Map<?,?>) Json.read(changed.body()).get("destination");
        assertEquals("UNKNOWN", place.get("coordinateStatus"));
        assertNull(place.get("latitude"));
        assertNull(place.get("longitude"));
        String returnPath = "/operator/reservations/" + id + "/return-place";
        assertEquals(403, call("PUT", returnPath, owner, Map.of("name", "Virtual depot")).statusCode());
        assertEquals(200, call("PUT", returnPath, operator, Map.of("latitude", 37, "longitude", 127)).statusCode());
    }

    @Test
    void simultaneousVehicleBookingsPersistOnlyOneReservation() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.<Callable<Integer>>of(
                    () -> call("POST", "/reservations", owner, booking()).statusCode(),
                    () -> call("POST", "/reservations", other, booking()).statusCode()));
            var codes = new ArrayList<>(List.of(results.get(0).get(), results.get(1).get()));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM reservation WHERE vehicle_id=?", Integer.class, car));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidIntervalsAreRejectedAndCancelledBookingsCannotChange() throws Exception {
        var reversed = new HashMap<>(booking());
        reversed.put("confirmedEndAt", from.minusSeconds(1).toString());
        assertEquals(400, call("POST", "/reservations", owner, reversed).statusCode());
        String id = (String) Json.read(call("POST", "/reservations", owner, booking()).body()).get("id");
        String cancel = "/operator/reservations/" + id + "/cancel";
        assertEquals(403, call("POST", cancel, owner, null).statusCode());
        assertEquals(200, call("POST", cancel, operator, null).statusCode());
        assertEquals(409, call("PUT", "/reservations/" + id + "/destination", owner, Map.of("name", "Closed")).statusCode());
        assertEquals(200, call("POST", "/reservations", other, booking()).statusCode());
    }
}
