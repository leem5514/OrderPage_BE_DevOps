package com.example.ordersystem.common.configs;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class RedisConfigTest {
    @Test
    void allRedisDatabasesUseConfiguredTlsAndPassword() {
        RedisConfig config = new RedisConfig();
        config.host = "cache.example.test";
        config.port = 6379;
        ReflectionTestUtils.setField(config, "password", "test-auth-token");
        ReflectionTestUtils.setField(config, "sslEnabled", true);
        LettuceConnectionFactory[] factories = {
            (LettuceConnectionFactory) config.redisConnectionFactory(),
            (LettuceConnectionFactory) config.redisStockFactory(),
            (LettuceConnectionFactory) config.sseFactory()
        };
        for (int i = 0; i < factories.length; i++) {
            assertThat(factories[i].getDatabase()).isEqualTo(i + 1);
            assertThat(factories[i].getStandaloneConfiguration().getPassword().get())
                    .isEqualTo("test-auth-token".toCharArray());
            assertThat(factories[i].getClientConfiguration().isUseSsl()).isTrue();
            assertThat(factories[i].getClientConfiguration().isVerifyPeer()).isTrue();
        }
    }
}
