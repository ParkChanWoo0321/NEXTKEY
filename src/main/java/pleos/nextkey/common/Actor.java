package pleos.nextkey.common;

import java.util.Arrays;
import org.springframework.security.core.context.SecurityContextHolder;

public record Actor(String id, String role) {
    public static Actor current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Actor actor)) {
            throw new ApiError(401, "AUTHENTICATION_REQUIRED");
        }
        return actor;
    }

    public void require(String... roles) {
        if (!Arrays.asList(roles).contains(role)) throw new ApiError(403, "FORBIDDEN");
    }
}
