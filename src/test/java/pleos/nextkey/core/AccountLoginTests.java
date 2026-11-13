package pleos.nextkey.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.Json;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "NEXTKEY_TEST_MYSQL", matches = "true")
class AccountLoginTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder;
    @Autowired Credentials credentials;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String account = UUID.randomUUID().toString();
    private final String password = UUID.randomUUID().toString();

    @BeforeEach
    void createCustomer() {
        assertEquals("nextkey_test", db.queryForObject("SELECT DATABASE()", String.class));
        db.update("INSERT INTO account(id, username, role, password_hash) VALUES(?, ?, 'CUSTOMER', ?)",
                account, account, encoder.encode(password));
    }

    @Test
    void issuedBearerResolvesToItsOwnerUntilExpiry() throws Exception {
        var response = login(password);
        assertEquals(200, response.statusCode());
        var body = Json.read(response.body());
        assertEquals("Bearer", body.get("type"));
        assertEquals("CUSTOMER", body.get("role"));
        String token = (String) body.get("token");
        assertNotNull(token);

        String stored = db.queryForObject("SELECT token_hash FROM auth_token WHERE account_id = ?",
                String.class, account);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        assertTrue(HexFormat.of().formatHex(digest).equals(stored), "Expected a SHA-256 token digest in storage");
        assertEquals(new Actor(account, "CUSTOMER"), credentials.identify(token, "/api/auth/token", "GET"));

        db.update("UPDATE auth_token SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE account_id = ?", account);
        assertNull(credentials.identify(token, "/api/auth/token", "GET"));
        var expired = http.send(request().header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, expired.statusCode());
        assertEquals("AUTH_REQUIRED", Json.read(expired.body()).get("code"));
    }

    @Test
    void invalidPasswordDoesNotIssueATokenAndAnonymousRequestsAreRejected() throws Exception {
        var invalid = login(UUID.randomUUID().toString());
        assertEquals(401, invalid.statusCode());
        assertEquals("INVALID_CREDENTIALS", Json.read(invalid.body()).get("code"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM auth_token WHERE account_id = ?",
                Integer.class, account));

        // Only POST is public on this path. The security filter rejects GET before MVC routing.
        var anonymous = http.send(request().GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
        assertEquals("AUTH_REQUIRED", Json.read(anonymous.body()).get("code"));
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/token"))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> login(String input) throws Exception {
        String payload = Json.write(Map.of("username", account, "password", input));
        var request = request().header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
