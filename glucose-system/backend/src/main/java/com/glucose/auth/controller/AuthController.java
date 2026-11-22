package com.glucose.auth.controller;

import com.glucose.auth.service.AdminAuthService;
import com.glucose.auth.service.WxAuthService;
import com.glucose.common.api.Result;
import com.glucose.security.AuthStateService;
import com.glucose.security.LoginContext;
import com.glucose.security.LoginUser;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AdminAuthService adminAuthService;
    private final WxAuthService wxAuthService;
    private final AuthStateService authStateService;
    private final com.glucose.security.JwtService jwtService;

    public record AdminLoginReq(@NotBlank String username, @NotBlank String password,
                                @NotBlank String captchaId, @NotBlank String captchaCode) {
    }

    @PostMapping("/api/admin/auth/login")
    public Result<AdminAuthService.LoginVO> adminLogin(@RequestBody @Validated AdminLoginReq req) {
        return Result.ok(adminAuthService.login(req.username(), req.password(), req.captchaId(), req.captchaCode()));
    }

    public record WxLoginReq(@NotBlank String code) {
    }

    @PostMapping("/api/app/auth/wx-login")
    public Result<WxAuthService.WxLoginVO> wxLogin(@RequestBody @Validated WxLoginReq req) {
        return Result.ok(wxAuthService.loginByCode(req.code()));
    }

    @PostMapping("/api/common/logout")
    public Result<Void> logout() {
        LoginUser user = LoginContext.current();
        var claims = jwtService.parse((String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials());
        authStateService.revokeJti(user.jti(), claims.getExpiration().getTime() / 1000);
        return Result.ok();
    }
}
