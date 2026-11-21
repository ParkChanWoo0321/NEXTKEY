package pleos.nextkey.core;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevelopmentReviewBoundaryTests {
    @Test void disabledAndProductionProfilesNeverRegisterTheAdapter() {
        for (String[] profiles : new String[][]{{"dev"},{"prod"},{"dev","prod"}}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles(profiles);
                boolean production = java.util.List.of(profiles).contains("prod");
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("review",
                        Map.of("nextkey.dev.review.handoff",Boolean.toString(production))));
                context.register(DevelopmentReviewHandoff.class);
                context.refresh();
                assertEquals(0,context.getBeanNamesForType(DevelopmentReviewHandoff.class).length);
            }
        }
    }

    @Test void externalSourceAndUnrelatedIdentitiesNeverWriteLocalReviewReservations() {
        var db = mock(JdbcTemplate.class);
        var env = new MockEnvironment();
        env.setActiveProfiles("dev");
        var adapter = new DevelopmentReviewHandoff(db,env);
        env.setProperty("nextkey.reservations.source","EXTERNAL");
        assertEquals("test",adapter.resolve("test","demo-customer","demo-car"));
        env.setProperty("nextkey.reservations.source","LOCAL");
        assertEquals("test",adapter.resolve("test","other-customer","demo-car"));
        assertEquals("test",adapter.resolve("test","demo-customer","other-car"));
        assertEquals("unrelated",adapter.resolve("unrelated","test2","demo-car"));
        env.setActiveProfiles("dev","prod");
        assertEquals("test",adapter.resolve("test","demo-customer","demo-car"));
        verifyNoInteractions(db);
    }
}
