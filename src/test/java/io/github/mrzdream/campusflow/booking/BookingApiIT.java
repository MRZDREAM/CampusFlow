package io.github.mrzdream.campusflow.booking;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DB_NAME=campus_flow_it", "campus.booking.maintenance=false"})
class BookingApiIT extends BookingTestClient {
    @Autowired BookingService bookings;

    @Test
    void realLoginEnforcesRolesAndReservationOwnership() throws Exception {
        String name = username();
        String pass = "integration-only-password";
        var creds = Map.of("username", name, "password", pass);
        var registered = request("POST", "/api/v1/auth/register", null, creds);
        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.body().path("role").asText()).isEqualTo("USER");
        String hash = jdbc.queryForObject("SELECT password_hash FROM campus_users WHERE username=?", String.class, name);
        assertThat(hash).startsWith("$2").doesNotContain(pass);
        assertThat(request("POST", "/api/v1/auth/login", null,
                Map.of("username", name, "password", "incorrect-password")).status()).isEqualTo(401);
        var login = request("POST", "/api/v1/auth/login", null, creds);
        assertThat(login.status()).isEqualTo(200);
        var user = new User(registered.id(), login.body().path("accessToken").asText());
        assertThat(request("POST", "/api/v1/resources", user.token(), Map.of("name", "forbidden")).status()).isEqualTo(403);
        assertThat(request("GET", "/api/v1/reservations", null, null).status()).isEqualTo(401);
        long slot = slot(resource(), 1);
        long order = reserve(user, slot).id();
        var other = fixtureUser();
        assertThat(request("GET", "/api/v1/reservations/" + order, other.token(), null).status()).isEqualTo(404);
        assertThat(cancel(other, order).status()).isEqualTo(404);
        occupancy(slot, 1, 1, 0);
    }

    @Test
    void concurrentSlotCreationRejectsOverlapButAllowsAdjacentIntervals() throws Exception {
        long id = resource();
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        var results = together(List.of(() -> createSlot(id, start, 1), () -> createSlot(id, start.plusSeconds(60), 1)));
        assertThat(results).extracting(Reply::status).containsExactlyInAnyOrder(201, 409);
        var first = results.stream().filter(reply -> reply.status() == 201).findFirst().orElseThrow();
        Instant end = Instant.parse(first.body().path("endsAt").asText());
        assertThat(createSlot(id, end, 1).status()).isEqualTo(201);
    }

    @Test
    void parallelUsersCannotOverbook() throws Exception {
        long slot = slot(resource(), 3);
        List<User> users = IntStream.range(0, 24).mapToObj(i -> fixtureUser()).toList();
        List<Callable<Reply>> requests = users.stream().<Callable<Reply>>map(user -> () -> reserve(user, slot)).toList();
        var results = together(requests);
        assertThat(results.stream().filter(reply -> reply.status() == 201)).hasSize(3);
        assertThat(results.stream().filter(reply -> reply.status() == 409 && "SLOT_FULL".equals(reply.code()))).hasSize(21);
        occupancy(slot, 3, 3, 0);
    }

    @Test
    void duplicatesAndConcurrentCancellationsChangeCapacityOnlyOnce() throws Exception {
        long slot = slot(resource(), 2);
        User user = fixtureUser();
        var results = together(List.of(() -> reserve(user, slot), () -> reserve(user, slot)));
        assertThat(results).extracting(Reply::status).containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().filter(reply -> reply.status() == 409).findFirst().orElseThrow().code())
                .isEqualTo("ALREADY_RESERVED");
        long order = results.stream().filter(reply -> reply.status() == 201).findFirst().orElseThrow().id();
        var cancelled = together(List.of(() -> cancel(user, order), () -> cancel(user, order)));
        assertThat(cancelled).extracting(Reply::state).containsExactly("CANCELLED", "CANCELLED");
        occupancy(slot, 0, 0, 0);
        var again = reserve(user, slot);
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.id()).isNotEqualTo(order);
        occupancy(slot, 1, 1, 0);
    }

    @Test
    void fifoHoldsOnePlaceAndConfirmDoesNotChargeTwice() throws Exception {
        long slot = slot(resource(), 1);
        var owner = fixtureUser();
        var first = fixtureUser();
        var second = fixtureUser();
        long order = reserve(owner, slot).id();
        long firstId = join(first, slot).id();
        long secondId = join(second, slot).id();
        assertThat(join(first, slot).id()).isEqualTo(firstId);
        assertThat(cancel(owner, order).state()).isEqualTo("CANCELLED");
        assertThat(entry(first, firstId).state()).isEqualTo("OFFERED");
        assertThat(entry(second, secondId).state()).isEqualTo("WAITING");
        occupancy(slot, 1, 0, 1);
        assertThat(reserve(fixtureUser(), slot).code()).isEqualTo("SLOT_FULL");
        var accepted = confirm(first, firstId);
        assertThat(accepted.state()).isEqualTo("ACCEPTED");
        long confirmedId = accepted.body().path("reservationId").asLong();
        assertThat(confirm(first, firstId).body().path("reservationId").asLong()).isEqualTo(confirmedId);
        occupancy(slot, 1, 1, 0);
        assertThat(cancel(first, confirmedId).state()).isEqualTo("CANCELLED");
        assertThat(entry(second, secondId).state()).isEqualTo("OFFERED");
        assertThat(request("POST", "/api/v1/waitlist/" + secondId + "/cancellation", second.token(), null).state())
                .isEqualTo("CANCELLED");
        occupancy(slot, 0, 0, 0);
    }

    @Test
    void expiredOfferMovesToNextWaiterAndCannotBeConfirmed() throws Exception {
        long slot = slot(resource(), 1);
        var owner = fixtureUser();
        var first = fixtureUser();
        var second = fixtureUser();
        long order = reserve(owner, slot).id();
        long firstId = join(first, slot).id();
        long secondId = join(second, slot).id();
        cancel(owner, order);
        jdbc.update("UPDATE waitlist_entries SET offer_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", firstId);
        assertThat(confirm(first, firstId).code()).isEqualTo("OFFER_NOT_VALID");
        bookings.expire(slot);
        bookings.expire(slot);
        assertThat(entry(first, firstId).state()).isEqualTo("EXPIRED");
        assertThat(entry(second, secondId).state()).isEqualTo("OFFERED");
        occupancy(slot, 1, 0, 1);
    }

    private Reply cancel(User user, long order) throws Exception {
        return request("POST", "/api/v1/reservations/" + order + "/cancellation", user.token(), null);
    }

    private Reply entry(User user, long id) throws Exception {
        return request("GET", "/api/v1/waitlist/" + id, user.token(), null);
    }

    private Reply confirm(User user, long id) throws Exception {
        return request("POST", "/api/v1/waitlist/" + id + "/confirmation", user.token(), null);
    }

    private static List<Reply> together(List<Callable<Reply>> operations) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = operations.stream().map(operation -> executor.submit(() -> {
                start.await();
                return operation.call();
            })).toList();
            start.countDown();
            var results = new java.util.ArrayList<Reply>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
