package io.github.mrzdream.campusflow.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.github.mrzdream.campusflow.resource.domain.SlotCapacity;
import io.github.mrzdream.campusflow.resource.domain.SlotWindow;
import io.github.mrzdream.campusflow.web.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static io.github.mrzdream.campusflow.booking.BookingModels.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class BookingService {
    private final BookingStore store;
    private final Clock clock;
    private final Duration offerDuration;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate bookingAttempt;
    private final MeterRegistry metrics;

    BookingService(BookingStore store, Clock clock, ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager, MeterRegistry metrics,
            @Value("${campus.booking.offer-duration:PT5M}") Duration offerDuration) {
        if (offerDuration.compareTo(Duration.ofSeconds(1)) < 0 || offerDuration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Offer duration must be between one second and one hour.");
        }
        this.store = store;
        this.clock = clock;
        this.events = events;
        this.offerDuration = offerDuration;
        this.metrics = metrics;
        this.bookingAttempt = new TransactionTemplate(transactionManager);
        this.bookingAttempt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Slot createSlot(long resourceId, Instant start, Instant end, int capacity) {
        start = start.truncatedTo(ChronoUnit.MICROS);
        end = end.truncatedTo(ChronoUnit.MICROS);
        if (!start.isBefore(end) || !start.isAfter(clock.instant()) || end.isAfter(clock.instant().plus(366, ChronoUnit.DAYS))) {
            throw ApiFailure.invalid("INVALID_SLOT_WINDOW", "Use a future interval ending within 366 days.");
        }
        var window = new SlotWindow(start, end);
        if (resource(resourceId, true) != Availability.OPEN) {
            throw ApiFailure.conflict("RESOURCE_CLOSED", "The resource is closed.");
        }
        if (store.overlaps(resourceId, window.startsAt(), window.endsAt())) {
            throw ApiFailure.conflict("SLOT_OVERLAP", "The new interval overlaps another slot for this resource.");
        }
        Slot slot = slot(store.insertSlot(resourceId, start, end, capacity), false);
        changed(resourceId);
        return slot;
    }

    @Transactional(readOnly = true)
    public List<Slot> upcoming(long resourceId) {
        resource(resourceId, false);
        return store.upcoming(resourceId, clock.instant());
    }

    @Transactional(readOnly = true)
    public Slot getSlot(long resourceId, long slotId) {
        return childSlot(resourceId, slotId, false);
    }

    public Slot setSlotStatus(long resourceId, long slotId, Availability status) {
        Slot slot = childSlot(resourceId, slotId, true);
        if (status == slot.status()) {
            return slot;
        }
        if (status == Availability.CLOSED) {
            if (slot.reserved() > 0) {
                throw ApiFailure.conflict("SLOT_OCCUPIED", "Cancel active reservations and offers before closing this slot.");
            }
            store.closeWaitlist(slotId);
        } else if (!slot.startsAt().isAfter(clock.instant()) || resource(resourceId, false) != Availability.OPEN) {
            throw ApiFailure.conflict("SLOT_UNAVAILABLE", "Only future slots in open resources can reopen.");
        }
        store.slotStatus(slotId, status);
        changed(resourceId);
        return slot(slotId, false);
    }

    public Availability setResourceStatus(long resourceId, Availability status) {
        if (resource(resourceId, true) == status) {
            return status;
        }
        if (status == Availability.CLOSED) {
            List<Slot> slots = store.lockUnfinishedSlots(resourceId, clock.instant());
            if (slots.stream().anyMatch(slot -> slot.reserved() > 0)) {
                throw ApiFailure.conflict("RESOURCE_OCCUPIED", "Cancel active reservations and offers before closing this resource.");
            }
            slots.forEach(slot -> store.closeWaitlist(slot.id()));
        }
        store.resourceStatus(resourceId, status);
        changed(resourceId);
        return status;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order reserve(long userId, long slotId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return bookingAttempt.execute(tx -> allocateOptimistically(userId, slotId));
            } catch (OptimisticLockingFailureException staleSlot) {
                metrics.counter("campus.booking.optimistic", "result", "retry").increment();
            }
        }
        metrics.counter("campus.booking.optimistic", "result", "row_lock_fallback").increment();
        return bookingAttempt.execute(tx -> allocate(userId, slotId));
    }

    private Order allocateOptimistically(long userId, long slotId) {
        Slot slot = slot(slotId, false);
        if (slot.available() == 0) {
            return allocate(userId, slotId);
        }
        int reserved = new SlotCapacity(slot.capacity(), slot.reserved()).reserve().reserved();
        if (!store.compareOccupancy(slot, reserved)) {
            throw new OptimisticLockingFailureException("The slot changed before the reservation update.");
        }
        // The update owns the slot row now. Business rejection rolls back the tentative count.
        requireBookable(slot, userId);
        Order order = store.insertOrder(slotId, userId);
        changed(slot.resourceId());
        return order;
    }

    // Also called by the async worker inside its request-and-booking transaction.
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiFailure.class)
    Order allocate(long userId, long slotId) {
        Slot slot = slot(slotId, true);
        requireBookable(slot, userId);
        int reserved = new SlotCapacity(slot.capacity(), slot.reserved()).reserve().reserved();
        Order order = store.insertOrder(slotId, userId);
        store.occupancy(slot, reserved);
        changed(slot.resourceId());
        return order;
    }

    private void requireBookable(Slot slot, long userId) {
        requireOpen(slot, clock.instant());
        long slotId = slot.id();
        if (store.hasOrder(slotId, userId)) {
            throw ApiFailure.conflict("ALREADY_RESERVED", "You already have a reservation for this slot.");
        }
        if (store.activeWait(slotId, userId).isPresent()) {
            throw ApiFailure.conflict("ALREADY_WAITLISTED", "Use your existing waitlist entry.");
        }
        if (slot.available() == 0 || !store.nextWaiters(slotId, 1).isEmpty()) {
            throw ApiFailure.conflict("SLOT_FULL", "No unassigned place is available for this slot.");
        }
    }

    @Transactional(readOnly = true)
    public Order order(long userId, long id) {
        return store.order(id, userId).orElseThrow(
                () -> ApiFailure.missing("RESERVATION_NOT_FOUND", "The reservation was not found."));
    }

    @Transactional(readOnly = true)
    public List<Order> orders(long userId, long beforeId) {
        return store.orders(userId, beforeId);
    }

    public Order cancel(long userId, long id) {
        Slot slot = slot(order(userId, id).slotId(), true);
        Order order = order(userId, id);
        if (order.status() == OrderStatus.CANCELLED) {
            return order;
        }
        Instant now = clock.instant();
        if (!slot.startsAt().isAfter(now)) {
            throw ApiFailure.conflict("SLOT_STARTED", "A reservation cannot be cancelled after the slot starts.");
        }
        store.cancelOrder(id, now);
        rebalance(slot, new SlotCapacity(slot.capacity(), slot.reserved()).release().reserved(), now);
        return order(userId, id);
    }

    public WaitEntry join(long userId, long slotId) {
        Slot slot = slot(slotId, true);
        Instant now = clock.instant();
        requireOpen(slot, now);
        var existing = store.activeWait(slotId, userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (store.hasOrder(slotId, userId)) {
            throw ApiFailure.conflict("ALREADY_RESERVED", "You already have a reservation for this slot.");
        }
        if (slot.available() > 0 && store.nextWaiters(slotId, 1).isEmpty()) {
            throw ApiFailure.conflict("SLOT_HAS_SPACE", "Reserve directly while a place is available.");
        }
        long id = store.enqueueWaiter(slotId, userId);
        rebalance(slot, slot.reserved(), now);
        return waitEntry(userId, id);
    }

    public WaitEntry confirm(long userId, long id) {
        Slot slot = slot(waitEntry(userId, id).slotId(), true);
        WaitEntry entry = waitEntry(userId, id);
        if (entry.status() == WaitStatus.ACCEPTED) {
            return entry;
        }
        Instant now = clock.instant();
        requireOpen(slot, now);
        if (entry.status() != WaitStatus.OFFERED || !entry.offerExpiresAt().isAfter(now)) {
            throw ApiFailure.conflict("OFFER_NOT_VALID", "This entry does not have a valid offer.");
        }
        Order order = store.insertOrder(slot.id(), userId);
        store.accept(id, order.id());
        // The offered place was already counted; confirmation changes its owner, not the count.
        return waitEntry(userId, id);
    }

    public WaitEntry leave(long userId, long id) {
        Slot slot = slot(waitEntry(userId, id).slotId(), true);
        WaitEntry entry = waitEntry(userId, id);
        if (entry.status() != WaitStatus.WAITING && entry.status() != WaitStatus.OFFERED) {
            return entry;
        }
        store.leave(id);
        int reserved = slot.reserved() - (entry.status() == WaitStatus.OFFERED ? 1 : 0);
        rebalance(slot, reserved, clock.instant());
        return waitEntry(userId, id);
    }

    @Transactional(readOnly = true)
    public WaitEntry waitEntry(long userId, long id) {
        return store.waitEntry(id, userId).orElseThrow(
                () -> ApiFailure.missing("WAITLIST_ENTRY_NOT_FOUND", "The waitlist entry was not found."));
    }

    @Transactional(readOnly = true)
    public List<WaitEntry> waitEntries(long userId, long beforeId) {
        return store.waitEntries(userId, beforeId);
    }

    public void expire(long slotId) {
        Slot slot = slot(slotId, true);
        rebalance(slot, slot.reserved(), clock.instant());
    }

    private void rebalance(Slot slot, int reserved, Instant now) {
        reserved -= store.expireOffers(slot.id(), now);
        var count = new SlotCapacity(slot.capacity(), reserved);
        if (!slot.startsAt().isAfter(now)) {
            store.expireWaiters(slot.id());
        } else if (count.available() > 0 && slot.status() == Availability.OPEN
                && resource(slot.resourceId(), false) == Availability.OPEN) {
            Instant deadline = now.plus(offerDuration);
            if (deadline.isAfter(slot.startsAt())) {
                deadline = slot.startsAt();
            }
            for (long next : store.nextWaiters(slot.id(), count.available())) {
                store.offer(next, deadline);
                count = count.reserve();
            }
        }
        if (count.reserved() != slot.reserved()) {
            store.occupancy(slot, count.reserved());
            changed(slot.resourceId());
        }
    }

    private Availability resource(long id, boolean lock) {
        return store.resourceStatus(id, lock).orElseThrow(
                () -> ApiFailure.missing("RESOURCE_NOT_FOUND", "The resource was not found."));
    }

    private Slot slot(long id, boolean lock) {
        return store.slot(id, lock).orElseThrow(() -> ApiFailure.missing("SLOT_NOT_FOUND", "The slot was not found."));
    }

    private Slot childSlot(long resourceId, long slotId, boolean lock) {
        Slot slot = slot(slotId, lock);
        if (slot.resourceId() != resourceId) {
            throw ApiFailure.missing("SLOT_NOT_FOUND", "The slot was not found for this resource.");
        }
        return slot;
    }

    private void requireOpen(Slot slot, Instant now) {
        if (slot.status() != Availability.OPEN || !slot.startsAt().isAfter(now)
                || resource(slot.resourceId(), false) != Availability.OPEN) {
            throw ApiFailure.conflict("SLOT_UNAVAILABLE", "The slot is closed or has already started.");
        }
    }

    private void changed(long resourceId) {
        events.publishEvent(new SlotChanged(resourceId));
    }
}
