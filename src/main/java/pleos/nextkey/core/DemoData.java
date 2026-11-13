package pleos.nextkey.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static pleos.nextkey.core.CoreData.*;

@Component
@Profile("dev & !prod")
class DemoData implements SmartInitializingSingleton {
    private record Fixture(String id, String role, String passwordVariable) {}
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("demo-customer", "CUSTOMER", "NEXTKEY_DEV_CUSTOMER_PASSWORD"),
            new Fixture("demo-customer-2", "CUSTOMER", "NEXTKEY_DEV_CUSTOMER2_PASSWORD"),
            new Fixture("demo-car", "VEHICLE", "NEXTKEY_DEV_DEVICE_PASSWORD"),
            new Fixture("demo-car-2", "VEHICLE", "NEXTKEY_DEV_DEVICE2_PASSWORD"),
            new Fixture("demo-operator", "OPERATOR", "NEXTKEY_DEV_OPERATOR_PASSWORD"));
    private final JdbcTemplate db;
    private final Environment env;
    private final PasswordEncoder encoder;
    DemoData(JdbcTemplate db,Environment env,PasswordEncoder encoder){
        this.db=db;this.env=env;this.encoder=encoder;
    }
    @Override @Transactional
    public void afterSingletonsInstantiated() {
        boolean cloudRun = env.containsProperty("K_SERVICE");
        Map<String, String> passwords = new HashMap<>();
        for (Fixture fixture : FIXTURES) {
            String variable = cloudRun ? fixture.passwordVariable() : "NEXTKEY_DEV_PASSWORD";
            String password = env.getProperty(variable);
            if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException(variable + " requires at least 12 characters and at most 72 UTF-8 bytes");
            }
            passwords.put(fixture.id(), password);
        }
        if (cloudRun && new HashSet<>(passwords.values()).size() != FIXTURES.size()) {
            throw new IllegalStateException("Cloud Run development accounts require distinct passwords");
        }
        for (Fixture fixture : FIXTURES) {
            String key=fixture.id();
            String password = passwords.get(key);
            var existing=first(db,"SELECT id,is_demo,role,username,password_hash FROM account WHERE id=? OR username=? FOR UPDATE",key,key);
            if(existing.isEmpty()){
                db.update("INSERT INTO account(id,role,username,password_hash,is_demo) VALUES (?,?,?,?,TRUE)",
                        key,fixture.role(),key,encoder.encode(password));
            } else if(!Boolean.TRUE.equals(existing.get("is_demo")) || !key.equals(existing.get("id"))
                    || !key.equals(existing.get("username")) || !fixture.role().equals(existing.get("role"))){
                throw new IllegalStateException("Development fixture identifier conflicts with an existing account");
            } else if (cloudRun && !encoder.matches(password, (String) existing.get("password_hash"))) {
                throw new IllegalStateException("Stored credential differs from the configured Cloud Run credential for " + key);
            }
        }
    }
}
