package io.github.mrzdream.campusflow.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.github.mrzdream.campusflow.web.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static io.github.mrzdream.campusflow.booking.AsyncBookingStore.Request;
import static io.github.mrzdream.campusflow.booking.AsyncBookingStore.Status;

@Service
@ConditionalOnProperty(name = "campus.delivery.enabled", havingValue = "true")
class AsyncBookingService {
    private final AsyncBookingStore requests;
    private final BookingStore slots;
    private final BookingService bookings;
    private final ObjectProvider<RedisBookingSupport> redis;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Duration timeout;
    private final TransactionTemplate tx;

    AsyncBookingService(AsyncBookingStore requests, BookingStore slots, BookingService bookings,
            ObjectProvider<RedisBookingSupport> redis, MeterRegistry metrics, Clock clock, PlatformTransactionManager tm,
            @Value("${campus.delivery.deadline:PT5M}") Duration timeout) {
        if (timeout.compareTo(Duration.ofSeconds(5)) < 0 || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Request deadline must be between five seconds and one hour.");
        }
        this.requests = requests;
        this.slots = slots;
        this.bookings = bookings;
        this.redis = redis;
        this.metrics = metrics;
        this.clock = clock;
        this.timeout = timeout;
        this.tx = new TransactionTemplate(tm);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    View submit(long userId, long slotId, UUID id) {
        var existing = requests.request(id, false);
        if (existing.isPresent()) {
            return repeat(existing.get(), userId, slotId);
        }
        var slot = slots.slot(slotId, false).orElseThrow(
                () -> ApiFailure.missing("SLOT_NOT_FOUND", "The slot was not found."));
        var cache = redis.getIfAvailable();
        var lease = cache == null ? RedisBookingSupport.Lease.FALLBACK : cache.acquire(slot, userId, id);
        boolean persisted = false;
        try {
            try {
                tx.executeWithoutResult(status -> {
                    slots.slot(slotId, true).orElseThrow();
                    requests.insert(id, userId, slotId, clock.instant().plus(timeout));
                });
            } catch (DuplicateKeyException duplicate) {
                var other = requests.request(id, false);
                if (other.isPresent()) {
                    View result = repeat(other.get(), userId, slotId);
                    persisted = other.get().status() == Status.PENDING;
                    return result;
                }
                throw ApiFailure.conflict("RESERVATION_IN_FLIGHT", "You already have a request in flight for this slot.");
            }
            persisted = true;
            metrics.counter("campus.booking.requests", "result", "accepted").increment();
            return get(userId, id);
        } finally {
            if (!persisted && lease == RedisBookingSupport.Lease.ACQUIRED) {
                cache.release(slotId, userId, id);
            }
        }
    }

    View get(long userId, UUID id) {
        return requests.request(id, false).filter(row -> row.userId() == userId)
                .map(View::of).orElseThrow(() -> ApiFailure.missing("REQUEST_NOT_FOUND", "The request was not found."));
    }

    private View repeat(Request existing, long userId, long slotId) {
        if (existing.userId() != userId || existing.slotId() != slotId) {
            throw ApiFailure.conflict("IDEMPOTENCY_KEY_CONFLICT", "This key cannot be used for this request.");
        }
        return View.of(existing);
    }

    void process(UUID id) {
        var observed = requests.request(id, false).orElse(null);
        if (observed == null || observed.status() != Status.PENDING) {
            release(observed);
            return;
        }
        Request row = tx.execute(status -> {
            slots.slot(observed.slotId(), true).orElseThrow();
            var request = requests.request(id, true).orElse(null);
            if (request == null || request.status() != Status.PENDING) {
                return request;
            }
            if (!request.deadline().isAfter(clock.instant())) {
                requests.finish(id, Status.FAILED, null, "PROCESSING_DEADLINE");
            } else {
                try {
                    var order = bookings.allocate(request.userId(), request.slotId());
                    requests.finish(id, Status.SUCCEEDED, order.id(), null);
                } catch (ApiFailure rejected) {
                    // Allocation checks business rules before changing any records.
                    requests.finish(id, Status.REJECTED, null, rejected.code());
                }
            }
            return requests.request(id, false).orElseThrow();
        });
        release(row);
    }

    void fail(UUID id, String reason) {
        Request row = tx.execute(status -> {
            var request = requests.request(id, true).orElse(null);
            if (request != null && request.status() == Status.PENDING) {
                requests.finish(id, Status.FAILED, null, reason);
            }
            return request;
        });
        release(row);
    }

    private void release(Request row) {
        if (row != null) {
            var cache = redis.getIfAvailable();
            if (cache != null) {
                cache.release(row.slotId(), row.userId(), row.id());
            }
        }
    }

    record View(UUID requestId, long slotId, Status status, Long reservationId, String errorCode,
                Instant createdAt, Instant deadline) {
        static View of(Request row) {
            return new View(row.id(), row.slotId(), row.status(), row.reservationId(), row.errorCode(),
                    row.createdAt(), row.deadline());
        }
    }
}
