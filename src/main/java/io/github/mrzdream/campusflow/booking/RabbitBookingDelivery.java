package io.github.mrzdream.campusflow.booking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static io.github.mrzdream.campusflow.booking.RabbitBookingConfiguration.*;

@Component
@ConditionalOnProperty(name = "campus.delivery.enabled", havingValue = "true")
class RabbitBookingDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(RabbitBookingDelivery.class);
    private static final int MAX_ATTEMPTS = 3;

    private final AsyncBookingStore store;
    private final AsyncBookingService service;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;
    private final Clock clock;

    RabbitBookingDelivery(AsyncBookingStore store, AsyncBookingService service,
            RabbitTemplate rabbit, MeterRegistry metrics, Clock clock) {
        this.store = store;
        this.service = service;
        this.rabbit = rabbit;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${campus.delivery.poll-ms:1000}")
    void relay() {
        for (UUID id : store.readyToSend()) {
            UUID claim = UUID.randomUUID();
            int attempts = store.claim(id, claim);
            if (attempts == 0) {
                continue;
            }
            var request = store.request(id, false).orElseThrow();
            if (request.status() != AsyncBookingStore.Status.PENDING) {
                store.discard(id, claim);
                continue;
            }
            if (!request.deadline().isAfter(clock.instant())) {
                service.fail(id, "PROCESSING_DEADLINE");
                store.discard(id, claim);
                continue;
            }
            if (publish(RESERVE, id, 1)) {
                store.sent(id, claim);
                count("published");
            } else {
                store.retry(id, claim, clock.instant().plusSeconds(Math.min(30, attempts * 2L)));
                count("publish_retry");
                // Avoid holding up other scheduled work while the broker is down.
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "${campus.delivery.expiry-poll-ms:5000}")
    void expireRequests() {
        for (UUID id : store.expired(clock.instant())) {
            service.fail(id, "PROCESSING_DEADLINE");
        }
    }

    @RabbitListener(queues = MAIN_QUEUE, ackMode = "MANUAL")
    void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        UUID id;
        try {
            id = UUID.fromString(new String(message.getBody(), StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException malformed) {
            channel.basicReject(tag, false);
            count("invalid_message");
            return;
        }
        Object header = message.getMessageProperties().getHeaders().get("booking-attempt");
        int attempt = header instanceof Number value ? Math.max(1, value.intValue()) : 1;

        try {
            service.process(id);
        } catch (RuntimeException failure) {
            LOG.warn("Booking request {} failed on attempt {}", id, attempt, failure);
            if (attempt < MAX_ATTEMPTS) {
                if (publish(RETRY, id, attempt + 1)) {
                    channel.basicAck(tag, false);
                    count("consume_retry");
                } else {
                    channel.basicNack(tag, false, true);
                }
            } else {
                try {
                    service.fail(id, "DELIVERY_FAILED");
                } catch (RuntimeException databaseUnavailable) {
                    LOG.warn("Request {} will be settled by the deadline sweep", id, databaseUnavailable);
                }
                channel.basicReject(tag, false);
                count("dead_lettered");
            }
            return;
        }
        channel.basicAck(tag, false);
        count("acknowledged");
    }

    private boolean publish(String routingKey, UUID id, int attempt) {
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        var message = MessageBuilder.withBody(id.toString().getBytes(StandardCharsets.US_ASCII))
                .setMessageId(id.toString()).setContentType("text/plain")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setHeader("booking-attempt", attempt).build();
        try {
            rabbit.send(EXCHANGE, routingKey, message, correlation);
            var confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
            return confirm.isAck() && correlation.getReturned() == null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (AmqpException | ExecutionException | TimeoutException failure) {
            LOG.warn("RabbitMQ publish not confirmed for request {}", id);
            return false;
        }
    }

    private void count(String result) {
        metrics.counter("campus.booking.delivery", "result", result).increment();
    }
}
