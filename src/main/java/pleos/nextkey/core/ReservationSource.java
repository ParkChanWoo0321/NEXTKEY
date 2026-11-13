package pleos.nextkey.core;

import java.util.Map;

/** An external provider must perform its own authoritative validation/update, or throw.
 * Returned booking is the authoritative result, including the confirmed end time. */
public interface ReservationSource {
    ReservationView verify(ReservationView reservation);
    ReservationView change(ReservationView requested);
}
