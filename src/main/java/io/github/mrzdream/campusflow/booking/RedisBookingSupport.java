package io.github.mrzdream.campusflow.booking;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mrzdream.campusflow.web.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import static io.github.mrzdream.campusflow.booking.BookingModels.Slot;
import static io.github.mrzdream.campusflow.booking.BookingModels.SlotChanged;

@Component
@ConditionalOnProperty(name = "campus.redis.enabled", havingValue = "true")
class RedisBookingSupport {
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private static final String LEASE_MILLIS = "120000";
    private static final RedisScript<Long> ACQUIRE =
            RedisScript.of(new ClassPathResource("redis/acquire-booking-lease.lua"), Long.class);
    private static final RedisScript<Long> RELEASE =
            RedisScript.of(new ClassPathResource("redis/release-booking-lease.lua"), Long.class);
    private static final TypeReference<List<Slot>> SLOT_LIST = new TypeReference<>() { };

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final BookingService bookings;
    private final MeterRegistry metrics;

    RedisBookingSupport(StringRedisTemplate redis, ObjectMapper mapper, BookingService bookings, MeterRegistry metrics) {
        this.redis = redis;
        this.mapper = mapper;
        this.bookings = bookings;
        this.metrics = metrics;
    }

    List<Slot> upcoming(long resourceId) {
        String key = cacheKey(resourceId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                List<Slot> result = mapper.readValue(cached, SLOT_LIST);
                count("cache_hit");
                return result;
            }
        } catch (DataAccessException | JsonProcessingException exception) {
            count("cache_read_failed");
        }

        List<Slot> result = bookings.upcoming(resourceId);
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(result), CACHE_TTL);
        } catch (DataAccessException | JsonProcessingException exception) {
            count("cache_write_failed");
        }
        return result;
    }

    Lease acquire(Slot slot, long userId, UUID requestId) {
        String user = Long.toString(userId);
        String token = requestId.toString();
        int budget = Math.max(1, Math.min(128, slot.available()));
        Long result;
        try {
            result = redis.execute(ACQUIRE, gateKeys(slot.id()), user, token, Integer.toString(budget), LEASE_MILLIS);
        } catch (DataAccessException exception) {
            count("admission_fallback");
            return Lease.FALLBACK;
        }
        if (Long.valueOf(1).equals(result)) {
            count("admitted");
            return Lease.ACQUIRED;
        }
        if (Long.valueOf(2).equals(result)) {
            return Lease.REUSED;
        }
        if (Long.valueOf(-1).equals(result)) {
            count("duplicate");
            throw ApiFailure.conflict("RESERVATION_IN_FLIGHT", "You already have a request in flight for this slot.");
        }
        if (Long.valueOf(0).equals(result)) {
            count("busy");
            throw ApiFailure.conflict("ADMISSION_BUSY", "This slot is busy. Retry or query your request status.");
        }
        count("admission_fallback");
        return Lease.FALLBACK;
    }

    void release(long slotId, long userId, UUID requestId) {
        try {
            redis.execute(RELEASE, gateKeys(slotId), Long.toString(userId), requestId.toString());
        } catch (DataAccessException exception) {
            count("release_failed");
        }
    }

    @TransactionalEventListener
    public void invalidate(SlotChanged change) {
        try {
            redis.delete(cacheKey(change.resourceId()));
        } catch (DataAccessException exception) {
            count("invalidate_failed");
        }
    }

    static String cacheKey(long resourceId) {
        return "campus:v1:resource:" + resourceId + ":upcoming";
    }

    static List<String> gateKeys(long slotId) {
        String base = "campus:v1:slot:{" + slotId + "}";
        return List.of(base + ":leases", base + ":owners");
    }

    private void count(String result) {
        metrics.counter("campus.redis.operations", "result", result).increment();
    }

    enum Lease { ACQUIRED, REUSED, FALLBACK }
}
