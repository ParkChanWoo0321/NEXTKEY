package pleos.nextkey.core;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import pleos.nextkey.common.ApiError;
import static org.junit.jupiter.api.Assertions.*;

class ProviderBoundaryTests {
    @Test void prodWinsEvenIfDevSelectedAndExternalProviderUnavailable(){
        try(var ctx=new AnnotationConfigApplicationContext()){
            ctx.getEnvironment().setActiveProfiles("prod","dev");
            String unusedProof=Integer.toString(new java.security.SecureRandom().nextInt(900000)+100000);
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("settings",
                    Map.of("NEXTKEY_DEV_OTP",unusedProof)));
            ctx.register(ProviderConfig.class, ReservationProviderConfig.class);
            ctx.refresh();
            var factor=ctx.getBean(IdentityFactor.class);
            assertEquals("IDENTITY_PROVIDER_UNAVAILABLE",assertThrows(ApiError.class,
                    ()->factor.verify("account","challenge",unusedProof)).code());
            assertEquals("RESERVATION_PROVIDER_UNAVAILABLE",assertThrows(ApiError.class,
                    ()->ctx.getBean(ReservationSource.class).verify(null)).code());
        }
    }
}
