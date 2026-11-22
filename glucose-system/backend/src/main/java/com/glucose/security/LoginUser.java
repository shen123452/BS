package com.glucose.security;

/**
 * 认证后的当前用户信息;role 来自实时数据库读取,不只信任 token 中的角色。
 */
public record LoginUser(Long userId, String username, String role, String dataMode,
                        long authVersion, String jti) {
}
