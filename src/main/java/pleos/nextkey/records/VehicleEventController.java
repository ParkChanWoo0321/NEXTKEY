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
    private final RecordRequests requests;

    VehicleEventController(Access access, VehicleEvents events, RecordRequests requests) {
        this.access = access;
        this.events = events;
        this.requests = requests;
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
    Map<String,Object> consent(@PathVariable String id,@RequestBody VehicleEvents.Consent input,
                              @RequestHeader(value="Idempotency-Key",required=false) String key) {
        authorize(id,"CUSTOMER","SESSION");
        requests.once(id,"LOCATION_CONSENT",key,input,()->events.consent(id,input));
        // An earlier grant replay must report a later withdrawal, not a stale successful-grant response.
        return events.consentState(id);
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
