package com.glucose.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.enums.Codes;
import com.glucose.common.exception.BusinessException;
import com.glucose.config.AppProperties;
import com.glucose.patient.entity.PatientProfile;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.security.JwtService;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * 微信小程序登录:code2session;未配置 appid 时使用确定性 mock openid 便于本地开发联调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxAuthService {

    private final AppProperties props;
    private final SysUserMapper sysUserMapper;
    private final PatientProfileMapper profileMapper;
    private final JwtService jwtService;
    private final PlatformTransactionManager transactionManager;

    public record WxLoginVO(String token, String userId, String dataMode, boolean profileCompleted) {
    }

    public WxLoginVO loginByCode(String code) {
        if (code == null || code.trim().length() < 3) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "微信登录 code 无效");
        }
        String openid = resolveOpenid(code.trim());
        return new TransactionTemplate(transactionManager).execute(status -> loginByOpenid(openid));
    }

    private WxLoginVO loginByOpenid(String openid) {
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getOpenid, openid));
        if (user == null) {
            user = createPatientUser(openid);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        PatientProfile profile = profileMapper.selectOne(Wrappers.<PatientProfile>lambdaQuery()
                .eq(PatientProfile::getUserId, user.getId()));
        if (profile == null) {
            profile = createProfile(user.getId());
        }
        user.setLastLoginAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        JwtService.TokenInfo token = jwtService.create(user.getId(), Codes.ROLE_PATIENT, user.getAuthVersion());
        boolean profileCompleted = profile.getPrivacyAcceptedAt() != null;
        return new WxLoginVO(token.token(), String.valueOf(user.getId()), user.getDataMode(), profileCompleted);
    }

    private String resolveOpenid(String code) {
        if (!props.getWx().configured()) {
            if (!props.getWx().isAllowMock()) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "微信登录服务未配置");
            String mock = sha256("mock-openid-salt:" + code).substring(0, 16);
            log.info("wx not configured, use mock openid={} (dev mode)", mock);
            return "mock-" + mock;
        }
        try {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(java.time.Duration.ofSeconds(3));
            factory.setReadTimeout(java.time.Duration.ofSeconds(5));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            Map<?, ?> resp = client.get()
                    .uri("https://api.weixin.qq.com/sns/jscode2session?appid={a}&secret={s}&js_code={c}&grant_type=authorization_code",
                            props.getWx().getAppid(), props.getWx().getSecret(), code)
                    .retrieve().body(Map.class);
            if (resp == null || resp.get("openid") == null) {
                log.warn("code2session rejected, errcode={}", resp == null ? "empty" : resp.get("errcode"));
                throw new BusinessException(ErrorCode.PARAM_ERROR, "微信登录 code 无效或已使用");
            }
            // session_key 不下发、不写日志
            return (String) resp.get("openid");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Exceptions may include the URL containing app secret and login code.
            log.warn("code2session unavailable, type={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "微信登录服务暂不可用");
        }
    }

    private SysUser createPatientUser(String openid) {
        SysUser user = new SysUser();
        user.setUsername("wx_" + openid.substring(Math.max(0, openid.length() - 12)));
        user.setOpenid(openid);
        user.setRole(Codes.ROLE_PATIENT);
        user.setDataMode(Codes.KIND_REAL);
        user.setStatus(1);
        user.setAuthVersion(0L);
        sysUserMapper.insert(user);
        return user;
    }

    private PatientProfile createProfile(long userId) {
        PatientProfile profile = new PatientProfile();
        profile.setUserId(userId);
        profile.setCgmTargetLow(new java.math.BigDecimal("3.9"));
        profile.setCgmTargetHigh(new java.math.BigDecimal("10.0"));
        profile.setTargetRevision(1L);
        profile.setDataRevision(0L);
        profileMapper.insert(profile);
        return profile;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
