package io.github.mrzdream.campusflow.booking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import static io.github.mrzdream.campusflow.booking.BookingModels.*;

@Repository
class BookingStore {
    private static final String SLOT_FIELDS = "id,resource_id,starts_at,ends_at,capacity,reserved,status,version";
    private static final String ORDER_FIELDS = "id,slot_id,status,created_at,cancelled_at";
    private static final String WAIT_FIELDS = "id,slot_id,status,created_at,offer_expires_at,reservation_id";
    private static final RowMapper<Slot> SLOT = (rs, row) -> new Slot(rs.getLong("id"), rs.getLong("resource_id"),
            instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getInt("capacity"), rs.getInt("reserved"),
            rs.getInt("capacity") - rs.getInt("reserved"), Availability.valueOf(rs.getString("status")),
            rs.getLong("version"));
    private static final RowMapper<Order> ORDER = (rs, row) -> new Order(rs.getLong("id"), rs.getLong("slot_id"),
            OrderStatus.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "cancelled_at"));
    private static final RowMapper<WaitEntry> WAIT = (rs, row) -> new WaitEntry(rs.getLong("id"), rs.getLong("slot_id"),
            WaitStatus.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "offer_expires_at"),
            rs.getObject("reservation_id", Long.class));

    private final JdbcTemplate jdbc;

    BookingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Availability> resourceStatus(long id, boolean lock) {
        return first(jdbc.query("SELECT status FROM resources WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> Availability.valueOf(rs.getString(1)), id));
    }

    void resourceStatus(long id, Availability status) {
        jdbc.update("UPDATE resources SET status=? WHERE id=?", status.name(), id);
    }

    List<Slot> upcoming(long resourceId, Instant now) {
        return jdbc.query("SELECT " + SLOT_FIELDS + " FROM resource_slots WHERE resource_id=? AND starts_at>?"
                + " ORDER BY starts_at,id LIMIT 100", SLOT, resourceId, utc(now));
    }

    List<Slot> lockUnfinishedSlots(long resourceId, Instant now) {
        return jdbc.query("SELECT " + SLOT_FIELDS + " FROM resource_slots WHERE resource_id=? AND ends_at>?"
                + " ORDER BY starts_at,id FOR UPDATE", SLOT, resourceId, utc(now));
    }

    Optional<Slot> slot(long id, boolean lock) {
        return first(jdbc.query("SELECT " + SLOT_FIELDS + " FROM resource_slots WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                SLOT, id));
    }

    boolean overlaps(long resourceId, Instant start, Instant end) {
        return !jdbc.queryForList("""
                SELECT id FROM resource_slots
                WHERE resource_id=? AND starts_at<? AND ends_at>? LIMIT 1
                """, Long.class, resourceId, utc(end), utc(start)).isEmpty();
    }

    long insertSlot(long resourceId, Instant start, Instant end, int capacity) {
        return insert("INSERT INTO resource_slots (resource_id,starts_at,ends_at,capacity) VALUES (?,?,?,?)",
                resourceId, utc(start), utc(end), capacity);
    }

    void occupancy(Slot slot, int reserved) {
        if (!compareOccupancy(slot, reserved)) {
            throw new IllegalStateException("Slot occupancy changed outside its transaction.");
        }
    }

    boolean compareOccupancy(Slot slot, int reserved) {
        return jdbc.update("UPDATE resource_slots SET reserved=?,version=version+1 WHERE id=? AND version=?",
                reserved, slot.id(), slot.version()) == 1;
    }

    void slotStatus(long id, Availability status) {
        jdbc.update("UPDATE resource_slots SET status=?,version=version+1 WHERE id=?", status.name(), id);
    }

    Optional<Order> order(long id, long userId) {
        return first(jdbc.query("SELECT " + ORDER_FIELDS + " FROM reservation_orders WHERE id=? AND user_id=?",
                ORDER, id, userId));
    }

    List<Order> orders(long userId, long beforeId) {
        return jdbc.query("SELECT " + ORDER_FIELDS + " FROM reservation_orders WHERE user_id=? AND id<?"
                + " ORDER BY id DESC LIMIT 100", ORDER, userId, beforeId);
    }

    boolean hasOrder(long slotId, long userId) {
        return !jdbc.queryForList("SELECT id FROM reservation_orders WHERE slot_id=? AND active_user=?",
                Long.class, slotId, userId).isEmpty();
    }

    Order insertOrder(long slotId, long userId) {
        long id = insert("INSERT INTO reservation_orders (slot_id,user_id) VALUES (?,?)", slotId, userId);
        return order(id, userId).orElseThrow();
    }

    void cancelOrder(long id, Instant now) {
        if (jdbc.update("UPDATE reservation_orders SET status='CANCELLED',cancelled_at=?"
                + " WHERE id=? AND status='CONFIRMED'", utc(now), id) != 1) {
            throw new IllegalStateException("Order changed outside its slot transaction.");
        }
    }

    Optional<WaitEntry> waitEntry(long id, long userId) {
        return first(jdbc.query("SELECT " + WAIT_FIELDS + " FROM waitlist_entries WHERE id=? AND user_id=?",
                WAIT, id, userId));
    }

    List<WaitEntry> waitEntries(long userId, long beforeId) {
        return jdbc.query("SELECT " + WAIT_FIELDS + " FROM waitlist_entries WHERE user_id=? AND id<?"
                + " ORDER BY id DESC LIMIT 100", WAIT, userId, beforeId);
    }

    Optional<WaitEntry> activeWait(long slotId, long userId) {
        return first(jdbc.query("SELECT " + WAIT_FIELDS + " FROM waitlist_entries WHERE slot_id=? AND active_user=?",
                WAIT, slotId, userId));
    }

    List<Long> nextWaiters(long slotId, int limit) {
        return jdbc.queryForList("SELECT id FROM waitlist_entries WHERE slot_id=? AND status='WAITING'"
                + " ORDER BY id LIMIT ?", Long.class, slotId, limit);
    }

    long enqueueWaiter(long slotId, long userId) {
        return insert("INSERT INTO waitlist_entries (slot_id,user_id) VALUES (?,?)", slotId, userId);
    }

    void offer(long id, Instant expiresAt) {
        jdbc.update("UPDATE waitlist_entries SET status='OFFERED',offer_expires_at=? WHERE id=?",
                utc(expiresAt), id);
    }

    void accept(long id, long reservationId) {
        jdbc.update("UPDATE waitlist_entries SET status='ACCEPTED',reservation_id=? WHERE id=?", reservationId, id);
    }

    void leave(long id) {
        jdbc.update("UPDATE waitlist_entries SET status='CANCELLED' WHERE id=?", id);
    }

    void closeWaitlist(long slotId) {
        jdbc.update("UPDATE waitlist_entries SET status='CANCELLED' WHERE slot_id=? AND status='WAITING'", slotId);
    }

    int expireOffers(long slotId, Instant now) {
        return jdbc.update("""
                UPDATE waitlist_entries SET status='EXPIRED'
                WHERE slot_id=? AND status='OFFERED' AND offer_expires_at<=?
                """, slotId, utc(now));
    }

    void expireWaiters(long slotId) {
        jdbc.update("UPDATE waitlist_entries SET status='EXPIRED' WHERE slot_id=? AND status='WAITING'", slotId);
    }

    List<Long> dueSlots(Instant now) {
        return jdbc.queryForList("""
                SELECT DISTINCT w.slot_id FROM waitlist_entries w
                JOIN resource_slots s ON s.id=w.slot_id
                WHERE (w.status='OFFERED' AND w.offer_expires_at<=?)
                   OR (w.status='WAITING' AND s.starts_at<=?)
                ORDER BY w.slot_id LIMIT 100
                """, Long.class, utc(now), utc(now));
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return java.util.Objects.requireNonNull(keys.getKey(), "No generated ID.").longValue();
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static <T> Optional<T> first(List<T> result) {
        return result.stream().findFirst();
    }
}
