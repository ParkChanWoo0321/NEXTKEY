package pleos.nextkey.core;

import java.time.Instant;

/** Coordinates are WGS84 degrees. A location may have only a name/address. */
public record ReservationView(String id,String accountId,String vehicleId,String status,Instant startsAt,
                              Instant confirmedEndAt,Place destination,Place returnPlace) {
    public record Place(String name,String address,Double latitude,Double longitude,String coordinateStatus) {}
}
