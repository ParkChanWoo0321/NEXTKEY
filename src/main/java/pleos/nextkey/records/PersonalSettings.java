package pleos.nextkey.records;

import static pleos.nextkey.records.RecordData.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;

/** Stores client observations and application evidence; never invokes a vehicle actuator. */
@Service
class PersonalSettings {
    record MemoryReference(String vehicleId,Integer slot,String referenceSource,BigDecimal expectedSeatPosition) {
    }
    record Observation(Instant observedAt,String source,Map<String,Object> readings,
                       Map<String,Object> readingDetails,MemoryReference memoryReference,String reason) {
        Observation(Instant at,String source,Map<String,Object> readings) {
            this(at,source,readings,null,null,null);
        }
    }
    record Gate(Boolean permissionGranted,Boolean safeToOperate,Instant observedAt,
                Map<String,Object> desired,String retryOf,List<String> fields,MemoryReference memoryReference) {
        Gate(Boolean permission,Boolean safe,Instant at) {
            this(permission,safe,at,null,null,null,null);
        }
        Gate(Boolean permission,Boolean safe,Instant at,Map<String,Object> desired) {
            this(permission,safe,at,desired,null,null,null);
        }
    }
    record Readback(Instant observedAt,String source,Map<String,Object> readings,Map<String,String> statuses,
                    Boolean permissionGranted,Boolean safeToOperate,Instant safetyObservedAt) {
        Readback(Instant at,String source,Map<String,Object> readings) {
            this(at,source,readings,null,null,null,null);
        }
    }
    record Cancel(String reason,Instant observedAt) {
    }
    record Capability(String setting,List<BigDecimal> values,String accessType,String area,
                      BigDecimal minValue,BigDecimal maxValue,BigDecimal step) {
        Capability(String setting,List<BigDecimal> values) {
            this(setting,values,null,null,null,null,null);
        }
    }

    private static final String SEAT="SEAT_FORE_AFT_POSITION", SLOT="SEAT_MEMORY_SLOT";
    private static final Set<String> NAMES=Set.of("CLIMATE_TARGET_C","CLIMATE_FAN_LEVEL",SLOT,SEAT);
    private static final Set<String> TERMINAL=Set.of("APPLIED","PARTIAL","FAILED","CANCELLED");
    private static final Set<String> READING_STATUSES=Set.of("OK","READ_FAILED","UNSUPPORTED","PERMISSION_DENIED","UNVERIFIED");
    private static final Set<String> FAILURE_STATUSES=Set.of("READ_FAILED","UNSUPPORTED","PERMISSION_DENIED","WRITE_FAILED","UNVERIFIED","OUT_OF_RANGE");
    private final RecordData data;
    PersonalSettings(RecordData data) {
        this.data=data;
    }

    private String compatibility(Object vehicle) {
        return data.db.queryForObject("SELECT compatibility_key FROM vehicle WHERE id=?",String.class,vehicle);
    }

    private Map<String,Map<String,Object>> capabilityDetails(Object vehicle) {
        Map<String,Map<String,Object>> out=new TreeMap<>();
        for(var r:data.db.queryForList("SELECT setting_name,allowed_values FROM vehicle_setting_capability WHERE vehicle_id=?",vehicle)) {
            var d=new LinkedHashMap<>(Json.read((String)r.get("allowed_values")));
            d.put("accessType","READ_WRITE");
            d.put("supportOrigin","LEGACY_REGISTERED");
            out.put((String)r.get("setting_name"),d);
        }
        for(var r:data.db.queryForList("SELECT setting_name,detail FROM setting_capability_detail WHERE vehicle_id=?",vehicle)) {
            out.put((String)r.get("setting_name"),Json.read((String)r.get("detail")));
        }
        return out;
    }

    private Map<String,List<?>> capabilities(Object vehicle) {
        Map<String,List<?>> out=new TreeMap<>();
        capabilityDetails(vehicle).forEach((k,v)->out.put(k,(List<?>)v.getOrDefault("values",List.of())));
        return out;
    }

    @Transactional
    public void capability(String vehicle,Capability input) {
        require(input!=null && input.setting()!=null && NAMES.contains(input.setting()),400,"INVALID_SETTING_CAPABILITY");
        var values=input.values()==null?List.<BigDecimal>of():input.values();
        require(values.size()<=100 && values.stream().allMatch(Objects::nonNull),400,"INVALID_SETTING_CAPABILITY");
        String access=input.accessType()==null?"READ_WRITE":input.accessType();
        require(Set.of("READ","WRITE","READ_WRITE","NONE").contains(access),400,"INVALID_SETTING_ACCESS");
        require(!SEAT.equals(input.setting()) || Set.of("READ","NONE").contains(access),400,"READ_ONLY_SETTING");
        require(input.area()==null || input.area().matches("[A-Z0-9_]{1,64}"),400,"INVALID_SETTING_AREA");
        require((input.minValue()==null)==(input.maxValue()==null),400,"INVALID_SETTING_RANGE");
        require(input.minValue()==null || input.minValue().compareTo(input.maxValue())<=0,400,"INVALID_SETTING_RANGE");
        require(input.step()==null || input.step().signum()>0 && input.minValue()!=null,400,"INVALID_SETTING_STEP");
        if(input.minValue()!=null) {
            require(values.stream().allMatch(value -> value.compareTo(input.minValue())>=0 &&
                value.compareTo(input.maxValue())<=0 && (input.step()==null ||
                value.subtract(input.minValue()).remainder(input.step()).signum()==0)),400,"INVALID_SETTING_RANGE");
        }
        if(SLOT.equals(input.setting()) || "CLIMATE_FAN_LEVEL".equals(input.setting())) {
            require(values.stream().allMatch(v->v.stripTrailingZeros().scale()<=0 && v.signum()>=0),400,"INVALID_DISCRETE_SETTINGS");
            require(input.minValue()==null && input.maxValue()==null && input.step()==null,400,"INVALID_DISCRETE_SETTINGS");
        }
        require(!data.db.queryForList("SELECT id FROM vehicle WHERE id=? FOR UPDATE",vehicle).isEmpty(),404,"VEHICLE_NOT_FOUND");
        // Old empty values means removal; explicit NONE retains an unsupported capability result.
        if(values.isEmpty() && input.accessType()==null && input.minValue()==null) {
            data.db.update("DELETE FROM vehicle_setting_capability WHERE vehicle_id=? AND setting_name=?",vehicle,input.setting());
            data.db.update("DELETE FROM setting_capability_detail WHERE vehicle_id=? AND setting_name=?",vehicle,input.setting());
            return;
        }
        require(!access.contains("WRITE") || !values.isEmpty() || input.minValue()!=null,400,"WRITABLE_RANGE_REQUIRED");
        var d=new LinkedHashMap<String,Object>();
        d.put("values",values);
        d.put("accessType",access);
        d.put("supportOrigin","REGISTERED");
        if(input.area()!=null) {
            d.put("area",input.area());
        }
        if(input.minValue()!=null) {
            d.put("minValue",input.minValue());
            d.put("maxValue",input.maxValue());
        }
        if(input.step()!=null) {
            d.put("step",input.step());
        }
        String json=Json.write(d);
        data.db.update("INSERT INTO setting_capability_detail(vehicle_id,setting_name,detail) VALUES(?,?,?) ON DUPLICATE KEY UPDATE detail=?",vehicle,input.setting(),json,json);
        if(!SEAT.equals(input.setting())) {
            String allowed=Json.write(Map.of("values",values));
            data.db.update("INSERT INTO vehicle_setting_capability(vehicle_id,setting_name,allowed_values) VALUES(?,?,?) ON DUPLICATE KEY UPDATE allowed_values=?",vehicle,input.setting(),allowed,allowed);
        }
    }

    private static boolean sameValue(Object a,Object b) {
        return numeric(a) && numeric(b) && number(a).compareTo(number(b))==0;
    }

    private static boolean numeric(Object value) {
        try {
            return value instanceof Number && new BigDecimal(value.toString()).precision()<=30;
        }
        catch(NumberFormatException ex) {
            return false;
        }
    }

    private static boolean access(Map<String,Object> c,String type) {
        return c!=null && String.valueOf(c.get("accessType")).contains(type);
    }

    private static boolean allowed(Map<String,Object> c,Object value) {
        if(c==null || !numeric(value)) {
            return false;
        }
        var values=(List<?>)c.getOrDefault("values",List.of());
        if(values.stream().anyMatch(v->sameValue(v,value))) {
            return true;
        }
        if(c.get("minValue")==null) {
            return false;
        }
        var n=number(value);
        var min=number(c.get("minValue"));
        var max=number(c.get("maxValue"));
        return n.compareTo(min)>=0 && n.compareTo(max)<=0 && (c.get("step")==null || n.subtract(min).remainder(number(c.get("step"))).signum()==0);
    }

    private Map<String,Object> observed(Map<String,Object> readings,Object vehicle,Map<String,Object> metadata) {
        Map<String,Object> good=new TreeMap<>();
        if(readings==null) {
            return good;
        }
        require(readings.size()<=16,400,"TOO_MANY_SETTINGS");
        var caps=capabilityDetails(vehicle);
        readings.forEach((k,v)-> {
            var status=object(metadata.get(k)).getOrDefault("status","OK");
            var c=caps.get(k);
            // An unregistered raw SDK seat read is observable, but can never become an actuator target.
            if(NAMES.contains(k) && numeric(v) && (!SEAT.equals(k) || number(v).stripTrailingZeros().scale()<=0) &&
                    "OK".equals(status) && (access(c,"READ") || SEAT.equals(k) && c==null)) {
                good.put(k,v);
            }
        });
        return good;
    }

    private Map<String,Object> readingDetails(Observation o) {
        var out=new TreeMap<String,Object>();
        if(o.readingDetails()!=null) {
            require(o.readingDetails().size()<=16,400,"TOO_MANY_SETTINGS");
            o.readingDetails().forEach((k,v)-> {
                require(NAMES.contains(k) && v instanceof Map<?,?>,400,"INVALID_READING_DETAILS");
                var m=object(v);
                var clean=new LinkedHashMap<String,Object>();
                String status=String.valueOf(m.getOrDefault("status","OK"));
                require(READING_STATUSES.contains(status),400,"INVALID_READING_STATUS");
                clean.put("status",status);
                for(String field:List.of("unit","area"))if(m.get(field)!=null) {
                    String text=String.valueOf(m.get(field));
                    require(text.length()<=64,400,"INVALID_READING_DETAILS");
                    clean.put(field,text);
                }
                out.put(k,clean);
            });
        }
        if(o.readings()!=null) {
            o.readings().keySet().stream().filter(NAMES::contains).forEach(k->out.putIfAbsent(k,Map.of("status","OK")));
        }
        return out;
    }

    private String activity(String id,String state,Map<String,Object> detail) {
        String app=uuid();
        data.db.update("INSERT INTO setting_activity(id,session_id,created_at,state,detail) VALUES(?,?,?,?,?)",app,id,stamp(Instant.now()),state,Json.write(detail));
        return app;
    }

    private Map<String,Object> saved(Map<String,Object> s) {
        var rows=data.db.queryForList(
            "SELECT source_vehicle_id,compatibility_key,observed_at,source,readings,observation_details,memory_reference " +
            "FROM setting_profile WHERE account_id=? AND compatibility_key=?",
            s.get("account_id"),compatibility(s.get("vehicle_id")));
        if(rows.isEmpty()) {
            return Map.of();
        }
        var p=withUtcTimes(rows.get(0));
        for(String field:List.of("readings","observation_details","memory_reference")) {
            p.put(field,p.get(field)==null?Map.of():Json.read((String)p.get(field)));
        }
        return p;
    }

    Map<String,Object> profile(String id) {
        var s=data.session(id,false);
        var p=saved(s);
        boolean any=!p.isEmpty() || Boolean.TRUE.equals(data.db.queryForObject("SELECT EXISTS(SELECT 1 FROM setting_profile WHERE account_id=?)",Boolean.class,s.get("account_id")));
        return Map.of("hasSavedProfile",any,"compatibleProfile",p,"vehicleCapabilities",capabilities(s.get("vehicle_id")),"capabilityDetails",capabilityDetails(s.get("vehicle_id")),"manufacturerUnconfirmed",List.of("MIRROR","STEERING_WHEEL"));
    }

    @Transactional
    public Map<String,Object> save(String id,Observation o) {
        data.lockProfileOwner(id);
        var result=store(id,data.active(id),o,false);
        if("MANUAL_ADJUSTMENT".equals(o.reason())) {
            cancelPending(id,"MANUAL_ADJUSTMENT");
        }
        return result;
    }

    private Map<String,Object> reference(MemoryReference ref,Object vehicle) {
        if(ref==null) {
            return Map.of();
        }
        require(Objects.equals(String.valueOf(vehicle),ref.vehicleId()) && ref.slot()!=null && ref.slot()>=0 && ref.referenceSource()!=null && Set.of("VERIFIED_EXISTING_VEHICLE_MEMORY_REFERENCE","CLIENT_REPORTED_EXISTING_MEMORY").contains(ref.referenceSource()),400,"INVALID_MEMORY_REFERENCE");
        require(ref.expectedSeatPosition()==null || ref.expectedSeatPosition().stripTrailingZeros().scale()<=0,
            400,"INVALID_SEAT_POSITION");
        var out=new LinkedHashMap<String,Object>();
        out.put("vehicleId",ref.vehicleId());
        out.put("slot",ref.slot());
        out.put("referenceSource",ref.referenceSource());
        if(ref.expectedSeatPosition()!=null) {
            out.put("expectedSeatPosition",ref.expectedSeatPosition());
        }
        out.put("verification","CLIENT_REPORTED");
        return out;
    }

    private Map<String,Object> store(String id,Map<String,Object> s,Observation o,boolean ending) {
        require(o!=null && o.observedAt()!=null && !o.observedAt().isBefore(instant(s.get("started_at"))),400,"INVALID_SETTING_TIME");
        Instant reference=ending && s.get("ended_at")!=null?instant(s.get("ended_at")):Instant.now();
        long freshness=data.env.getProperty("nextkey.settings.max-reading-age-seconds",Long.class,120L);
        require(!o.observedAt().isAfter(reference) && !o.observedAt().isBefore(reference.minusSeconds(freshness)),400,"STALE_SETTING_READINGS");
        String source=data.source(o.source());
        var metadata=readingDetails(o);
        var readings=observed(o.readings(),s.get("vehicle_id"),metadata);
        require(!readings.isEmpty(),422,"NO_SUPPORTED_READINGS");
        var memory=new LinkedHashMap<>(reference(o.memoryReference(),s.get("vehicle_id")));
        if(memory.isEmpty() && readings.containsKey(SLOT)) {
            memory.put("vehicleId",s.get("vehicle_id"));
            memory.put("slot",readings.get(SLOT));
            memory.put("referenceSource","CLIENT_REPORTED_EXISTING_MEMORY");
            memory.put("verification","CLIENT_REPORTED");
        }
        if(!memory.isEmpty()) {
            require(allowed(capabilityDetails(s.get("vehicle_id")).get(SLOT),memory.get("slot")),400,"MEMORY_SLOT_UNSUPPORTED");
        }
        var detail=new LinkedHashMap<String,Object>();
        detail.put("readings",readings);
        detail.put("readingDetails",metadata);
        detail.put("memoryReference",memory);
        detail.put("observedAt",o.observedAt());
        detail.put("source",source);
        detail.put("verification","CLIENT_REPORTED");
        detail.put("atEnd",ending);
        detail.put("skippedCount",o.readings()==null?0:o.readings().size()-readings.size());
        if(o.reason()!=null) {
            require(o.reason().length()<=64,400,"INVALID_SETTINGS_REASON");
            detail.put("reason",o.reason());
        }
        var key=compatibility(s.get("vehicle_id"));
        var old=data.db.queryForList("SELECT observed_at FROM setting_profile WHERE account_id=? AND compatibility_key=? FOR UPDATE",s.get("account_id"),key);
        String state="SAVED";
        if(old.isEmpty()) {
            data.db.update("INSERT INTO setting_profile(account_id,compatibility_key,source_vehicle_id,observed_at,source," +
                "readings,observation_details,memory_reference) VALUES(?,?,?,?,?,?,?,?)",
                s.get("account_id"),key,s.get("vehicle_id"),stamp(o.observedAt()),source,
                Json.write(readings),Json.write(metadata),Json.write(memory));
        }
        else if(!instant(old.get(0).get("observed_at")).isAfter(o.observedAt())) {
            data.db.update("UPDATE setting_profile SET source_vehicle_id=?,observed_at=?,source=?,readings=?," +
                "observation_details=?,memory_reference=? WHERE account_id=? AND compatibility_key=?",
                s.get("vehicle_id"),stamp(o.observedAt()),source,Json.write(readings),Json.write(metadata),
                Json.write(memory),s.get("account_id"),key);
        }
        else {
            state="NEWER_PROFILE_KEPT";
        }
        return Map.of("id",activity(id,state,detail),"state",state,"detail",detail);
    }

    Map<String,Object> end(String id,Map<String,Object> input) {
        cancelPending(id,"SESSION_ENDED");
        if(input==null || input.isEmpty()) {
            return Map.of("state","NO_READINGS");
        }
        try {
            require(input.get("readings") instanceof Map<?,?>,400,"NO_SUPPORTED_READINGS");
            var mem=object(input.get("memoryReference"));
            MemoryReference ref=mem.isEmpty()?null:new MemoryReference(
                (String)mem.get("vehicleId"),number(mem.get("slot")).intValueExact(),(String)mem.get("referenceSource"),
                mem.get("expectedSeatPosition")==null?null:number(mem.get("expectedSeatPosition")));
            var o=new Observation(instant(input.get("observedAt")),(String)input.get("source"),
                object(input.get("readings")),object(input.get("readingDetails")),ref,"END_SNAPSHOT");
            return store(id,data.session(id,true),o,true);
        }
        catch(ApiError | java.time.DateTimeException | ClassCastException | ArithmeticException ignored) {
            return Map.of("id",activity(id,"END_SAVE_UNAVAILABLE",Map.of("reason","MISSING_OR_UNSUPPORTED_READINGS")),"state","END_SAVE_UNAVAILABLE");
        }
    }

    private void gate(Gate gate) {
        require(gate!=null && Boolean.TRUE.equals(gate.permissionGranted()),403,"VEHICLE_PERMISSION_REQUIRED");
        require(Boolean.TRUE.equals(gate.safeToOperate()),409,"SAFE_OPERATION_REQUIRED");
        safeAt(gate.observedAt(),data.env.getProperty("nextkey.settings.safety-age-seconds",Long.class,15L));
    }

    private boolean validMemory(Map<String,Object> memory,Map<String,Object> profile,Object vehicle,Object slot) {
        return Objects.equals(profile.get("source_vehicle_id"),vehicle) &&
            Objects.equals(memory.get("vehicleId"),vehicle) && memory.get("referenceSource")!=null &&
            Set.of("VERIFIED_EXISTING_VEHICLE_MEMORY_REFERENCE","CLIENT_REPORTED_EXISTING_MEMORY")
                .contains(memory.get("referenceSource")) && sameValue(memory.get("slot"),slot);
    }

    @Transactional
    public Map<String,Object> request(String id,Gate gate) {
        var s=data.active(id);
        gate(gate);
        var p=saved(s);
        require(!p.isEmpty(),409,"COMPATIBLE_PROFILE_REQUIRED");
        var previous=new LinkedHashMap<String,Object>();
        var carried=new TreeMap<String,Object>();
        var targets=new TreeMap<String,Object>(gate.desired()==null?object(p.get("readings")):gate.desired());
        require(!targets.isEmpty() && targets.size()<=16 && targets.keySet().stream().allMatch(NAMES::contains) &&
            targets.values().stream().allMatch(PersonalSettings::numeric),400,"INVALID_SETTING_TARGET");
        require(!targets.containsKey(SEAT) || gate.desired()==null,400,"READ_ONLY_SETTING");
        if(gate.retryOf()!=null) {
            var prior=load(id,gate.retryOf());
            require(TERMINAL.contains(prior.get("state")),409,"RETRY_NOT_AVAILABLE");
            previous.putAll(Json.read((String)prior.get("detail")));
            var oldTargets=object(previous.get("requested"));
            if(oldTargets.isEmpty()) {
                oldTargets=object(previous.get("desired"));
            }
            var priorTargets=oldTargets;
            var outcomes=object(previous.get("outcomes"));
            var retryFields=gate.fields()==null?oldTargets.keySet().stream().filter(k->!"MATCHED".equals(outcomes.get(k))).toList():gate.fields();
            require(!retryFields.isEmpty() && retryFields.size()<=16 && new HashSet<>(retryFields).size()==retryFields.size(),400,"INVALID_RETRY_FIELDS");
            require(retryFields.stream().allMatch(k->priorTargets.containsKey(k) && !"MATCHED".equals(outcomes.get(k))),400,"RETRY_MATCHED_FIELD");
            if(gate.desired()==null) {
                targets=new TreeMap<>(oldTargets);
            }
            require(gate.desired()==null || retryFields.containsAll(gate.desired().keySet()),400,"INVALID_RETRY_FIELDS");
            targets.keySet().retainAll(retryFields);
            require(targets.keySet().containsAll(retryFields),400,"INVALID_RETRY_FIELDS");
            object(previous.get("fieldResults")).forEach((k,v)-> {
                if(!retryFields.contains(k)) {
                    carried.put(k,v);
                }
            });
        }
        else {
            require(gate.fields()==null,400,"RETRY_APPLICATION_REQUIRED");
        }
        Map<String,Object> memory;
        if(gate.memoryReference()!=null) {
            memory=reference(gate.memoryReference(),s.get("vehicle_id"));
        } else if(gate.retryOf()!=null && previous.containsKey("memoryReference")) {
            memory=object(previous.get("memoryReference"));
        } else {
            memory=object(p.get("memory_reference"));
        }
        // A retry continues the original observation/application snapshot, not a later profile save.
        Object profileSource=gate.retryOf()!=null?
            previous.getOrDefault("profileSourceVehicleId",p.get("source_vehicle_id")):p.get("source_vehicle_id");
        var memoryProfile=new LinkedHashMap<>(p);
        memoryProfile.put("source_vehicle_id",profileSource);
        if(gate.retryOf()==null && gate.desired()==null && memory.get("slot")!=null &&
                validMemory(memory,memoryProfile,s.get("vehicle_id"),memory.get("slot"))) {
            targets.remove(SEAT);
            targets.put(SLOT,memory.get("slot"));
        }
        var caps=capabilityDetails(s.get("vehicle_id"));
        var desired=new TreeMap<String,Object>();
        var skipped=new ArrayList<String>();
        var fields=new TreeMap<String,Object>(carried);
        for(var e:targets.entrySet()) {
            String name=e.getKey(),error=null;
            var c=caps.get(name);
            if(SEAT.equals(name)) {
                error="READ_ONLY_SETTING";
            }
            else if(!access(c,"WRITE")) {
                error="WRITE_UNAVAILABLE";
            }
            else if(!allowed(c,e.getValue())) {
                error="OUT_OF_RANGE";
            }
            else if(SLOT.equals(name) && !validMemory(memory,memoryProfile,s.get("vehicle_id"),e.getValue())) {
                error="MEMORY_REFERENCE_INCOMPATIBLE";
            }
            if(error==null) {
                desired.put(name,e.getValue());
                fields.put(name,Map.of("requested",e.getValue(),"status","REQUESTED","attempted",false));
            }
            else {
                skipped.add(name);
                fields.put(name,Map.of("requested",e.getValue(),"status",error,"attempted",false));
            }
        }
        require(!desired.isEmpty() || gate.desired()!=null || gate.retryOf()!=null,409,"NO_COMPATIBLE_SETTINGS");
        var requested=new TreeMap<String,Object>(targets);
        carried.forEach((name,priorField) -> {
            Object target=object(priorField).get("requested");
            if(numeric(target)) {
                requested.putIfAbsent(name,target);
            }
        });
        var detail=new LinkedHashMap<String,Object>();
        detail.put("desired",desired);
        detail.put("requested",requested);
        detail.put("skipped",skipped);
        detail.put("fieldResults",fields);
        detail.put("memoryReference",memory);
        detail.put("profileSourceVehicleId",profileSource);
        detail.put("requestedAt",Instant.now());
        detail.put("verification","CLIENT_REPORTED");
        if(gate.retryOf()!=null) {
            detail.put("retryOf",gate.retryOf());
            detail.put("preservedFieldResults",carried);
            detail.put("previousReadback",previous.getOrDefault("readback",Map.of()));
        }
        String state="REQUESTED";
        if(desired.isEmpty()) {
            var finalOutcomes=outcomes(fields);
            state=finalOutcomes.containsValue("MATCHED")?"PARTIAL":"FAILED";
            detail.put("outcomes",finalOutcomes);
        }
        if("REQUESTED".equals(state)) {
            cancelPending(id,"SUPERSEDED");
        }
        return Map.of("id",activity(id,state,detail),"state",state,"detail",detail);
    }

    private Map<String,Object> load(String id,String app) {
        var rows=data.db.queryForList("SELECT id,state,created_at,detail FROM setting_activity WHERE id=? AND session_id=? FOR UPDATE",app,id);
        require(!rows.isEmpty(),404,"APPLICATION_NOT_FOUND");
        var r=rows.get(0);
        require(Json.read((String)r.get("detail")).containsKey("desired"),404,"APPLICATION_NOT_FOUND");
        return r;
    }

    private Map<String,Object> response(String id,Map<String,Object> r) {
        return Map.of("id",id,"state",r.get("state"),"detail",Json.read((String)r.get("detail")));
    }

    @Transactional
    public Map<String,Object> application(String id,String app) {
        data.session(id,false);
        return response(app,load(id,app));
    }

    Map<String,Object> applications(String id,int limit) {
        data.session(id,false);
        require(limit>=1 && limit<=100,400,"INVALID_PAGE");
        var rows=data.db.queryForList("SELECT id,state,created_at,detail FROM setting_activity WHERE session_id=? AND state IN ('REQUESTED','APPLYING','APPLIED','PARTIAL','FAILED','CANCELLED') ORDER BY created_at DESC,id DESC LIMIT ?",id,limit);
        return Map.of("applications",rows.stream().map(r->response((String)r.get("id"),r)).toList());
    }

    @Transactional
    public Map<String,Object> applying(String id,String app,Gate gate) {
        var s=data.active(id);
        var r=load(id,app);
        gate(gate);
        require(Set.of("REQUESTED","APPLYING").contains(r.get("state")),409,"INVALID_APPLY_TRANSITION");
        var detail=Json.read((String)r.get("detail"));
        var desired=object(detail.get("desired"));
        var caps=capabilityDetails(s.get("vehicle_id"));
        require(desired.entrySet().stream().allMatch(e->access(caps.get(e.getKey()),"WRITE") && allowed(caps.get(e.getKey()),e.getValue())),409,"CAPABILITIES_CHANGED");
        if(!"APPLYING".equals(r.get("state"))) {
            detail.put("applyingAt",Instant.now());
            detail.put("safetyObservedAt",gate.observedAt());
            update(app,"APPLYING",detail);
        }
        return Map.of("id",app,"state","APPLYING","detail",detail);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object v) {
        return v instanceof Map<?,?>?new LinkedHashMap<>((Map<String,Object>)v):new LinkedHashMap<>();
    }

    private static Map<String,Object> outcomes(Map<String,Object> fields) {
        var out=new TreeMap<String,Object>();
        fields.forEach((k,v)->out.put(k,object(v).get("status")));
        return out;
    }

    private void update(String app,String state,Map<String,Object> detail) {
        data.db.update("UPDATE setting_activity SET state=?,detail=? WHERE id=?",state,Json.write(detail),app);
    }

    @Transactional
    public Map<String,Object> readback(String id,String app,Readback result) {
        var s=data.active(id);
        var r=load(id,app);
        var detail=Json.read((String)r.get("detail"));
        if(TERMINAL.contains(r.get("state"))) {
            return response(app,r);
        }
        require("APPLYING".equals(r.get("state")),409,"INVALID_APPLY_TRANSITION");
        require(result!=null,400,"INVALID_READBACK");
        String source=data.source(result.source());
        require(result.observedAt()!=null && !result.observedAt().isBefore(instant(detail.get("applyingAt"))) &&
            !result.observedAt().isAfter(Instant.now()),400,"INVALID_READBACK_TIME");
        if(Boolean.FALSE.equals(result.permissionGranted()) || Boolean.FALSE.equals(result.safeToOperate())) {
            return cancelLoaded(app,r,new Cancel(Boolean.FALSE.equals(result.permissionGranted())?"PERMISSION_REVOKED":"UNSAFE_OPERATION",result.observedAt()));
        }
        if(result.permissionGranted()!=null || result.safeToOperate()!=null || result.safetyObservedAt()!=null) {
            require(Boolean.TRUE.equals(result.permissionGranted()) && Boolean.TRUE.equals(result.safeToOperate()),400,"INCOMPLETE_READBACK_SAFETY");
            safeAt(result.safetyObservedAt(),data.env.getProperty("nextkey.settings.safety-age-seconds",Long.class,15L));
        }
        var statuses=result.statuses()==null?Map.<String,String>of():result.statuses();
        require(statuses.size()<=16 && statuses.keySet().stream().allMatch(NAMES::contains) &&
            statuses.values().stream().allMatch(v->v!=null && FAILURE_STATUSES.contains(v)),400,"INVALID_READBACK_STATUS");
        var actual=observed(result.readings(),s.get("vehicle_id"),Map.of());
        var desired=object(detail.get("desired"));
        var fields=object(detail.get("fieldResults"));
        boolean legacyMemoryAcknowledged=false;
        for(var e:desired.entrySet()) {
            String name=e.getKey(),status=statuses.get(name);
            Object value=actual.get(name);
            if(status==null && SLOT.equals(name)) {
                status=statuses.get(SEAT);
                var ref=object(detail.get("memoryReference"));
                value=actual.get(SEAT);
                if(status==null && ref.get("expectedSeatPosition")!=null && value!=null) {
                    status=sameValue(ref.get("expectedSeatPosition"),value)?"MATCHED":"MISMATCH";
                }
                else if(status==null) {
                    status="UNVERIFIED";
                    legacyMemoryAcknowledged|=sameValue(e.getValue(),actual.get(SLOT));
                }
            }
            if(status==null) {
                status=value==null?"NO_SUPPORTED_READBACK":sameValue(e.getValue(),value)?"MATCHED":"MISMATCH";
            }
            var field=new LinkedHashMap<String,Object>();
            field.put("requested",e.getValue());
            field.put("status",status);
            field.put("attempted",true);
            if(value!=null) {
                field.put("actual",value);
            }
            if(SLOT.equals(name)) {
                var reference=object(detail.get("memoryReference"));
                field.put("actualSetting",SEAT);
                if(reference.get("expectedSeatPosition")!=null) {
                    field.put("comparisonTarget",reference.get("expectedSeatPosition"));
                }
            }
            field.put("observedAt",result.observedAt());
            fields.put(name,field);
        }
        var outcomes=outcomes(fields);
        long matched=outcomes.values().stream().filter("MATCHED"::equals).count();
        boolean noSkipped=((List<?>)detail.getOrDefault("skipped",List.of())).isEmpty();
        String state=matched==outcomes.size() && matched>0 && noSkipped?"APPLIED":
            matched>0 || legacyMemoryAcknowledged?"PARTIAL":"FAILED";
        detail.put("fieldResults",fields);
        detail.put("outcomes",outcomes);
        detail.put("readback",actual);
        detail.put("originalReadback",result.readings()==null?Map.of():result.readings());
        detail.put("readbackStatuses",statuses);
        detail.put("observedAt",result.observedAt());
        detail.put("source",source);
        detail.put("verification","CLIENT_REPORTED");
        detail.put("matchedCount",matched);
        detail.put("profileUpdated",false);
        update(app,state,detail);
        return Map.of("id",app,"state",state,"detail",detail);
    }

    @Transactional
    public Map<String,Object> cancel(String id,String app,Cancel input) {
        data.session(id,true);
        return cancelLoaded(app,load(id,app),input);
    }

    private Map<String,Object> cancelLoaded(String app,Map<String,Object> r,Cancel input) {
        if(TERMINAL.contains(r.get("state"))) {
            return response(app,r);
        }
        require(input!=null && input.reason()!=null && input.reason().matches("[A-Z_]{1,64}"),400,"INVALID_CANCELLATION_REASON");
        require(input.observedAt()!=null && !input.observedAt().isAfter(Instant.now()),400,"INVALID_CANCELLATION_TIME");
        var detail=Json.read((String)r.get("detail"));
        var fields=object(detail.get("fieldResults"));
        require(detail.get("requestedAt")==null || !input.observedAt().isBefore(instant(detail.get("requestedAt"))),400,"INVALID_CANCELLATION_TIME");
        object(detail.get("desired")).forEach((k,v)-> {
            var field=object(fields.get(k));
            field.put("status","CANCELLED");
            field.put("reason",input.reason());
            fields.put(k,field);
        });
        detail.put("fieldResults",fields);
        detail.put("outcomes",outcomes(fields));
        detail.put("cancelReason",input.reason());
        detail.put("cancelledAt",input.observedAt());
        update(app,"CANCELLED",detail);
        return Map.of("id",app,"state","CANCELLED","detail",detail);
    }

    private void cancelPending(String id,String reason) {
        for(var r:data.db.queryForList("SELECT id,state,detail FROM setting_activity WHERE session_id=? AND state IN ('REQUESTED','APPLYING') FOR UPDATE",id)) {
            cancelLoaded((String)r.get("id"),r,new Cancel(reason,Instant.now()));
        }
    }
}

@Component
@Profile("dev & !prod")
class DemoCapabilities {
    private final RecordData data;
    DemoCapabilities(RecordData data) {
        this.data=data;
    }
    @EventListener(ApplicationReadyEvent.class) void populate() {
        for(String car:List.of("demo-car","demo-car-2")) {
            for(var e:Map.of("CLIMATE_TARGET_C",List.of(20,21,22,23,24),
                    "CLIMATE_FAN_LEVEL",List.of(0,1,2,3),"SEAT_MEMORY_SLOT",List.of(1,2)).entrySet()) {
                data.db.update("INSERT INTO vehicle_setting_capability(vehicle_id,setting_name,allowed_values) " +
                    "SELECT id,?,? FROM vehicle WHERE id=? AND model='VIRTUAL-EV' AND compatibility_key='DEMO-EV' " +
                    "AND NOT EXISTS (SELECT 1 FROM vehicle_setting_capability WHERE vehicle_id=? AND setting_name=?)",
                    e.getKey(),Json.write(Map.of("values",e.getValue())),car,car,e.getKey());
            }
        }
    }
}
