package io.github.mrzdream.campusflow.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.mrzdream.campusflow.web.ApiFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import static io.github.mrzdream.campusflow.booking.BookingModels.*;

@RestController
@RequestMapping("/api/v1")
class BookingController {
    private final BookingService bookings;
    private final BookingStore store;
    private final ObjectProvider<RedisBookingSupport> redisSupport;

    BookingController(BookingService bookings, BookingStore store, ObjectProvider<RedisBookingSupport> redisSupport) {
        this.bookings = bookings;
        this.store = store;
        this.redisSupport = redisSupport;
    }

    @PostMapping("/resources/{resourceId}/slots")
    @ResponseStatus(HttpStatus.CREATED)
    Slot createSlot(@PathVariable @Positive long resourceId, @Valid @RequestBody NewSlot input) {
        return bookings.createSlot(resourceId, input.startsAt(), input.endsAt(), input.capacity());
    }

    @GetMapping("/resources/{resourceId}/slots")
    List<Slot> upcoming(@PathVariable @Positive long resourceId) {
        var redis = redisSupport.getIfAvailable();
        return redis == null ? bookings.upcoming(resourceId) : redis.upcoming(resourceId);
    }

    @GetMapping("/resources/{resourceId}/slots/{slotId}")
    Slot slot(@PathVariable @Positive long resourceId, @PathVariable @Positive long slotId) {
        return bookings.getSlot(resourceId, slotId);
    }

    @PatchMapping("/resources/{resourceId}/slots/{slotId}/status")
    Slot slotStatus(@PathVariable @Positive long resourceId, @PathVariable @Positive long slotId,
            @Valid @RequestBody ChangeStatus input) {
        return bookings.setSlotStatus(resourceId, slotId, input.status());
    }

    @PatchMapping("/resources/{resourceId}/status")
    ChangeStatus resourceStatus(@PathVariable @Positive long resourceId, @Valid @RequestBody ChangeStatus input) {
        return new ChangeStatus(bookings.setResourceStatus(resourceId, input.status()));
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    Order reserve(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SlotSelection input,
            @RequestParam(defaultValue = "DB") Mode mode) {
        long userId = userId(jwt);
        if (mode == Mode.DB) {
            return bookings.reserve(userId, input.slotId());
        }
        RedisBookingSupport redis = redisSupport.getIfAvailable();
        if (redis == null) {
            throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "REDIS_MODE_DISABLED", "Redis mode is disabled.");
        }
        Slot slot = store.slot(input.slotId(), false).orElseThrow(
                () -> ApiFailure.missing("SLOT_NOT_FOUND", "The slot was not found."));
        UUID token = UUID.randomUUID();
        var lease = redis.acquire(slot, userId, token);
        try {
            return bookings.reserve(userId, input.slotId());
        } finally {
            if (lease == RedisBookingSupport.Lease.ACQUIRED) {
                redis.release(slot.id(), userId, token);
            }
        }
    }

    @GetMapping("/reservations")
    List<Order> orders(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "9223372036854775807") @Positive long beforeId) {
        return bookings.orders(userId(jwt), beforeId);
    }

    @GetMapping("/reservations/{id}")
    Order order(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
        return bookings.order(userId(jwt), id);
    }

    @PostMapping("/reservations/{id}/cancellation")
    Order cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
        return bookings.cancel(userId(jwt), id);
    }

    @PostMapping("/waitlist")
    @ResponseStatus(HttpStatus.CREATED)
    WaitEntry join(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SlotSelection input) {
        return bookings.join(userId(jwt), input.slotId());
    }

    @GetMapping("/waitlist")
    List<WaitEntry> waitEntries(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "9223372036854775807") @Positive long beforeId) {
        return bookings.waitEntries(userId(jwt), beforeId);
    }

    @GetMapping("/waitlist/{id}")
    WaitEntry waitEntry(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
        return bookings.waitEntry(userId(jwt), id);
    }

    @PostMapping("/waitlist/{id}/confirmation")
    WaitEntry confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
        return bookings.confirm(userId(jwt), id);
    }

    @PostMapping("/waitlist/{id}/cancellation")
    WaitEntry leave(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id) {
        return bookings.leave(userId(jwt), id);
    }

    static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    enum Mode { DB, REDIS }
    record NewSlot(@NotNull Instant startsAt, @NotNull Instant endsAt, @Positive @Max(1000) int capacity) { }
    record SlotSelection(@Positive long slotId) { }
    record ChangeStatus(@NotNull Availability status) { }
}
