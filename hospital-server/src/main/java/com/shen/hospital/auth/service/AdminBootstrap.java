package com.shen.hospital.auth.service;

import com.shen.hospital.auth.domain.Role;

import java.util.regex.Pattern;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "hospital.bootstrap.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final AuthService auth;
    private final String username;
    private final String password;

    public AdminBootstrap(AuthService auth, @Value("${hospital.bootstrap.username}") String username,
                          @Value("${hospital.bootstrap.password}") String password) {
        this.auth = auth;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public void run(ApplicationArguments args) {
        if (!Pattern.matches("[A-Za-z0-9_.-]{3,64}", username))
            throw new IllegalArgumentException("管理员用户名格式无效");
        var existing = auth.findByUsername(username);
        if (existing != null) {
            if (!Role.ADMIN.name().equals(existing.getRole()))
                throw new IllegalStateException("初始化管理员用户名已被非管理员占用");
            log.info("管理员已存在，保留原密码和状态");
            return;
        }
        auth.createUser(username, password, "系统管理员", Role.ADMIN);
        log.info("管理员初始化完成");
    }
}
