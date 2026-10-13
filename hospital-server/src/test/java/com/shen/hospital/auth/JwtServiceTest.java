package com.shen.hospital.auth;

import com.shen.hospital.auth.security.JwtService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private static final String SECRET = "test-only-signing-key-at-least-32-bytes-long";
    private final JwtService jwt = new JwtService(SECRET, 7200);

    @Test
    void roundTripAndTampering() {
        String token = jwt.issue(42L);
        assertEquals(42L, jwt.verify(token));
        assertThrows(JwtException.class, () -> new JwtService("another-test-only-signing-key-at-least-32-bytes", 7200).verify(token));
    }

    @Test
    void expirationAndIssuerRequired() {
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder().issuer("hospital-triage").subject("42")
                .expiration(Date.from(Instant.now().minusSeconds(120))).signWith(key).compact();
        assertThrows(ExpiredJwtException.class, () -> jwt.verify(expired));
        String wrongIssuer = Jwts.builder().issuer("other").subject("42")
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        assertThrows(JwtException.class, () -> jwt.verify(wrongIssuer));
        String noExpiry = Jwts.builder().issuer("hospital-triage").subject("42").signWith(key).compact();
        assertThrows(IllegalArgumentException.class, () -> jwt.verify(noExpiry));
    }
}
