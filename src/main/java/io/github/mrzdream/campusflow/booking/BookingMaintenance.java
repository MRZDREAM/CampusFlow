package io.github.mrzdream.campusflow.booking;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class BookingScheduling { }

@Component
@ConditionalOnProperty(name = "campus.booking.maintenance", matchIfMissing = true)
class BookingMaintenance {
    private static final Logger LOG = LoggerFactory.getLogger(BookingMaintenance.class);
    private final BookingStore store;
    private final BookingService bookings;
    private final Clock clock;

    BookingMaintenance(BookingStore store, BookingService bookings, Clock clock) {
        this.store = store;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${campus.booking.expiry-poll-ms:5000}")
    void releaseExpiredOffers() {
        for (long slotId : store.dueSlots(clock.instant())) {
            try {
                bookings.expire(slotId);
            } catch (RuntimeException exception) {
                LOG.warn("Could not expire waitlist entries for slot {}", slotId, exception);
            }
        }
    }
}
