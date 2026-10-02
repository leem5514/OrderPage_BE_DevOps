package com.example.ordersystem.ordering.service.stock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "order.stock-processing-mode=REDIS_ASYNC",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@ActiveProfiles("test")
class RedisAsyncModeConfigurationTest {

    @Autowired
    private StockProcessor stockProcessor;

    @Test
    void selectsRedisAsyncProcessorFromConfiguration() {
        assertThat(stockProcessor).isInstanceOf(RedisAsyncStockProcessor.class);
    }
}
