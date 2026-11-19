package pleos.nextkey.common;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiErrorsTests {
    @Test
    void rateLimitDetailsExposeTheRetryTimeWithoutReplacingTheErrorCode() {
        ApiError error=new ApiError(429,"SECOND_FACTOR_ATTEMPT_LIMIT",Map.of(
                "code","cannot-override-domain-code","retryAt","2026-11-15T03:00:15Z","retryAfterSeconds",15L));
        var response=new ApiErrors().domain(error);
        assertEquals(429,response.getStatusCode().value());
        assertEquals("15",response.getHeaders().getFirst("Retry-After"));
        var body=(Map<?,?>)response.getBody();
        assertEquals("SECOND_FACTOR_ATTEMPT_LIMIT",body.get("code"));
        assertEquals("2026-11-15T03:00:15Z",body.get("retryAt"));
    }

    @Test
    void existingDomainErrorsKeepTheirOriginalMinimalResponse() {
        var response=new ApiErrors().domain(new ApiError(403,"FORBIDDEN"));
        assertEquals(403,response.getStatusCode().value());
        assertEquals(Map.of("code","FORBIDDEN"),response.getBody());
        assertNull(response.getHeaders().getFirst("Retry-After"));
    }
}
