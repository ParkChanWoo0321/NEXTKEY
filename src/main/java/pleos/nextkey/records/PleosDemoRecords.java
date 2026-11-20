package pleos.nextkey.records;

import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.Json;
import static pleos.nextkey.records.RecordData.*;

/** The development phone reports through the same damage validation and idempotency path as the customer API. */
@Service
@Profile("dev & !prod")
public class PleosDemoRecords {
    private final RecordData data;
    private final DamageRecords damage;
    private final RecordRequests requests;

    PleosDemoRecords(RecordData data,DamageRecords damage,RecordRequests requests) {
        this.data=data;this.damage=damage;this.requests=requests;
    }

    @Transactional
    public Map<String,Object> incomingDamage(String session,String requestId) {
        Actor phone=Actor.current();phone.require("CUSTOMER");
        var rental=data.session(session,true);
        require(phone.id().equals(rental.get("account_id")),403,"DEMO_WRONG_CUSTOMER");
        var owner=data.db.queryForMap("SELECT is_demo FROM account WHERE id=?",phone.id());
        require(phone.id().startsWith("pleos-")&&flag(owner.get("is_demo")),403,"DEMO_ACCOUNT_REQUIRED");
        // The preset has no editable report payload. Repeated requests return the original committed result.
        var memo=data.db.queryForList("SELECT result FROM record_request WHERE session_id=? AND operation_name='DAMAGE' AND request_id=?",
                session,requestId);
        if(!memo.isEmpty())return Json.read((String)memo.get(0).get("result"));
        require("ACTIVE".equals(rental.get("state")),409,"SESSION_NOT_ACTIVE");
        Instant now=Instant.now();
        var input=new DamageRecords.Submission("SCRATCH","가상 휴대폰 신규 제보. 발생 시점이나 책임을 확정하지 않습니다.",
                now,true,now,"RIGHT_FRONT");
        return requests.once(session,"DAMAGE",requestId,input,()->damage.create(session,input));
    }
}
