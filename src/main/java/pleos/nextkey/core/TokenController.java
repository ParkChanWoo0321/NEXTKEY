package pleos.nextkey.core;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pleos.nextkey.common.Actor;

@RestController
@RequestMapping("/api")
class TokenController {
    record Login(String username, String password) {}

    private final Credentials credentials;

    TokenController(Credentials credentials) {
        this.credentials = credentials;
    }

    @PostMapping("/auth/token")
    Map<String, String> login(@RequestBody Login input) {
        Actor actor = credentials.authenticate(input.username(), input.password());
        String token = credentials.issue(actor.id(), null);
        return Map.of("token", token, "type", "Bearer", "role", actor.role());
    }
}
