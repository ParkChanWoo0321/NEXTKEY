package pleos.nextkey.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;

/** A production integration must authenticate the desk, validate the authoritative contract and
 * return only on a confirmed result. Repeated calls must use the supplied business identifiers. */
interface RentalOffice {
    void offer(String extension, BigDecimal amount, String currency);
    void decide(String extension, String reservation, Instant requestedEnd, boolean approved);
    void replace(String oldSession, String newReservation);
    void confirmReturn(String session);
}

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name="nextkey.reservations.source",havingValue="LOCAL",matchIfMissing=true)
final class DevelopmentRentalOffice implements RentalOffice {
    // The dev operator supplies the quote and decision. No payment or external call is made.
    public void offer(String extension, BigDecimal amount, String currency) {}
    public void decide(String extension, String reservation, Instant requestedEnd, boolean approved) {}
    public void replace(String oldSession, String newReservation) {}
    public void confirmReturn(String session) {}
}
