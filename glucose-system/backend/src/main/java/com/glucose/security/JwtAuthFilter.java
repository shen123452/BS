package com.glucose.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.api.Result;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 校验 Bearer token:签名、期限、jti 黑名单、账号状态与 authVersion;
 * 角色以数据库实时值为准,不只信任 token。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthStateService authStateService;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Authentication entry points must work even when a client sends an old token.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ("POST".equals(request.getMethod())
                && ("/api/admin/auth/login".equals(path) || "/api/app/auth/wx-login".equals(path)))
                || ("GET".equals(request.getMethod()) && "/api/common/captcha".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(7);
        try {
            Claims claims = jwtService.parse(token);
            String jti = claims.getId();
            if (jti == null || authStateService.isJtiRevoked(jti)) {
                writeUnauthorized(response);
                return;
            }
            Long userId = Long.valueOf(claims.getSubject());
            SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getId, userId));
            if (user == null || user.getStatus() == null || user.getStatus() != 1
                    || claims.get("authVersion") == null
                    || ((Number) claims.get("authVersion")).longValue() != user.getAuthVersion()) {
                writeUnauthorized(response);
                return;
            }
            LoginUser principal = new LoginUser(user.getId(), user.getUsername(), user.getRole(),
                    user.getDataMode(), user.getAuthVersion(), jti);
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
            var authentication = new UsernamePasswordAuthenticationToken(principal, token, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            writeUnauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ErrorCode.UNAUTHORIZED)));
    }
}
