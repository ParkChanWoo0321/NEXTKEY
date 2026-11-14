package pleos.nextkey.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import static pleos.nextkey.core.CoreData.fail;

@Configuration
class ProviderConfig {
    @Bean @Profile("dev & !prod") @ConditionalOnMissingBean(IdentityFactor.class)
    IdentityFactor demoFactor(Environment env) {
        String expected=env.getRequiredProperty("NEXTKEY_DEV_OTP");
        if (!expected.matches("[0-9]{6,12}")) throw new IllegalStateException("NEXTKEY_DEV_OTP requires 6-12 digits");
        return new IdentityFactor(){
            public void begin(String account,String challenge) {}
            public boolean verify(String account,String challenge,String code){
                return code!=null && code.length()<=12 && MessageDigest.isEqual(
                        code.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    @Bean @Profile("!dev | prod") @ConditionalOnMissingBean(IdentityFactor.class)
    IdentityFactor unavailableFactor() {
        return new IdentityFactor(){
            public void begin(String account,String challenge){ throw fail(503,"IDENTITY_PROVIDER_UNAVAILABLE"); }
            public boolean verify(String account,String challenge,String code){ throw fail(503,"IDENTITY_PROVIDER_UNAVAILABLE"); }
        };
    }
}
