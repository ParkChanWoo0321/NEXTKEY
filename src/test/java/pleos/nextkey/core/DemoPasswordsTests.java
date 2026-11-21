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
        config.setProperty("NEXTKEY_DEV_SIMPLE_CUSTOMER_PASSWORD","test");
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

    @Test void explicitSimpleCustomerLoginPreservesTheCustomerIdAndResetsLoginLockOnChange() {
        configureCloudSecrets();
        config.setProperty("NEXTKEY_DEV_SIMPLE_CUSTOMER_LOGIN","true");
        String find = "SELECT id,is_demo,role,username,password_hash FROM account WHERE id=? OR username=? FOR UPDATE";
        when(db.queryForList(find,"demo-customer","test")).thenReturn(List.of(Map.of(
                "id","demo-customer","is_demo",true,"role","CUSTOMER","username","demo-customer",
                "password_hash",encoder.encode(UUID.randomUUID().toString()))));

        seed();

        verify(db).update(eq("UPDATE account SET username=?,password_hash=?,failed_attempts=0,blocked_until=NULL WHERE id=?"),
                eq("test"),argThat((String hash) -> encoder.matches("test",hash)),eq("demo-customer"));
        verify(db,never()).update(eq("INSERT INTO account(id,role,username,password_hash,is_demo) VALUES (?,?,?,?,TRUE)"),
                eq("demo-customer"),anyString(),anyString(),anyString());
    }

    @Test void shortCustomerPasswordStillRequiresTheExplicitSimpleLoginMode() {
        configureCloudSecrets();
        config.setProperty("NEXTKEY_DEV_CUSTOMER_PASSWORD","test");

        assertTrue(assertThrows(IllegalStateException.class,this::seed).getMessage()
                .contains("NEXTKEY_DEV_CUSTOMER_PASSWORD"));

        verifyNoInteractions(db);
    }

    @Test void simpleCustomerLoginCreatesOnlyThePrimaryCustomerWithTheShortPassword() {
        configureCloudSecrets();
        config.setProperty("NEXTKEY_DEV_SIMPLE_CUSTOMER_LOGIN","true");

        seed();

        String insert = "INSERT INTO account(id,role,username,password_hash,is_demo) VALUES (?,?,?,?,TRUE)";
        verify(db).update(eq(insert),eq("demo-customer"),eq("CUSTOMER"),eq("test"),
                argThat((String hash) -> encoder.matches("test",hash)));
        verify(db).update(eq(insert),eq("demo-car"),eq("VEHICLE"),eq("demo-car"),argThat((String hash) ->
                encoder.matches(config.getRequiredProperty("NEXTKEY_DEV_DEVICE_PASSWORD"),hash)));
        config.setProperty("NEXTKEY_DEV_OPERATOR_PASSWORD","test");
        clearInvocations(db);
        assertThrows(IllegalStateException.class,this::seed);
        verifyNoInteractions(db);
    }

    @Test void restartingAnAlreadyConfiguredSimpleCustomerDoesNotRewriteItsHashOrUnlockIt() {
        configureCloudSecrets();
        config.setProperty("NEXTKEY_DEV_SIMPLE_CUSTOMER_LOGIN","true");
        String find = "SELECT id,is_demo,role,username,password_hash FROM account WHERE id=? OR username=? FOR UPDATE";
        when(db.queryForList(find,"demo-customer","test")).thenReturn(List.of(Map.of(
                "id","demo-customer","is_demo",true,"role","CUSTOMER","username","test",
                "password_hash",encoder.encode("test"))));

        seed();

        verify(db,never()).update(eq("UPDATE account SET username=?,password_hash=?,failed_attempts=0,blocked_until=NULL WHERE id=?"),
                anyString(),anyString(),anyString());
    }

    @Test void aDifferentAccountUsingTestAndMultipleCollisionRowsAreNeverOverwritten() {
        configureCloudSecrets();
        config.setProperty("NEXTKEY_DEV_SIMPLE_CUSTOMER_LOGIN","true");
        String find = "SELECT id,is_demo,role,username,password_hash FROM account WHERE id=? OR username=? FOR UPDATE";
        var other = Map.<String,Object>of("id","someone-else","is_demo",true,"role","CUSTOMER",
                "username","test","password_hash",encoder.encode("test"));
        when(db.queryForList(find,"demo-customer","test")).thenReturn(List.of(other));
        assertThrows(IllegalStateException.class,this::seed);
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("update")));

        clearInvocations(db);
        var owner = Map.<String,Object>of("id","demo-customer","is_demo",true,"role","CUSTOMER",
                "username","demo-customer","password_hash",encoder.encode(UUID.randomUUID().toString()));
        when(db.queryForList(find,"demo-customer","test")).thenReturn(List.of(owner,other));
        assertThrows(IllegalStateException.class,this::seed);
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("update")));
    }

    @Test void anotherInvalidFixtureIsRejectedBeforeChangingThePrimaryCustomer() {
        configureCloudSecrets();
        config.setProperty("NEXTKEY_DEV_SIMPLE_CUSTOMER_LOGIN","true");
        String find = "SELECT id,is_demo,role,username,password_hash FROM account WHERE id=? OR username=? FOR UPDATE";
        when(db.queryForList(find,"demo-customer","test")).thenReturn(List.of(Map.of(
                "id","demo-customer","is_demo",true,"role","CUSTOMER","username","demo-customer",
                "password_hash",encoder.encode(UUID.randomUUID().toString()))));
        when(db.queryForList(find,"demo-operator","demo-operator")).thenReturn(List.of(Map.of(
                "id","demo-operator","is_demo",true,"role","OPERATOR","username","demo-operator",
                "password_hash",encoder.encode(UUID.randomUUID().toString()))));

        assertThrows(IllegalStateException.class,this::seed);

        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("update")));
    }
}
