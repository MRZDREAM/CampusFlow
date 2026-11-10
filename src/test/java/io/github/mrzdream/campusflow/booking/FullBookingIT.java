package io.github.mrzdream.campusflow.booking;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import io.github.mrzdream.campusflow.web.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static io.github.mrzdream.campusflow.booking.RabbitBookingConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@ActiveProfiles("full")
@EnabledIfEnvironmentVariable(named = "CAMPUS_FULL_IT", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DB_NAME=campus_flow_it", "spring.rabbitmq.virtual-host=campus-flow-it",
        "spring.data.redis.database=15", "campus.delivery.poll-ms=200"
})
class FullBookingIT extends BookingTestClient {
    @Autowired StringRedisTemplate redis;
    @Autowired RedisBookingSupport admission;
    @Autowired BookingStore slots;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin rabbitAdmin;
    @Autowired MeterRegistry metrics;
    @MockitoSpyBean AsyncBookingService service;

    @Test
    void redisCachesQueriesAndInvalidatesAfterBookingCommits() throws Exception {
        long resource = resource();
        long slot = slot(resource, 1);
        String url = "/api/v1/resources/" + resource + "/slots";
        String key = RedisBookingSupport.cacheKey(resource);
        double hits = redisCount("cache_hit");
        assertThat(request("GET", url, null, null).body().get(0).path("available").asInt()).isEqualTo(1);
        assertThat(redis.opsForValue().get(key)).contains("\"available\":1");
        assertThat(redis.getExpire(key)).isBetween(0L, 5L);
        assertThat(request("GET", url, null, null).status()).isEqualTo(200);
        assertThat(redisCount("cache_hit")).isGreaterThan(hits);
        var user = fixtureUser();
        assertThat(request("POST", "/api/v1/reservations?mode=REDIS", user.token(),
                Map.of("slotId", slot)).status()).isEqualTo(201);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(request("GET", url, null, null).body().get(0).path("available").asInt()).isZero();
        occupancy(slot, 1, 1, 0);
    }

    @Test
    void redisLuaKeepsAdmissionAtomicAndReleaseRequiresTheToken() throws Exception {
        long slotId = slot(resource(), 1);
        var slot = slots.slot(slotId, false).orElseThrow();
        var first = fixtureUser();
        var second = fixtureUser();
        UUID token = UUID.randomUUID();
        assertThat(admission.acquire(slot, first.id(), token)).isEqualTo(RedisBookingSupport.Lease.ACQUIRED);
        assertThat(admission.acquire(slot, first.id(), token)).isEqualTo(RedisBookingSupport.Lease.REUSED);
        assertThatThrownBy(() -> admission.acquire(slot, first.id(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiFailure.class, e -> assertThat(e.code()).isEqualTo("RESERVATION_IN_FLIGHT"));
        admission.release(slotId, first.id(), UUID.randomUUID());
        assertThatThrownBy(() -> admission.acquire(slot, second.id(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiFailure.class, e -> assertThat(e.code()).isEqualTo("ADMISSION_BUSY"));
        admission.release(slotId, first.id(), token);
        UUID secondToken = UUID.randomUUID();
        assertThat(admission.acquire(slot, second.id(), secondToken)).isEqualTo(RedisBookingSupport.Lease.ACQUIRED);
        admission.release(slotId, second.id(), secondToken);
        occupancy(slotId, 0, 0, 0);
    }

    @Test
    void acceptedRequestAndDuplicateDeliveryProduceOneCommittedOrder() throws Exception {
        long slotId = slot(resource(), 1);
        var user = fixtureUser();
        UUID id = UUID.randomUUID();
        double initial = deliveryCount("acknowledged");
        assertThat(submit(user, slotId, id).status()).isEqualTo(202);
        assertThat(submit(user, slotId, id).body().path("requestId").asText()).isEqualTo(id.toString());
        await("committed request", () -> state(id).equals("SUCCEEDED"));
        await("publisher confirm stored", () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT sent_at IS NOT NULL FROM booking_outbox WHERE request_id=?", Boolean.class, id.toString())));
        await("original acknowledged", () -> deliveryCount("acknowledged") > initial);
        assertThat(request("GET", "/api/v1/reservation-requests/" + id, fixtureUser().token(), null).status()).isEqualTo(404);
        double before = deliveryCount("acknowledged");
        rabbit.send(EXCHANGE, RESERVE, MessageBuilder.withBody(id.toString().getBytes(StandardCharsets.US_ASCII)).build());
        await("duplicate acknowledged", () -> deliveryCount("acknowledged") > before);
        occupancy(slotId, 1, 1, 0);
        assertThat(redis.hasKey(RedisBookingSupport.gateKeys(slotId).get(1))).isFalse();
    }

    @Test
    void unroutablePublishKeepsTheOutboxAndRecoversAfterBindingReturns() throws Exception {
        long slotId = slot(resource(), 1);
        UUID id = UUID.randomUUID();
        var user = fixtureUser();
        var binding = new Binding(MAIN_QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, RESERVE, Map.of());
        rabbitAdmin.removeBinding(binding);
        try {
            double retry = deliveryCount("publish_retry");
            assertThat(submit(user, slotId, id).status()).isEqualTo(202);
            await("broker returned unroutable publication", () -> deliveryCount("publish_retry") > retry);
            assertThat(state(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT sent_at IS NULL FROM booking_outbox WHERE request_id=?",
                    Boolean.class, id.toString())).isTrue();
        } finally {
            rabbitAdmin.declareBinding(binding);
        }
        await("confirmed publication after repair", () -> state(id).equals("SUCCEEDED"));
        occupancy(slotId, 1, 1, 0);
    }

    @Test
    void consumerFailureRetriesThroughBrokerAndEndsInDeadLetterQueue() throws Exception {
        long slotId = slot(resource(), 1);
        UUID id = UUID.randomUUID();
        var user = fixtureUser();
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("Injected processing failure"))
                .when(service).process(id);
        double retry = deliveryCount("consume_retry");
        double dead = deliveryCount("dead_lettered");
        assertThat(submit(user, slotId, id).status()).isEqualTo(202);
        await("two retries and dead-letter", () -> deliveryCount("dead_lettered") > dead);
        assertThat(deliveryCount("consume_retry")).isEqualTo(retry + 2);
        assertThat(state(id)).isEqualTo("FAILED");
        var message = rabbit.receive(DEAD_QUEUE, 4000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.US_ASCII)).isEqualTo(id.toString());
        occupancy(slotId, 0, 0, 0);
    }

    private Reply submit(User user, long slot, UUID id) throws Exception {
        return request("POST", "/api/v1/reservation-requests", user.token(), Map.of("slotId", slot), id);
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT status FROM booking_requests WHERE id=?", String.class, id.toString());
    }

    private double deliveryCount(String result) {
        var counter = metrics.find("campus.booking.delivery").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private double redisCount(String result) {
        var counter = metrics.find("campus.redis.operations").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void await(String description, BooleanSupplier satisfied) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (!satisfied.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(80);
        }
        assertThat(satisfied.getAsBoolean()).as(description).isTrue();
    }
}
