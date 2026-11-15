package pleos.nextkey.records;

import static pleos.nextkey.records.RecordData.require;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.Json;

@Service
class RecordRequests {
    private final RecordData data;
    RecordRequests(RecordData data) { this.data=data; }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @Transactional
    public Map<String,Object> once(String id,String operation,String key,Object body,Supplier<Map<String,Object>> action) {
        if (key==null) return action.get();
        require(key.matches("[A-Za-z0-9_-]{1,64}"),400,"INVALID_IDEMPOTENCY_KEY");
        if ("SAVE_SETTINGS".equals(operation)) data.lockProfileOwner(id);
        data.session(id,true);
        String digest=hash(Json.writeSorted(body).getBytes(StandardCharsets.UTF_8));
        var memo=data.db.queryForList("SELECT request_hash,result FROM record_request WHERE session_id=? AND operation_name=? AND request_id=?",
            id,operation,key);
        if (!memo.isEmpty()) {
            Object stored=memo.get(0).get("request_hash");
            // Existing requests used the original map order. Preserve their exact-payload replay contract.
            boolean matches=digest.equals(stored) || hash(Json.write(body).getBytes(StandardCharsets.UTF_8)).equals(stored);
            require(matches,409,"IDEMPOTENCY_KEY_CONFLICT");
            return Json.read((String)memo.get(0).get("result"));
        }
        var result=action.get();
        data.db.update("INSERT INTO record_request(session_id,operation_name,request_id,request_hash,result) VALUES(?,?,?,?,?)",
            id,operation,key,digest,Json.write(result));
        return result;
    }
}
