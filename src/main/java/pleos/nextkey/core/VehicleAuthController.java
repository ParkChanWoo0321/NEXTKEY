package pleos.nextkey.core;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.Actor;

@RestController
@RequestMapping("/api")
class VehicleAuthController {
    record NewChallenge(String method, Map<String,Object> snapshot) {}
    record Qr(String qrToken, String reservationId) {}
    record Direct(String username, String password, String reservationId) {}
    record Verify(String code) {}
    record Claim(String pollSecret) {}
    private final ChallengeService challenges;

    VehicleAuthController(ChallengeService challenges) {
        this.challenges = challenges;
    }

    @PostMapping("/vehicle/challenges")
    Object create(@RequestBody NewChallenge r){return challenges.create(Actor.current(),r.method(),r.snapshot());}
    @PostMapping("/auth/challenges/{id}/qr")
    Object qr(@PathVariable String id,@RequestBody Qr r){return challenges.qr(id,Actor.current(),r.qrToken(),r.reservationId());}
    @PostMapping("/auth/challenges/{id}/direct")
    Object direct(@PathVariable String id,@RequestBody Direct r){
        return challenges.direct(id,Actor.current(),r.username(),r.password(),r.reservationId());
    }
    @PostMapping("/auth/challenges/{id}/verify")
    Object verify(@PathVariable String id,@RequestBody Verify r){return challenges.verify(id,Actor.current(),r.code());}
    @PostMapping("/auth/challenges/{id}/claim")
    Object claim(@PathVariable String id,@RequestBody Claim r){return challenges.claim(id,Actor.current(),r.pollSecret());}
    @GetMapping("/vehicle/recovery")
    Object recover(){return challenges.recovery(Actor.current());}

}
