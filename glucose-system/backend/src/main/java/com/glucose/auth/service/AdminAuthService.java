package com.glucose.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.enums.Codes;
import com.glucose.common.exception.BusinessException;
import com.glucose.security.AuthStateService;
import com.glucose.security.JwtService;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 管理端账密登录:验证码 + 失败限制 + BCrypt;仅 DOCTOR/ADMIN 可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final SysUserMapper sysUserMapper;
    private final CaptchaService captchaService;
    private final AuthStateService authStateService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public record LoginVO(String token, String userId, String username, String role, String realName) {
    }

    public LoginVO login(String username, String password, String captchaId, String captchaCode) {
        if (!captchaService.verify(captchaId, captchaCode)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "验证码错误或已过期");
        }
        if (authStateService.isLoginLocked(username)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "失败次数过多,请 10 分钟后再试");
        }
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username));
        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(password, user.getPassword())) {
            authStateService.recordLoginFailure(username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!Codes.ROLE_DOCTOR.equals(user.getRole()) && !Codes.ROLE_ADMIN.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该账号不允许从此入口登录");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        authStateService.clearLoginFailures(username);
        user.setLastLoginAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        JwtService.TokenInfo token = jwtService.create(user.getId(), user.getRole(), user.getAuthVersion());
        log.info("admin login success, userId={}, role={}", user.getId(), user.getRole());
        return new LoginVO(token.token(), String.valueOf(user.getId()), user.getUsername(),
                user.getRole(), user.getRealName());
    }
}
