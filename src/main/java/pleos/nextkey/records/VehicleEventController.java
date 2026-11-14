package pleos.nextkey.records;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.Access;
import pleos.nextkey.common.Actor;

@RestController
@RequestMapping("/api")
class VehicleEventController {
    private final Access access;
    private final VehicleEvents events;

    VehicleEventController(Access access, VehicleEvents events) {
        this.access = access;
        this.events = events;
    }

    private void authorize(String session, String... roles) {
        Actor actor = Actor.current();
        actor.require(roles);
        access.session(session, actor);
    }

    @PostMapping("/sessions/{id}/events")
    Map<String,Object> event(@PathVariable String id,@RequestBody VehicleEvents.Input input) {
        authorize(id,"SESSION");
        return events.accept(id,input);
    }

    @PutMapping("/sessions/{id}/location/consent")
    Map<String,Object> consent(@PathVariable String id,@RequestBody VehicleEvents.Consent input) {
        authorize(id,"CUSTOMER");
        return events.consent(id,input);
    }

    @GetMapping("/sessions/{id}/location/consent")
    Map<String,Object> decision(@PathVariable String id) {
        authorize(id,"CUSTOMER","SESSION");
        return events.consentState(id);
    }

    @GetMapping("/sessions/{id}/location/route")
    Map<String,Object> route(@PathVariable String id) {
        authorize(id,"CUSTOMER");
        return events.route(events.timeline(id));
    }

}
