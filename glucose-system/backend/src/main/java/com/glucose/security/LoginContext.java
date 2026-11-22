package com.glucose.security;

import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class LoginContext {

    private LoginContext() {
    }

    public static LoginUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    public static long currentUserId() {
        return current().userId();
    }
}
