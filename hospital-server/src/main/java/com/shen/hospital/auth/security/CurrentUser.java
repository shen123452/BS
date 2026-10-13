package com.shen.hospital.auth.security;

import com.shen.hospital.auth.domain.SysUser;
import com.shen.hospital.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

public record CurrentUser(Long id, String username, String role) {
    public static CurrentUser of(SysUser user) {
        return new CurrentUser(user.getId(), user.getUsername(), user.getRole());
    }

    public static CurrentUser get() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) return user;
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
    }
}
