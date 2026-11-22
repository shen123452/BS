package com.glucose.system.init;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.enums.Codes;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 首次启动且库中无管理员时,从环境变量读取初始口令创建管理员;
 * 未配置时使用本地开发默认值并打警告(仅限个人毕设本地演示)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.core.env.Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long count = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getRole, Codes.ROLE_ADMIN));
        if (count != null && count > 0) {
            return;
        }
        String username = env("GLUCOSE_ADMIN_USERNAME", "admin");
        boolean local = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "test"));
        String password = env("GLUCOSE_ADMIN_PASSWORD", local ? "admin123" : null);
        if (password == null || password.isBlank()) throw new IllegalStateException("首次部署需要设置 GLUCOSE_ADMIN_PASSWORD");
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRealName("系统管理员");
        user.setRole(Codes.ROLE_ADMIN);
        user.setDataMode(Codes.KIND_REAL);
        user.setStatus(1);
        user.setAuthVersion(0L);
        user.setLastLoginAt(LocalDateTime.now().minusDays(1));
        sysUserMapper.insert(user);
        log.info("created initial admin '{}'", username);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
