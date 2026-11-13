package pleos.nextkey.core;

import org.springframework.web.bind.annotation.*;
import pleos.nextkey.common.Actor;
import static pleos.nextkey.core.CoreData.fail;

@RestController
@RequestMapping("/api")
class ReservationController {
    private final BookingService bookings;

    ReservationController(BookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping("/reservations")
    Object reservations() { return bookings.list(Actor.current()); }

    @GetMapping("/reservations/{id}")
    Object reservation(@PathVariable String id) {
        return bookings.authorize(bookings.lookup(id, false), Actor.current());
    }

    @PostMapping("/operator/reservations")
    Object provision(@RequestBody ReservationView input) {
        Actor actor = Actor.current();
        actor.require("OPERATOR");
        return bookings.create(actor, input);
    }

    @PostMapping("/reservations")
    Object create(@RequestBody ReservationView input) {
        Actor actor = Actor.current();
        actor.require("CUSTOMER");
        if (input.accountId() != null && !input.accountId().equals(actor.id())) {
            throw fail(403, "RESERVATION_FORBIDDEN");
        }
        return bookings.create(actor, new ReservationView(null, actor.id(), input.vehicleId(), null,
                input.startsAt(), input.confirmedEndAt(), input.destination(), null));
    }

    @PutMapping("/reservations/{id}/destination")
    Object destination(@PathVariable String id, @RequestBody(required = false) ReservationView.Place place) {
        return bookings.update(id, Actor.current(), "destination", place);
    }

    @PutMapping("/operator/reservations/{id}/return-place")
    Object returnPlace(@PathVariable String id, @RequestBody(required = false) ReservationView.Place place) {
        return bookings.update(id, Actor.current(), "return", place);
    }

    @PostMapping("/operator/reservations/{id}/cancel")
    Object cancel(@PathVariable String id) {
        return bookings.update(id, Actor.current(), "cancel", null);
    }
}
