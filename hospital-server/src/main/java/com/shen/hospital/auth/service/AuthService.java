package com.shen.hospital.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shen.hospital.auth.domain.*;
import com.shen.hospital.auth.dto.*;
import com.shen.hospital.auth.mapper.UserMapper;
import com.shen.hospital.auth.security.JwtService;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.masterdata.domain.PatientProfile;
import com.shen.hospital.masterdata.mapper.*;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserMapper users;
    private final PatientProfileMapper patients;
    private final DoctorMapper doctors;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final String dummyHash;

    public AuthService(UserMapper users, PatientProfileMapper patients, DoctorMapper doctors, PasswordEncoder passwords, JwtService jwt) {
        this.users = users;
        this.patients = patients;
        this.doctors = doctors;
        this.passwords = passwords;
        this.jwt = jwt;
        this.dummyHash = passwords.encode("non-account-dummy-password");
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public UserView register(RegisterRequest request) {
        var user = createUser(request.username(), request.password(), request.displayName(), Role.PATIENT);
        var profile = new PatientProfile();
        profile.setUserId(user.getId());
        profile.setGender("UNKNOWN");
        profile.setChronicStatus("UNKNOWN");
        profile.setAllergyStatus("UNKNOWN");
        patients.insert(profile);
        return UserView.of(user, profile.getId(), null);
    }

    public record LoginResult(String token, long expiresIn, UserView user) {
    }

    public LoginResult login(LoginRequest request) {
        var user = findByUsername(request.username());
        boolean valid = request.password().getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !valid)
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        if (!"ACTIVE".equals(user.getStatus()))
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "账号已被禁用");
        return new LoginResult(jwt.issue(user.getId()), jwt.expiresIn(), view(user));
    }

    public UserView me(Long userId) {
        var user = users.selectById(userId);
        if (user == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "账号不存在");
        return view(user);
    }

    public UserView view(SysUser user) {
        Long patientId = patients.findIdByUserId(user.getId());
        Long doctorId = doctors.findIdByUserId(user.getId());
        return UserView.of(user, patientId, doctorId);
    }

    public SysUser findByUsername(String username) {
        return users.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, normalize(username)));
    }

    public SysUser createUser(String username, String password, String displayName, Role role) {
        validatePassword(password);
        var user = new SysUser();
        user.setUsername(normalize(username));
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(displayName.strip());
        user.setRole(role.name());
        user.setStatus("ACTIVE");
        users.insert(user);
        return user;
    }

    public static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码至少8个字符，UTF-8编码不超过72字节");
    }
}
