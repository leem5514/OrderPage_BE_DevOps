package com.example.ordersystem.common.configs;

import com.example.ordersystem.ordering.controller.SseController;
import com.fasterxml.jackson.databind.ObjectMapper;
//import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    // application.yml 의 spring.redis.host 의 정보를 가져옴.
    @Value("${spring.redis.host}")
    public String host;

    @Value("${spring.redis.port}")
    public int port;

    @Value("${spring.redis.password:}")
    private String password;
    @Value("${spring.redis.ssl:false}")
    private boolean sslEnabled;

    // 직접 만든 세 연결 팩토리 모두에 동일한 TLS/인증을 적용한다.
    // Spring Boot 속성만 설정하고 여기서 읽지 않으면 ElastiCache 연결에 반영되지 않는다.
    private LettuceConnectionFactory connectionFactory(int database) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        configuration.setDatabase(database);
        if (!password.isEmpty()) {
            configuration.setPassword(password);
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder();
        if (sslEnabled) {
            client.useSsl(); // 기본 인증서 검증을 유지한다.
        }
        return new LettuceConnectionFactory(configuration, client.build());
    }



    @Bean
    @Qualifier("2") //
    // RedisConnectionFactory 는 Redis 서버와의 연결을 설정하는 역할.
    // LettuceConnectionFactory 는 RedisConnectionFactory 의 구현체로서 실질적인 역할 수행.
    public RedisConnectionFactory redisConnectionFactory(){
        return connectionFactory(1);
    }

    // redisTemplate 은 redis 와 상호작용할 때 redis key, value 의 형식을 정의.
    @Bean
    @Qualifier("2")
    public RedisTemplate<String, Object> redisTemplate(@Qualifier("2") RedisConnectionFactory redisConnectionFactory){
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        return redisTemplate;
    }

    @Bean
    @Qualifier("3")
    public RedisConnectionFactory redisStockFactory(){
        return connectionFactory(2);
    }

    // redisTemplate 은 redis 와 상호작용할 때 redis key, value 의 형식을 정의.
    @Bean
    @Qualifier("3")
    public RedisTemplate<String, Object> stockRedisTemplate(@Qualifier("3") RedisConnectionFactory redisStockFactory){
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        redisTemplate.setConnectionFactory(redisStockFactory);
        return redisTemplate;
    }

    /* SseEmitter*/
    @Bean
    @Qualifier("4")
    public RedisConnectionFactory sseFactory(){
        return connectionFactory(3);
    }
    @Bean
    @Qualifier("4")
    public RedisTemplate<String, Object> sseRedisTemplate(@Qualifier("4") RedisConnectionFactory sseFactory){
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setKeySerializer(new StringRedisSerializer());
//        redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());

        // 객체 안 객체 으로 직렬화 이슈발생으로 인해서 아래와 같이 serializer
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper objectMapper = new ObjectMapper();
        serializer.setObjectMapper(objectMapper);
        redisTemplate.setValueSerializer(serializer);
        redisTemplate.setConnectionFactory(sseFactory);
        return redisTemplate;
    }
    // 리스너 객체 생성
    @Bean
    @Qualifier("4")
    public RedisMessageListenerContainer redisMessageListenerContainer(@Qualifier("4") RedisConnectionFactory sseFactory) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(sseFactory);
        return container;
    }

    /*
      redisTemplate를 불러다가 .opsForValue().set(key,value)
      redisTemplate.opsForValue().get(key)
      redisTemplate.opsForValue().increment 또는 decrement
      => redisTemplate를 통해 메서드가 제공됨
     */

}
