package com.shen.hospital.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shen.hospital.auth.domain.Role;
import com.shen.hospital.auth.mapper.UserMapper;
import com.shen.hospital.common.ApiResponse;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserMapper users;
    private final ObjectMapper json;

    public JwtAuthenticationFilter(JwtService jwt, UserMapper users, ObjectMapper json) {
        this.jwt = jwt;
        this.users = users;
        this.json = json;
    }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            Long id;
            try {
                if (!header.startsWith("Bearer ")) throw new IllegalArgumentException();
                id = jwt.verify(header.substring(7));
            } catch (JwtException | IllegalArgumentException ex) {
                error(response, 401, "INVALID_TOKEN", "登录凭证无效或已过期");
                return;
            }
            try {
                var user = users.selectById(id);
                if (user == null) {
                    error(response, 401, "INVALID_TOKEN", "登录凭证无效");
                    return;
                }
                if (!"ACTIVE".equals(user.getStatus())) {
                    error(response, 401, "ACCOUNT_DISABLED", "账号已被禁用");
                    return;
                }
                Role role;
                try {
                    role = Role.valueOf(user.getRole());
                } catch (IllegalArgumentException ex) {
                    error(response, 401, "INVALID_ACCOUNT", "账号角色无效");
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken(CurrentUser.of(user), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (DataAccessException ex) {
                error(response, 503, "DATABASE_UNAVAILABLE", "数据服务暂不可用");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
