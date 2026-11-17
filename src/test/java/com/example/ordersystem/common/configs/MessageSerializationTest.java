package com.example.ordersystem.common.configs;

import com.example.ordersystem.ordering.domain.OrderStatus;
import com.example.ordersystem.ordering.dto.OrderListResDto;
import com.example.ordersystem.ordering.dto.SseNotificationDto;
import com.example.ordersystem.ordering.dto.StockDecreaseEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MessageSerializationTest {
    @Test
    @SuppressWarnings("unchecked")
    void keepsRedisTokenAndCounterWireFormatWithoutClassMetadata() {
        RedisConfig config = new RedisConfig();
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisSerializer<Object> token = (RedisSerializer<Object>) config.redisTemplate(factory).getValueSerializer();
        RedisSerializer<Object> stock = (RedisSerializer<Object>) config.stockRedisTemplate(factory).getValueSerializer();
        assertThat(new String(token.serialize("signed-token"), StandardCharsets.UTF_8)).isEqualTo("\"signed-token\"");
        assertThat(token.deserialize("\"signed-token\"".getBytes(StandardCharsets.UTF_8))).isEqualTo("signed-token");
        assertThat(new String(stock.serialize(42), StandardCharsets.UTF_8)).isEqualTo("42");
        assertThat(((Number) stock.deserialize("41".getBytes(StandardCharsets.UTF_8))).intValue()).isEqualTo(41);
        assertThatThrownBy(() -> token.deserialize("{\"@class\":\"java.lang.Runtime\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Exception.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsNestedSseJsonContract() {
        OrderListResDto order = OrderListResDto.builder().id(1L).memberEmail("user@example.test")
                .orderStatus(OrderStatus.values()[0]).orderDetailDtos(List.of(
                        OrderListResDto.OrderDetailDto.builder().id(2L).productName("상품").count(3).build())).build();
        SseNotificationDto dto = new SseNotificationDto("ordered", order);
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) new RedisConfig()
                .sseRedisTemplate(mock(RedisConnectionFactory.class)).getValueSerializer();
        byte[] body = serializer.serialize(dto);
        SseNotificationDto decoded = JsonMapper.builder().build().readValue(body, SseNotificationDto.class);
        assertThat(decoded).isEqualTo(dto);
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("@class");
    }

    @Test
    void rabbitStockEventRoundTrips() {
        var converter = new RabbitMqConfig().jacksonJsonMessageConverter();
        StockDecreaseEvent event = new StockDecreaseEvent(4L, 3);
        var message = converter.toMessage(event, new MessageProperties());
        StockDecreaseEvent decoded = (StockDecreaseEvent) converter.fromMessage(message);
        assertThat(decoded.getProductId()).isEqualTo(4L);
        assertThat(decoded.getProductCount()).isEqualTo(3);
    }
}
