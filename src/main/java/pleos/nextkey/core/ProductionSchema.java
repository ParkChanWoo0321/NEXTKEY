package pleos.nextkey.core;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Run after Flyway initializes its tables and before the servlet container starts accepting clients. */
@Component
@Profile("!dev | prod")
class ProductionSchema implements SmartInitializingSingleton {
    private final JdbcTemplate db;

    ProductionSchema(JdbcTemplate db) { this.db=db; }

    @Override
    public void afterSingletonsInstantiated() {
        Integer marked=db.queryForObject("SELECT EXISTS(SELECT 1 FROM account WHERE is_demo=TRUE)",Integer.class);
        if (Integer.valueOf(1).equals(marked))
            throw new IllegalStateException("DEVELOPMENT_SCHEMA_NOT_ALLOWED");
    }
}
