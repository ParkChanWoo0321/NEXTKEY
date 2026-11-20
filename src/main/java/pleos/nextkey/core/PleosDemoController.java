package pleos.nextkey.core;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.Actor;

/** Explicit development phone/office stand-in. No demo routes are registered when prod is active. */
@RestController
@Profile("dev & !prod")
@RequestMapping("/api/dev/pleos/runs")
class PleosDemoController {
    record Phone(String challengeId, String qrToken) {}
    record Bootstrap(Map<String,Object> snapshot) {}
    record Transition(String preset) {}
    private final PleosDemoService demo;

    PleosDemoController(PleosDemoService demo) { this.demo=demo; }

    @PostMapping
    Map<String,Object> create(@RequestBody(required=false) PleosDemoService.Options input) {
        return demo.create(Actor.current(),input);
    }

    @GetMapping("/{run}")
    Map<String,Object> status(@PathVariable String run) {
        return demo.status(Actor.current(),run);
    }

    @PostMapping("/{run}/reset")
    Map<String,Object> reset(@PathVariable String run,@RequestBody(required=false) PleosDemoService.Options input) {
        return demo.reset(Actor.current(),run,input);
    }

    @PostMapping("/{run}/phone-auth")
    Map<String,Object> phone(@PathVariable String run,@RequestBody Phone input) {
        return demo.phone(Actor.current(),run,input.challengeId(),input.qrToken());
    }

    @PostMapping("/{run}/bootstrap")
    Map<String,Object> bootstrap(@PathVariable String run,@RequestBody(required=false) Bootstrap input) {
        return demo.bootstrap(Actor.current(),run,input==null?null:input.snapshot());
    }

    @PostMapping("/{run}/preset")
    Map<String,Object> preset(@PathVariable String run,@RequestBody Transition input) {
        return demo.transition(Actor.current(),run,input.preset());
    }

    @PostMapping("/{run}/replacement-vehicle")
    Map<String,Object> replacementVehicle(@PathVariable String run) {
        return demo.replacementVehicle(Actor.current(),run);
    }
}
