package com.example.ordersystem.common.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {
    private static final String ACCESS = "test_only_access_key_not_for_deployment_00000000";
    private static final String REFRESH = "test_only_refresh_key_not_for_deployment_11111111";
    private final JwtTokenProvider provider = new JwtTokenProvider(ACCESS, REFRESH, 30, 14400);

    @Test
    void preservesSubjectRoleExpiryAndSeparatesTokenTypes() {
        String access = provider.createToken("user@example.test", "USER");
        String refresh = provider.createRefreshToken("user@example.test", "USER");
        assertThat(provider.parseAccessToken(access).getSubject()).isEqualTo("user@example.test");
        assertThat(provider.parseAccessToken(access).get("role")).isEqualTo("USER");
        assertThat(provider.parseRefreshToken(refresh).getExpiration()).isAfter(new Date());
        assertThatThrownBy(() -> provider.parseAccessToken(refresh)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.parseRefreshToken(access)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredUnsignedAndInvalidClaimTokens() {
        JwtTokenProvider expired = new JwtTokenProvider(ACCESS, REFRESH, -1, -1);
        assertThatThrownBy(() -> provider.parseAccessToken(expired.createToken("user@example.test", "USER")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.parseAccessToken(Jwts.builder().subject("user@example.test").compact()))
                .isInstanceOf(JwtException.class);
        String noExpiry = Jwts.builder().subject("user@example.test").claim("role", "ADMIN")
                .signWith(Keys.hmacShaKeyFor(ACCESS.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
        assertThatThrownBy(() -> provider.parseAccessToken(noExpiry)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.parseAccessToken(provider.createToken("user@example.test", "ROOT")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWeakOrSharedKeysAtStartup() {
        assertThatThrownBy(() -> new JwtTokenProvider("weak", REFRESH, 30, 30)).isInstanceOf(WeakKeyException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new JwtTokenProvider(ACCESS, ACCESS, 30, 30));
    }
}
