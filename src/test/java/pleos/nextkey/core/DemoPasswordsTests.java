package pleos.nextkey.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoPasswordsTests {
    private final MockEnvironment config = new MockEnvironment();
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private void seed() {
        new DemoData(db,config,encoder).afterSingletonsInstantiated();
    }

    private void configureCloudSecrets() {
        config.setProperty("K_SERVICE","nextkey-test");
        for (String role : List.of("CUSTOMER","CUSTOMER2","DEVICE","DEVICE2","OPERATOR")) {
            config.setProperty("NEXTKEY_DEV_" + role + "_PASSWORD", UUID.randomUUID().toString());
        }
    }

    @Test void cloudRunRejectsSharedFallbackAndDuplicateSecretsBeforeDatabaseAccess() {
        config.setProperty("K_SERVICE","nextkey-test");
        config.setProperty("NEXTKEY_DEV_PASSWORD",UUID.randomUUID().toString());
        assertTrue(assertThrows(IllegalStateException.class,this::seed).getMessage().contains("NEXTKEY_DEV_CUSTOMER_PASSWORD"));
        configureCloudSecrets();
        config.setProperty("NEXTKEY_DEV_OPERATOR_PASSWORD", config.getRequiredProperty("NEXTKEY_DEV_CUSTOMER_PASSWORD"));
        assertTrue(assertThrows(IllegalStateException.class,this::seed).getMessage().contains("distinct"));
        verifyNoInteractions(db);
    }

    @Test void cloudRunRejectsOldSharedFixtureWithoutOverwritingItsHash() {
        configureCloudSecrets();
        String find = "SELECT id,is_demo,role,username,password_hash FROM account WHERE id=? OR username=? FOR UPDATE";
        when(db.queryForList(find,"demo-customer","demo-customer")).thenReturn(List.of(Map.of(
                "id","demo-customer","is_demo",true,"role","CUSTOMER","username","demo-customer",
                "password_hash",encoder.encode(UUID.randomUUID().toString()))));
        assertTrue(assertThrows(IllegalStateException.class,this::seed).getMessage().contains("demo-customer"));
        verify(db).queryForList(find,"demo-customer","demo-customer");
        verifyNoMoreInteractions(db);
    }

    @Test void newCloudFixturesUseTheMatchingAccountSecret() {
        configureCloudSecrets();
        seed();
        String sql = "INSERT INTO account(id,role,username,password_hash,is_demo) VALUES (?,?,?,?,TRUE)";
        Map<String,String> identities = Map.of("demo-customer","CUSTOMER","demo-customer-2","CUSTOMER2",
                "demo-car","DEVICE","demo-car-2","DEVICE2","demo-operator","OPERATOR");
        identities.forEach((id, variable) -> {
            String secret = config.getRequiredProperty("NEXTKEY_DEV_" + variable + "_PASSWORD");
            verify(db).update(eq(sql),eq(id),anyString(),eq(id),argThat((String value) -> encoder.matches(secret,value)));
        });
    }
}
