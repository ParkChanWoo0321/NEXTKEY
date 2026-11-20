package pleos.nextkey.core;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import pleos.nextkey.common.Actor;
import pleos.nextkey.records.PleosDemoRecords;
import pleos.nextkey.lifecycle.DevelopmentReportFaults;
import static org.junit.jupiter.api.Assertions.*;

class PleosDemoBoundaryTests {
    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }

    @Test void productionWinsEvenWhenDevelopmentIsAlsoSelected() {
        for(String[] profiles:new String[][]{{"prod"},{"dev","prod"}})try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.register(PleosDemoController.class,PleosDemoService.class,PleosDemoRecords.class,DevelopmentReportFaults.class,DevelopmentOverdueDeadline.class);
            context.refresh();
            assertEquals(0,context.getBeanNamesForType(PleosDemoController.class).length);
            assertEquals(0,context.getBeanNamesForType(PleosDemoService.class).length);
            assertEquals(0,context.getBeanNamesForType(PleosDemoRecords.class).length);
            assertEquals(0,context.getBeanNamesForType(DevelopmentReportFaults.class).length);
            assertEquals(0,context.getBeanNamesForType(DevelopmentOverdueDeadline.class).length);
        }
    }

    @Test void phoneOrOfficeFailureAlwaysRestoresTheRealCallerContext() {
        var original=SecurityContextHolder.createEmptyContext();
        var vehicle=new Actor("vehicle-caller","VEHICLE");
        original.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(vehicle,null,java.util.List.of()));
        SecurityContextHolder.setContext(original);
        assertThrows(IllegalStateException.class,()->PleosDemoService.as(new Actor("demo-phone","CUSTOMER"),()->{
            assertEquals("CUSTOMER",Actor.current().role());
            SecurityContextHolder.clearContext();
            throw new IllegalStateException("simulated provider failure");
        }));
        assertSame(original,SecurityContextHolder.getContext());
        assertEquals(vehicle,Actor.current());
    }

    @Test void aMissingStartNeverInventsZeroAndFuelNeverPretendsToBeBattery() {
        var reading=PleosDemoService.sample("FUEL",Instant.now(),true);
        assertFalse(reading.containsKey("odometer"));
        assertFalse(reading.containsKey("battery"));
        assertTrue(reading.containsKey("fuel"));
        assertEquals("INVALID_DEMO_RUN",assertThrows(pleos.nextkey.common.ApiError.class,
                ()->PleosDemoService.names("demo-car")).code());
    }
}
