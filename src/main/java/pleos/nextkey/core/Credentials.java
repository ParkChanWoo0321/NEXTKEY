package pleos.nextkey.core;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pleos.nextkey.common.Actor;
import pleos.nextkey.common.ApiError;
import static pleos.nextkey.core.CoreData.*;

@Service
class Credentials {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    private final int tokenMinutes;
    private final boolean allowDemo;
    Credentials(JdbcTemplate db, PasswordEncoder passwords, org.springframework.core.env.Environment env,
                @Value("${nextkey.auth.token-minutes:120}") int tokenMinutes) {
        this.db=db;
        this.passwords=passwords;
        this.allowDemo=env.matchesProfiles("dev & !prod");
        this.dummyHash=passwords.encode(secret());
        if (tokenMinutes < 1 || tokenMinutes > 1440) throw new IllegalArgumentException("Invalid token lifetime");
        this.tokenMinutes=tokenMinutes;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=ApiError.class)
    public Actor authenticate(String username, String password) {
        if (username == null || password == null || username.length()>128 || password.length()>128
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw fail(401,"INVALID_CREDENTIALS");
        var row=first(db,"SELECT * FROM account WHERE username=? AND enabled=TRUE AND (? OR is_demo=FALSE) FOR UPDATE", username,allowDemo);
        if (row.isEmpty()) {
            passwords.matches(password,dummyHash);
            throw fail(401,"INVALID_CREDENTIALS");
        }
        var until=row.get("blocked_until");
        if (until!=null && instant(until).isAfter(Instant.now())) throw fail(429,"LOGIN_ATTEMPT_LIMIT");
        if (!passwords.matches(password,string(row,"password_hash"))) {
            int n=until==null ? ((Number)row.get("failed_attempts")).intValue()+1 : 1;
            db.update("UPDATE account SET failed_attempts=?,blocked_until=? WHERE id=?", n,
                    n>=5 ? sql(Instant.now().plusSeconds(300)) : null,row.get("id"));
            throw fail(401,"INVALID_CREDENTIALS");
        }
        db.update("UPDATE account SET failed_attempts=0,blocked_until=NULL WHERE id=?",row.get("id"));
        return new Actor(string(row,"id"),string(row,"role"));
    }

    String issue(String accountId,String sessionId) {
        String token=secret();
        db.update("INSERT INTO auth_token (token_hash,account_id,session_id,expires_at,dev_issued) VALUES (?,?,?,?,?)",
                hash(token),accountId,sessionId,sql(Instant.now().plusSeconds(60L*tokenMinutes)),allowDemo);
        return token;
    }

    Actor identify(String token, String path, String method) {
        if (token.length() != 43) return null;
        var row = first(db, """
            SELECT a.id, a.role FROM auth_token t
            JOIN account a ON a.id=t.account_id AND a.enabled=TRUE
            WHERE t.token_hash=? AND t.session_id IS NULL AND t.expires_at>UTC_TIMESTAMP(6)
              AND t.dev_issued=? AND (? OR a.is_demo=FALSE)
            """, hash(token), allowDemo, allowDemo);
        if (row.isEmpty()) return null;
        return new Actor(string(row, "id"), string(row, "role"));
    }
}
