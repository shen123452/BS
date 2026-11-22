package com.glucose.security;

import com.glucose.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT HS256;claims: sub(userId)、role、jti、authVersion;有效期 2 小时。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(AppProperties props) {
        String secret = props.getSecurity().getJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = props.getSecurity().getTokenTtlHours() * 3600L;
    }

    public record TokenInfo(String token, String jti, long expiresAtEpochSeconds) {
    }

    public TokenInfo create(Long userId, String role, long authVersion) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);
        String jti = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("authVersion", authVersion);
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new TokenInfo(token, jti, exp.getEpochSecond());
    }

    /**
     * 解析并校验签名与有效期;返回 claims。业务状态(黑名单/账号)由过滤器继续校验。
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
