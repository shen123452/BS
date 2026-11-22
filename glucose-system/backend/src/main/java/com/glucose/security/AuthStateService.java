package com.glucose.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis 保存的安全状态:验证码、jti 黑名单、登录失败计数、问答限流。
 */
@Service
@RequiredArgsConstructor
public class AuthStateService {

    private static final String K_CAPTCHA = "auth:cap:";
    private static final String K_BLACKLIST = "auth:bl:";
    private static final String K_LOGIN_FAIL = "auth:fail:";
    private static final String K_QA_HOUR = "qa:hour:";
    private static final String K_QA_CONC = "qa:conc:";

    private final StringRedisTemplate redis;

    /* ---------- 验证码(2 分钟一次性) ---------- */

    public void saveCaptcha(String captchaId, String code) {
        redis.opsForValue().set(K_CAPTCHA + captchaId, code, Duration.ofSeconds(120));
    }

    /** 一次性校验:无论成败立即删除。 */
    public boolean verifyCaptcha(String captchaId, String code) {
        String key = K_CAPTCHA + captchaId;
        // GETDEL requires Redis 6.2; Lua preserves one-time consumption on local Redis 5 as well.
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<String>(
                "local v = redis.call('get', KEYS[1]); if v then redis.call('del', KEYS[1]); end; return v", String.class);
        String saved = redis.execute(script, java.util.List.of(key));
        return saved != null && code != null && saved.equalsIgnoreCase(code.trim());
    }

    /* ---------- jti 黑名单 ---------- */

    public void revokeJti(String jti, long expiresAtEpochSeconds) {
        long ttl = expiresAtEpochSeconds - System.currentTimeMillis() / 1000;
        if (ttl > 0) {
            redis.opsForValue().set(K_BLACKLIST + jti, "1", Duration.ofSeconds(ttl));
        }
    }

    public boolean isJtiRevoked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(K_BLACKLIST + jti));
    }

    /* ---------- 登录失败限制(账号维度,5 次/10 分钟) ---------- */

    public boolean isLoginLocked(String username) {
        String v = redis.opsForValue().get(K_LOGIN_FAIL + username);
        return v != null && Integer.parseInt(v) >= 5;
    }

    public void recordLoginFailure(String username) {
        String key = K_LOGIN_FAIL + username;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofMinutes(10));
        }
    }

    public void clearLoginFailures(String username) {
        redis.delete(K_LOGIN_FAIL + username);
    }

    /* ---------- 问答限流 ---------- */

    public boolean tryAcquireQaQuota(long userId, int hourlyLimit) {
        String hourKey = K_QA_HOUR + userId + ":" + (System.currentTimeMillis() / 3_600_000);
        Long count = redis.opsForValue().increment(hourKey);
        if (count != null && count == 1) {
            redis.expire(hourKey, Duration.ofHours(1));
        }
        return count != null && count <= hourlyLimit;
    }

    public String acquireQaLease(long userId) {
        String lease = java.util.UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(K_QA_CONC + userId, lease, Duration.ofSeconds(30))) ? lease : null;
    }

    public void releaseQaLease(long userId, String lease) {
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
        redis.execute(script, java.util.List.of(K_QA_CONC + userId), lease);
    }
}
