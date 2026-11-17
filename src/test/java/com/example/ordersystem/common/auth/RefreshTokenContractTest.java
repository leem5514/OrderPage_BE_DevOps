package com.example.ordersystem.common.auth;

import com.example.ordersystem.member.controller.MemberController;
import com.example.ordersystem.member.dto.MemberRefreshDto;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.member.service.MemberService;
import com.example.ordersystem.common.dto.CommonResDto;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenContractTest {
    private final JwtTokenProvider tokens = new JwtTokenProvider(
            "test_only_access_key_not_for_deployment_00000000",
            "test_only_refresh_key_not_for_deployment_11111111", 30, 14400);

    @Test
    @SuppressWarnings("unchecked")
    void refreshRequiresSignatureAndStoredTokenAndReturnsExistingResponseShape() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var controller = new MemberController(mock(MemberService.class), mock(MemberRepository.class), tokens, redis);
        MemberRefreshDto dto = new MemberRefreshDto();
        dto.setRefreshToken(tokens.createToken("user@example.test", "USER"));
        assertThat(controller.generateNewAccessToken(dto).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(values);
        dto.setRefreshToken(tokens.createRefreshToken("user@example.test", "USER"));
        assertThat(controller.generateNewAccessToken(dto).getStatusCode().value()).isEqualTo(401);
        when(values.get("user@example.test")).thenReturn(dto.getRefreshToken());
        var response = controller.generateNewAccessToken(dto);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        CommonResDto body = (CommonResDto) response.getBody();
        String access = (String) ((Map<String, Object>) body.getResult()).get("token");
        assertThat(tokens.parseAccessToken(access).getSubject()).isEqualTo("user@example.test");
    }
}
