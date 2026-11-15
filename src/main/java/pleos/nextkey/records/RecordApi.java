package pleos.nextkey.records;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.Access;
import pleos.nextkey.common.Actor;

@RestController
@RequestMapping("/api")
class RecordApi {
    private final Access access;
    private final PersonalSettings settings;
    private final RecordRequests requests;

    RecordApi(Access access,PersonalSettings settings,RecordRequests requests) {
        this.access=access;
        this.settings=settings;
        this.requests=requests;
    }

    private void authorize(String session,String... roles) {
        var actor = Actor.current();
        actor.require(roles);
        access.session(session,actor);
    }

    @GetMapping("/sessions/{id}/settings")
    Map<String,Object> profile(@PathVariable String id) {
        authorize(id,"CUSTOMER","SESSION");
        return settings.profile(id);
    }

    @PostMapping("/sessions/{id}/settings")
    Map<String,Object> save(@PathVariable String id,@RequestBody PersonalSettings.Observation observation,
                            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        authorize(id,"SESSION");
        return requests.once(id,"SAVE_SETTINGS",key,observation,()->settings.save(id,observation));
    }

    @PostMapping("/sessions/{id}/settings/applications")
    Map<String,Object> request(@PathVariable String id,@RequestBody PersonalSettings.Gate gate,
                               @RequestHeader(value="Idempotency-Key",required=false) String key) {
        authorize(id,"SESSION");
        return requests.once(id,"REQUEST_APPLICATION",key,gate,()->settings.request(id,gate));
    }

    @PostMapping("/sessions/{id}/settings/applications/{app}/applying")
    Map<String,Object> applying(@PathVariable String id,@PathVariable String app,@RequestBody PersonalSettings.Gate gate) {
        authorize(id,"SESSION");
        return settings.applying(id,app,gate);
    }

    @GetMapping("/sessions/{id}/settings/applications")
    Map<String,Object> applications(@PathVariable String id,@RequestParam(defaultValue="50") int limit) {
        authorize(id,"CUSTOMER","SESSION");
        return settings.applications(id,limit);
    }

    @GetMapping("/sessions/{id}/settings/applications/{app}")
    Map<String,Object> application(@PathVariable String id,@PathVariable String app) {
        authorize(id,"CUSTOMER","SESSION");
        return settings.application(id,app);
    }

    @PostMapping("/sessions/{id}/settings/applications/{app}/cancel")
    Map<String,Object> cancel(@PathVariable String id,@PathVariable String app,@RequestBody PersonalSettings.Cancel input) {
        authorize(id,"SESSION");
        return settings.cancel(id,app,input);
    }

    @PostMapping("/sessions/{id}/settings/applications/{app}/readback")
    Map<String,Object> result(@PathVariable String id,@PathVariable String app,@RequestBody PersonalSettings.Readback reading) {
        authorize(id,"SESSION");
        return settings.readback(id,app,reading);
    }

    @PutMapping("/operator/vehicles/{vehicle}/setting-capabilities")
    void capability(@PathVariable String vehicle,@RequestBody PersonalSettings.Capability input) {
        Actor.current().require("OPERATOR");
        settings.capability(vehicle,input);
    }
}
