package pleos.nextkey.records;

import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pleos.nextkey.common.Access;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;

@RestController
@RequestMapping("/api")
class RecordApi {
    private final Access access;
    private final PersonalSettings settings;
    private final DamageRecords damage;
    private final RecordRequests requests;

    RecordApi(Access access,PersonalSettings settings,DamageRecords damage,RecordRequests requests) {
        this.access=access;
        this.settings=settings;
        this.damage=damage;
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

    @PostMapping("/sessions/{id}/damage")
    Map<String,Object> damage(@PathVariable String id,@RequestBody DamageRecords.Submission input,
                              @RequestHeader(value="Idempotency-Key",required=false) String key) {
        authorize(id,"CUSTOMER");
        return requests.once(id,"DAMAGE",key,input,()->damage.create(id,input));
    }

    @GetMapping("/sessions/{id}/damage")
    Map<String,Object> own(@PathVariable String id) {
        authorize(id,"CUSTOMER");
        return damage.details(id);
    }

    @GetMapping("/sessions/{id}/damage/vehicle")
    Object vehicle(@PathVariable String id,@RequestParam(required=false) String view) {
        authorize(id,"SESSION","CUSTOMER");
        if (view == null || "summary".equals(view)) return damage.vehicle(id);
        if ("handover".equals(view)) return damage.handover(id);
        throw new ApiError(400,"INVALID_DAMAGE_VIEW");
    }

    @GetMapping("/sessions/{id}/support")
    Map<String,Object> support(@PathVariable String id) {
        authorize(id,"CUSTOMER","SESSION");
        return damage.support();
    }

    /** Company safety information is available before a personal rental session exists. */
    @GetMapping("/support")
    Map<String,Object> support() {
        Actor.current().require("VEHICLE","CUSTOMER","SESSION","OPERATOR");
        return damage.support();
    }

    @PostMapping(value="/sessions/{id}/damage/{report}/photos",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String,Object> photo(@PathVariable String id,@PathVariable String report,@RequestParam("file") MultipartFile file,
                             @RequestParam(required=false) Boolean parked,@RequestParam(required=false) Instant safetyObservedAt,
                             @RequestHeader(value="Idempotency-Key",required=false) String key) {
        authorize(id,"CUSTOMER");
        if (key==null) return damage.attach(id,report,file,parked,safetyObservedAt);
        var identity=Arrays.asList(report,requests.photoHash(file),file.getContentType(),parked,safetyObservedAt);
        return requests.once(id,"ATTACH_PHOTO",key,identity,()->damage.attach(id,report,file,parked,safetyObservedAt));
    }

    @GetMapping("/sessions/{id}/damage/photos/{photo}")
    ResponseEntity<byte[]> download(@PathVariable String id,@PathVariable String photo) {
        authorize(id,"CUSTOMER");
        var image = damage.photo(id,photo);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(image.mime()))
            .header("X-Content-Type-Options","nosniff").body(image.bytes());
    }

    @PutMapping("/operator/damage/{report}/verification")
    void verify(@PathVariable String report,@RequestBody DamageRecords.Verification input) {
        var actor=Actor.current();
        actor.require("OPERATOR");
        damage.verify(report,actor.id(),input);
    }
}
