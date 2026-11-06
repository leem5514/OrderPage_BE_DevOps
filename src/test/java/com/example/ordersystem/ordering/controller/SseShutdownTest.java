package com.example.ordersystem.ordering.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SseShutdownTest {
    @Test
    void shutdownRejectsNewSubscriptions() {
        SseController controller = new SseController(mock(RedisTemplate.class),
                mock(RedisMessageListenerContainer.class));
        controller.closeConnections();
        assertThat(controller.subscribe().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(controller.subscribe().getBody()).isNull();
    }
}
