package com.glucose.system.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.api.PageResult;
import com.glucose.common.api.Result;
import com.glucose.common.enums.Codes;
import com.glucose.common.exception.BusinessException;
import com.glucose.security.LoginContext;
import com.glucose.security.LoginUser;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import com.glucose.system.service.AuditService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 医生账号管理(仅管理员)与患者管理。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Validated
public class SystemAdminController {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final com.glucose.patient.service.PatientAccessService patientAccessService;
    private final com.glucose.patient.mapper.PatientProfileMapper profileMapper;

    /* ================= 医生账号 ================= */

    public record DoctorUpsertReq(@NotBlank String username, String realName, String phone,
                                  String password) {
    }

    public record DoctorVO(String id, String username, String realName, String phone,
                           Integer status, String createdAt) {
    }

    @GetMapping("/users/doctors")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<DoctorVO>> listDoctors(@RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "20") long pageSize) {
        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getRole, Codes.ROLE_DOCTOR)
                        .orderByDesc(SysUser::getCreatedAt));
        return Result.ok(PageResult.of(page.getTotal(), page.getCurrent(),
                page.getRecords().stream().map(SystemAdminController::toDoctorVO).toList()));
    }

    @PostMapping("/users/doctors")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DoctorVO> createDoctor(@RequestBody @Validated DoctorUpsertReq req) {
        Long exists = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, req.username()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(requirePassword(req.password())));
        user.setRealName(req.realName());
        user.setPhone(req.phone());
        user.setRole(Codes.ROLE_DOCTOR);
        user.setDataMode(Codes.KIND_REAL);
        user.setStatus(1);
        user.setAuthVersion(0L);
        sysUserMapper.insert(user);
        auditService.record(LoginContext.currentUserId(), "doctor.create", "sys_user",
                String.valueOf(user.getId()), "创建医生账号 " + req.username(), null);
        return Result.ok(toDoctorVO(user));
    }

    @PutMapping("/users/doctors/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DoctorVO> updateDoctor(@PathVariable long id, @RequestBody DoctorUpsertReq req) {
        SysUser user = requireDoctor(id);
        if (req.realName() != null) {
            user.setRealName(req.realName());
        }
        if (req.phone() != null) {
            user.setPhone(req.phone());
        }
        if (req.password() != null && !req.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(requirePassword(req.password())));
            user.setAuthVersion(user.getAuthVersion() + 1);
        }
        sysUserMapper.updateById(user);
        auditService.record(LoginContext.currentUserId(), "doctor.update", "sys_user", String.valueOf(id),
                "更新医生账号 " + user.getUsername(), null);
        return Result.ok(toDoctorVO(user));
    }

    @PutMapping("/users/doctors/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> toggleDoctor(@PathVariable long id, @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status 必须为 0 或 1");
        }
        SysUser user = requireDoctor(id);
        user.setStatus(status);
        user.setAuthVersion(user.getAuthVersion() + 1);
        sysUserMapper.updateById(user);
        auditService.record(LoginContext.currentUserId(), "doctor.status", "sys_user", String.valueOf(id),
                (status == 1 ? "启用" : "停用") + "医生账号 " + user.getUsername(), null);
        return Result.ok();
    }

    /* ================= 患者管理 ================= */

    public record PatientListVO(String userId, String username, String realName, String phone,
                                String dataMode, Integer status, String doctorId, String doctorName,
                                String diabetesType, String createdAt) {
    }

    @GetMapping("/patients")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<PageResult<PatientListVO>> listPatients(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword) {
        LoginUser user = LoginContext.current();
        var query = Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getRole, Codes.ROLE_PATIENT);
        if (Codes.ROLE_DOCTOR.equals(user.role())) {
            List<Long> boundIds = profileMapper.selectList(Wrappers.<com.glucose.patient.entity.PatientProfile>lambdaQuery()
                            .eq(com.glucose.patient.entity.PatientProfile::getDoctorId, user.userId()))
                    .stream().map(com.glucose.patient.entity.PatientProfile::getUserId).toList();
            if (boundIds.isEmpty()) {
                return Result.ok(PageResult.of(0, pageNum, List.of()));
            }
            query.in(SysUser::getId, boundIds);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword));
        }
        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                query.orderByDesc(SysUser::getCreatedAt));
        Map<Long, String> doctorNames = loadDoctorNames();
        List<PatientListVO> records = page.getRecords().stream()
                .map(u -> toPatientVO(user, u, doctorNames))
                .toList();
        return Result.ok(PageResult.of(page.getTotal(), page.getCurrent(), records));
    }

    @GetMapping("/patients/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<Map<String, Object>> patientDetail(@PathVariable long id) {
        LoginUser user = LoginContext.current();
        patientAccessService.requireReadable(user, id);
        SysUser u = sysUserMapper.selectById(id);
        var profile = patientAccessService.requireProfile(id);
        Map<Long, String> doctorNames = loadDoctorNames();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("userId", String.valueOf(u.getId()));
        out.put("username", u.getUsername());
        out.put("realName", u.getRealName() == null ? "" : u.getRealName());
        out.put("phone", u.getPhone() == null ? "" : u.getPhone());
        out.put("dataMode", u.getDataMode());
        out.put("status", u.getStatus());
        out.put("gender", profile.getGender() == null ? "" : profile.getGender());
        out.put("birthDate", profile.getBirthDate() == null ? "" : profile.getBirthDate().toString());
        out.put("diabetesType", profile.getDiabetesType() == null ? "" : profile.getDiabetesType());
        out.put("cgmTargetLow", profile.getCgmTargetLow());
        out.put("cgmTargetHigh", profile.getCgmTargetHigh());
        out.put("targetRevision", profile.getTargetRevision());
        out.put("dataRevision", profile.getDataRevision());
        out.put("doctorId", profile.getDoctorId() == null ? "" : String.valueOf(profile.getDoctorId()));
        out.put("doctorName", profile.getDoctorId() == null ? ""
                : doctorNames.getOrDefault(profile.getDoctorId(), ""));
        return Result.ok(out);
    }

    @PutMapping("/patients/{id}/doctor")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> bindDoctor(@PathVariable long id, @RequestBody Map<String, Long> body) {
        var profile = patientAccessService.requireProfile(id);
        Long doctorId = body.get("doctorId");
        if (doctorId != null && doctorId > 0) {
            SysUser doctor = requireDoctor(doctorId);
            if (doctor.getStatus() != 1) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "目标医生账号已停用");
            }
            profile.setDoctorId(doctorId);
        } else {
            profile.setDoctorId(null);
        }
        profileMapper.update(null, Wrappers.<com.glucose.patient.entity.PatientProfile>lambdaUpdate()
                .eq(com.glucose.patient.entity.PatientProfile::getId, profile.getId())
                .set(com.glucose.patient.entity.PatientProfile::getDoctorId, profile.getDoctorId()));
        auditService.record(LoginContext.currentUserId(), "patient.bind", "patient_profile",
                String.valueOf(id), "设置患者绑定医生: " + doctorId, null);
        return Result.ok();
    }

    @PutMapping("/patients/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> togglePatient(@PathVariable long id, @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status 必须为 0 或 1");
        }
        SysUser user = requireUser(id);
        user.setStatus(status);
        user.setAuthVersion(user.getAuthVersion() + 1);
        sysUserMapper.updateById(user);
        auditService.record(LoginContext.currentUserId(), "patient.status", "sys_user", String.valueOf(id),
                (status == 1 ? "启用" : "停用") + "患者账号,旧凭证失效", null);
        return Result.ok();
    }

    /* ================= helpers ================= */

    private static DoctorVO toDoctorVO(SysUser user) {
        return new DoctorVO(String.valueOf(user.getId()), user.getUsername(), user.getRealName(),
                user.getPhone(), user.getStatus(),
                user.getCreatedAt() == null ? "" : user.getCreatedAt().toString());
    }

    private PatientListVO toPatientVO(LoginUser viewer, SysUser u, Map<Long, String> doctorNames) {
        var profile = profileMapper.selectOne(Wrappers.<com.glucose.patient.entity.PatientProfile>lambdaQuery()
                .eq(com.glucose.patient.entity.PatientProfile::getUserId, u.getId()));
        String doctorId = profile != null && profile.getDoctorId() != null ? String.valueOf(profile.getDoctorId()) : "";
        return new PatientListVO(String.valueOf(u.getId()), u.getUsername(),
                u.getRealName() == null ? "" : u.getRealName(),
                u.getPhone() == null ? "" : u.getPhone(),
                u.getDataMode(), u.getStatus(), doctorId,
                doctorId.isEmpty() ? "" : doctorNames.getOrDefault(Long.parseLong(doctorId), ""),
                profile == null || profile.getDiabetesType() == null ? "" : profile.getDiabetesType(),
                u.getCreatedAt() == null ? "" : u.getCreatedAt().toString());
    }

    private Map<Long, String> loadDoctorNames() {
        List<SysUser> doctors = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getRole, Codes.ROLE_DOCTOR));
        return doctors.stream().collect(java.util.stream.Collectors.toMap(
                SysUser::getId,
                u -> u.getRealName() == null ? u.getUsername() : u.getRealName(),
                (a, b) -> a));
    }

    private SysUser requireDoctor(long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null || !Codes.ROLE_DOCTOR.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "医生不存在");
        }
        return user;
    }

    private SysUser requireUser(long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return user;
    }

    private static String requirePassword(String password) {
        if (password == null || password.length() < 6) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码至少 6 位");
        }
        return password;
    }
}
