package com.shen.hospital.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final JwtParser parser;
    private final long expiresIn;

    public JwtService(@Value("${hospital.jwt.secret}") String secret,
                      @Value("${hospital.jwt.expires-in-seconds}") long expiresIn) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("JWT_SECRET 至少需要32字节");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser().verifyWith(key).requireIssuer("hospital-triage").clockSkewSeconds(30).build();
        this.expiresIn = expiresIn;
    }

    public String issue(Long userId) {
        var now = Instant.now();
        return Jwts.builder().issuer("hospital-triage").subject(userId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expiresIn))).signWith(key).compact();
    }

    public Long verify(String token) {
        var claims = parser.parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null) throw new IllegalArgumentException("Token expiration missing");
        long id = Long.parseLong(claims.getSubject());
        if (id <= 0) throw new IllegalArgumentException("Invalid token subject");
        return id;
    }

    public long expiresIn() {
        return expiresIn;
    }
}
