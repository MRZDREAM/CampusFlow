package io.github.mrzdream.campusflow.booking;

import java.time.Instant;

public final class BookingModels {
    private BookingModels() { }

    public enum Availability { OPEN, CLOSED }
    public enum OrderStatus { CONFIRMED, CANCELLED }
    public enum WaitStatus { WAITING, OFFERED, ACCEPTED, EXPIRED, CANCELLED }

    public record Slot(long id, long resourceId, Instant startsAt, Instant endsAt,
                       int capacity, int reserved, int available, Availability status, long version) { }
    public record Order(long id, long slotId, OrderStatus status, Instant createdAt, Instant cancelledAt) { }
    public record WaitEntry(long id, long slotId, WaitStatus status, Instant createdAt,
                            Instant offerExpiresAt, Long reservationId) { }
    public record SlotChanged(long resourceId) { }
}
