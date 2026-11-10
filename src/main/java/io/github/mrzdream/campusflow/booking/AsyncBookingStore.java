package io.github.mrzdream.campusflow.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static io.github.mrzdream.campusflow.booking.BookingStore.instant;
import static io.github.mrzdream.campusflow.booking.BookingStore.utc;

@Repository
@ConditionalOnProperty(name = "campus.delivery.enabled", havingValue = "true")
class AsyncBookingStore {
    private final JdbcTemplate jdbc;

    AsyncBookingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, long userId, long slotId, Instant deadline) {
        jdbc.update("INSERT INTO booking_requests (id,user_id,slot_id,deadline) VALUES (?,?,?,?)",
                id.toString(), userId, slotId, utc(deadline));
        jdbc.update("INSERT INTO booking_outbox (request_id) VALUES (?)", id.toString());
    }

    Optional<Request> request(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM booking_requests WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Request(UUID.fromString(rs.getString("id")), rs.getLong("user_id"),
                        rs.getLong("slot_id"), Status.valueOf(rs.getString("status")),
                        rs.getObject("reservation_id", Long.class), rs.getString("error_code"),
                        instant(rs, "created_at"), instant(rs, "deadline")), id.toString()).stream().findFirst();
    }

    void finish(UUID id, Status status, Long orderId, String error) {
        jdbc.update("UPDATE booking_requests SET status=?,reservation_id=?,error_code=? WHERE id=? AND status='PENDING'",
                status.name(), orderId, error, id.toString());
    }

    List<UUID> expired(Instant now) {
        return jdbc.queryForList("""
                SELECT id FROM booking_requests WHERE status='PENDING' AND deadline<=?
                ORDER BY deadline,id LIMIT 100
                """, String.class, utc(now)).stream().map(UUID::fromString).toList();
    }

    List<UUID> readyToSend() {
        return jdbc.queryForList("""
                SELECT request_id FROM booking_outbox
                WHERE sent_at IS NULL AND available_at<=UTC_TIMESTAMP(6)
                AND (claim_until IS NULL OR claim_until<=UTC_TIMESTAMP(6))
                ORDER BY available_at,request_id LIMIT 20
                """, String.class).stream().map(UUID::fromString).toList();
    }

    int claim(UUID id, UUID lease) {
        if (jdbc.update("""
                UPDATE booking_outbox SET claim_token=?,claim_until=UTC_TIMESTAMP(6)+INTERVAL 30 SECOND,attempts=attempts+1
                WHERE request_id=? AND sent_at IS NULL AND available_at<=UTC_TIMESTAMP(6)
                AND (claim_until IS NULL OR claim_until<=UTC_TIMESTAMP(6))
                """, lease.toString(), id.toString()) != 1) {
            return 0;
        }
        return jdbc.queryForObject("SELECT attempts FROM booking_outbox WHERE request_id=?", Integer.class, id.toString());
    }

    void sent(UUID id, UUID lease) {
        jdbc.update("""
                UPDATE booking_outbox SET sent_at=UTC_TIMESTAMP(6),claim_token=NULL,claim_until=NULL
                WHERE request_id=? AND claim_token=?
                """, id.toString(), lease.toString());
    }

    void discard(UUID id, UUID lease) {
        // A terminal request needs no further publication. Keep the request as the audit record.
        jdbc.update("DELETE FROM booking_outbox WHERE request_id=? AND claim_token=? AND sent_at IS NULL",
                id.toString(), lease.toString());
    }

    void retry(UUID id, UUID lease, Instant nextAttempt) {
        jdbc.update("""
                UPDATE booking_outbox SET available_at=?,claim_token=NULL,claim_until=NULL
                WHERE request_id=? AND claim_token=? AND sent_at IS NULL
                """, utc(nextAttempt), id.toString(), lease.toString());
    }

    enum Status { PENDING, SUCCEEDED, REJECTED, FAILED }
    record Request(UUID id, long userId, long slotId, Status status, Long reservationId,
                   String errorCode, Instant createdAt, Instant deadline) { }
}
