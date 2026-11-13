package pleos.nextkey.core;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import static pleos.nextkey.core.CoreData.fail;

@Configuration
class ReservationProviderConfig {
    @Bean
    @ConditionalOnMissingBean(ReservationSource.class)
    ReservationSource reservationSource(Environment env) {
        String defaultSource = env.matchesProfiles("dev & !prod") ? "LOCAL" : "EXTERNAL";
        boolean local = "LOCAL".equalsIgnoreCase(env.getProperty("nextkey.reservations.source", defaultSource));
        return new ReservationSource() {
            private ReservationView authoritative(ReservationView view) {
                if (!local) throw fail(503, "RESERVATION_PROVIDER_UNAVAILABLE");
                return view;
            }

            public ReservationView verify(ReservationView view) { return authoritative(view); }
            public ReservationView change(ReservationView view) { return authoritative(view); }
        };
    }
}
