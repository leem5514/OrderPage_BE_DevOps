package com.example.ordersystem.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

@Component
public class JwtTokenProvider {
    private final SecretKey accessKey;
    private final SecretKey refreshKey;
    private final int expiration;
    private final int expirationRt;

    public JwtTokenProvider(@Value("${jwt.secretKey}") String secretKey,
                            @Value("${jwt.secretKeyRt}") String secretKeyRt,
                            @Value("${jwt.expiration}") int expiration,
                            @Value("${jwt.expirationRt}") int expirationRt) {
        // UTF-8 바이트를 그대로 사용한다. 운영에서는 서로 다른 난수 키를 주입한다.
        // JJWT가 32바이트 미만의 HS256 키를 거부하므로 약한 설정은 시작 시 실패한다.
        if (secretKey.equals(secretKeyRt)) {
            throw new IllegalArgumentException("Access/refresh signing keys must differ");
        }
        this.accessKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.refreshKey = Keys.hmacShaKeyFor(secretKeyRt.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
        this.expirationRt = expirationRt;
    }

    public String createToken(String email, String role) {
        return create(email, role, accessKey, expiration);
    }

    public String createRefreshToken(String email, String role) {
        return create(email, role, refreshKey, expirationRt);
    }

    private String create(String email, String role, SecretKey key, int minutes) {
        Date now = new Date();
        return Jwts.builder().subject(email).claim("role", role).issuedAt(now)
                .expiration(new Date(now.getTime() + minutes * 60_000L))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    public Claims parseAccessToken(String token) { return parse(token, accessKey); }

    public Claims parseRefreshToken(String token) { return parse(token, refreshKey); }

    private Claims parse(String token, SecretKey key) {
        Claims claims = Jwts.parser().verifyWith(key)
                .sig().clear().add(Jwts.SIG.HS256).and().build()
                .parseSignedClaims(token).getPayload();
        // 필수 claim 오류에 토큰 원문을 넣지 않는다.
        String role = claims.get("role", String.class);
        if (claims.getExpiration() == null || claims.getSubject() == null || claims.getSubject().isBlank()
                || role == null || !Set.of("USER", "ADMIN").contains(role)) {
            throw new JwtException("Invalid token claims");
        }
        return claims;
    }
}
