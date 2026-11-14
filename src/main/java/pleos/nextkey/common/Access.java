package pleos.nextkey.common;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Access {
    private final JdbcTemplate db;

    public Access(JdbcTemplate db) { this.db = db; }

    public Map<String,Object> session(String id, Actor actor) {
        actor.require("CUSTOMER", "SESSION");
        var rows = db.queryForList("SELECT * FROM rental_session WHERE id=?", id);
        if (rows.isEmpty()) throw new ApiError(404, "SESSION_NOT_FOUND");
        var row = rows.get(0);
        boolean allowed = switch (actor.role()) {
            case "CUSTOMER" -> actor.id().equals(row.get("account_id"));
            case "SESSION" -> actor.id().equals(id);
            default -> false;
        };
        if (!allowed) throw new ApiError(403, "FORBIDDEN");
        return row;
    }
}
