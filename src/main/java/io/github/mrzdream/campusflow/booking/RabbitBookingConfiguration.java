package io.github.mrzdream.campusflow.booking;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "campus.delivery.enabled", havingValue = "true")
class RabbitBookingConfiguration {
    static final String EXCHANGE = "campus.booking.v1";
    static final String MAIN_QUEUE = "campus.booking.v1.requests";
    static final String RETRY_QUEUE = "campus.booking.v1.retry";
    static final String DEAD_QUEUE = "campus.booking.v1.dead";
    static final String RESERVE = "reserve";
    static final String RETRY = "retry";
    static final String DEAD = "dead";

    @Bean
    Declarables bookingQueues() {
        var exchange = new DirectExchange(EXCHANGE);
        var main = QueueBuilder.durable(MAIN_QUEUE).deadLetterExchange(EXCHANGE).deadLetterRoutingKey(DEAD).build();
        var retry = QueueBuilder.durable(RETRY_QUEUE).ttl(2000)
                .deadLetterExchange(EXCHANGE).deadLetterRoutingKey(RESERVE).build();
        var dead = QueueBuilder.durable(DEAD_QUEUE).build();
        return new Declarables(exchange, main, retry, dead,
                BindingBuilder.bind(main).to(exchange).with(RESERVE),
                BindingBuilder.bind(retry).to(exchange).with(RETRY),
                BindingBuilder.bind(dead).to(exchange).with(DEAD));
    }
}
