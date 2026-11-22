package com.glucose.patient.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.api.Result;
import com.glucose.common.exception.BusinessException;
import com.glucose.patient.entity.GlucoseTargetRule;
import com.glucose.patient.entity.PatientProfile;
import com.glucose.patient.mapper.GlucoseTargetRuleMapper;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.security.AuthStateService;
import com.glucose.security.JwtService;
import com.glucose.security.LoginContext;
import com.glucose.security.LoginUser;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import com.glucose.system.service.AuditService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.glucose.common.enums.MeasureType;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 患者端:本人资料、目标(CGM + SMBG 分类型)与注销。
 */
@RestController
@RequestMapping("/api/app")
@PreAuthorize("hasRole('PATIENT')")
@RequiredArgsConstructor
@Validated
public class AppProfileController {

    private final SysUserMapper sysUserMapper;
    private final PatientProfileMapper profileMapper;
    private final GlucoseTargetRuleMapper targetRuleMapper;
    private final AuditService auditService;
    private final AuthStateService authStateService;
    private final JwtService jwtService;
    private final com.glucose.patient.service.PatientCleanupService cleanupService;

    public record ProfileVO(String userId, String username, String realName, String phone,
                            String gender, String birthDate, String diabetesType,
                            String cgmTargetLow, String cgmTargetHigh, Long targetRevision,
                            Long dataRevision, String dataMode, String privacyVersion,
                            String privacyAcceptedAt) {
    }

    @GetMapping("/profile")
    public Result<ProfileVO> getProfile() {
        long userId = LoginContext.currentUserId();
        SysUser user = requireUser(userId);
        PatientProfile profile = requireProfile(userId);
        return Result.ok(new ProfileVO(
                String.valueOf(userId), user.getUsername(), nullToEmpty(user.getRealName()),
                nullToEmpty(user.getPhone()),
                profile.getGender() == null ? "" : String.valueOf(profile.getGender()),
                profile.getBirthDate() == null ? "" : profile.getBirthDate().toString(),
                nullToEmpty(profile.getDiabetesType()),
                String.valueOf(profile.getCgmTargetLow()), String.valueOf(profile.getCgmTargetHigh()),
                profile.getTargetRevision(), profile.getDataRevision(), user.getDataMode(),
                nullToEmpty(profile.getPrivacyVersion()),
                profile.getPrivacyAcceptedAt() == null ? "" : profile.getPrivacyAcceptedAt().toString()));
    }

    @Data
    public static class ProfileUpdateReq {
        @Size(max = 50)
        private String realName;
        @Size(max = 20)
        private String phone;
        @Min(0) @Max(2)
        private Integer gender;
        private String birthDate;
        @Size(max = 20)
        private String diabetesType;
    }

    @PutMapping("/profile")
    @Transactional
    public Result<ProfileVO> updateProfile(@RequestBody @Validated ProfileUpdateReq req) {
        long userId = LoginContext.currentUserId();
        profileMapper.requireLock(userId);
        SysUser user = requireUser(userId);
        PatientProfile profile = requireProfile(userId);
        if (req.getRealName() != null) {
            user.setRealName(req.getRealName());
        }
        if (req.getPhone() != null) {
            user.setPhone(req.getPhone());
        }
        sysUserMapper.updateById(user);
        if (req.getGender() != null) {
            profile.setGender(req.getGender());
        }
        if (req.getBirthDate() != null) {
            try {
                profile.setBirthDate(req.getBirthDate().isBlank() ? null : java.time.LocalDate.parse(req.getBirthDate()));
                if (profile.getBirthDate() != null && profile.getBirthDate().isAfter(java.time.LocalDate.now())) throw new IllegalArgumentException();
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "出生日期须为有效日期且不能晚于今天");
            }
        }
        if (req.getDiabetesType() != null) {
            profile.setDiabetesType(req.getDiabetesType());
        }
        profileMapper.update(profile, Wrappers.<PatientProfile>lambdaUpdate().eq(PatientProfile::getId, profile.getId())
                .set(req.getBirthDate() != null && req.getBirthDate().isBlank(), PatientProfile::getBirthDate, null));
        return getProfile();
    }

    @Data
    public static class TargetsReq {
        private BigDecimal cgmTargetLow;
        private BigDecimal cgmTargetHigh;
        private Long targetRevision;
        @Valid @Size(max = 6)
        private List<@NotNull TargetRuleReq> rules;
        @Size(max = 30)
        private String privacyVersion;
    }

    @Data
    public static class TargetRuleReq {
        @NotNull @Min(1) @Max(6)
        private Integer measureType;
        private BigDecimal targetLow;
        private BigDecimal targetHigh;
        @Size(max = 200)
        private String basis;
        private boolean remove;
    }

    @GetMapping("/profile/targets")
    public Result<Map<String, Object>> getTargets() {
        long userId = LoginContext.currentUserId();
        PatientProfile profile = requireProfile(userId);
        List<GlucoseTargetRule> rules = targetRuleMapper.selectList(
                Wrappers.<GlucoseTargetRule>lambdaQuery().eq(GlucoseTargetRule::getPatientId, userId));
        return Result.ok(Map.of(
                "cgmTargetLow", profile.getCgmTargetLow(),
                "cgmTargetHigh", profile.getCgmTargetHigh(),
                "targetRevision", profile.getTargetRevision(),
                "rules", rules));
    }

    @PutMapping("/profile/targets")
    @Transactional
    public Result<Map<String, Object>> updateTargets(@RequestBody @Validated TargetsReq req) {
        long userId = LoginContext.currentUserId();
        LoginUser loginUser = LoginContext.current();
        PatientProfile profile = profileMapper.requireLock(userId);
        if (profile == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (req.getTargetRevision() == null || !req.getTargetRevision().equals(profile.getTargetRevision())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "目标版本已变化,请刷新后重试");
        }
        boolean changed = false;
        boolean privacyChanged = false;
        if ((req.getCgmTargetLow() == null) != (req.getCgmTargetHigh() == null)) throw new BusinessException(ErrorCode.PARAM_ERROR, "请同时填写目标上下限");
        if (req.getCgmTargetLow() != null && req.getCgmTargetHigh() != null) {
            validateRange(req.getCgmTargetLow(), req.getCgmTargetHigh());
            profile.setCgmTargetLow(req.getCgmTargetLow());
            profile.setCgmTargetHigh(req.getCgmTargetHigh());
            changed = true;
        }
        if (req.getPrivacyVersion() != null && !req.getPrivacyVersion().isBlank()) {
            if (!"privacy-v1".equals(req.getPrivacyVersion())) throw new BusinessException(ErrorCode.PARAM_ERROR, "隐私说明版本无效，请更新客户端");
            profile.setPrivacyVersion(req.getPrivacyVersion());
            profile.setPrivacyAcceptedAt(LocalDateTime.now());
            privacyChanged = true;
        }
        if (req.getRules() != null) {
            java.util.Set<Integer> seenTypes = new java.util.HashSet<>();
            for (TargetRuleReq r : req.getRules()) {
                if (r == null || !MeasureType.isValid(r.getMeasureType()) || !seenTypes.add(r.getMeasureType())) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "测量类型须为 1–6 且不能重复");
                }
                if (r.isRemove()) {
                    targetRuleMapper.delete(Wrappers.<GlucoseTargetRule>lambdaQuery()
                            .eq(GlucoseTargetRule::getPatientId, userId).eq(GlucoseTargetRule::getMeasureType, r.getMeasureType()));
                    changed = true;
                    continue;
                }
                validateRange(r.getTargetLow(), r.getTargetHigh());
                GlucoseTargetRule existing = targetRuleMapper.selectOne(Wrappers.<GlucoseTargetRule>lambdaQuery()
                        .eq(GlucoseTargetRule::getPatientId, userId)
                        .eq(GlucoseTargetRule::getMeasureType, r.getMeasureType()));
                if (existing == null) {
                    existing = new GlucoseTargetRule();
                    existing.setPatientId(userId);
                    existing.setMeasureType(r.getMeasureType());
                    existing.setTargetLow(r.getTargetLow());
                    existing.setTargetHigh(r.getTargetHigh());
                    existing.setBasis(r.getBasis());
                    existing.setUpdatedBy(userId);
                    targetRuleMapper.insert(existing);
                } else {
                    existing.setTargetLow(r.getTargetLow());
                    existing.setTargetHigh(r.getTargetHigh());
                    existing.setBasis(r.getBasis());
                    existing.setUpdatedBy(userId);
                    targetRuleMapper.updateById(existing);
                }
                changed = true;
            }
        }
        if (changed || privacyChanged) {
            profile.setTargetRevision(profile.getTargetRevision() + 1);
            profileMapper.updateById(profile);
            auditService.record(loginUser.userId(), "target.update", "patient_profile",
                    String.valueOf(userId), "更新血糖目标", null);
        }
        return getTargets();
    }

    @DeleteMapping("/account")
    @Transactional
    public Result<Void> deleteAccount() {
        long userId = LoginContext.currentUserId();
        LoginUser loginUser = LoginContext.current();
        SysUser user = requireUser(userId);
        profileMapper.requireLock(userId);
        user.setStatus(0);
        user.setAuthVersion(user.getAuthVersion() + 1);
        // 保留不可登录的账号墓碑与最少审计标识，移除可识别字段及本人业务数据。
        sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate().eq(SysUser::getId, userId)
                .set(SysUser::getStatus, 0).set(SysUser::getAuthVersion, user.getAuthVersion())
                .set(SysUser::getRealName, null).set(SysUser::getPhone, null).set(SysUser::getOpenid, null)
                .set(SysUser::getAvatarUrl, null).set(SysUser::getPassword, null).set(SysUser::getDeleted, 1)
                .set(SysUser::getUsername, "deleted_" + userId));
        cleanupService.deleteData(userId);
        var claims = jwtService.parse((String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials());
        authStateService.revokeJti(loginUser.jti(), claims.getExpiration().getTime() / 1000);
        auditService.record(userId, "account.delete", "sys_user", String.valueOf(userId),
                "患者注销，已清理业务数据及账号个人字段", null);
        return Result.ok();
    }

    /* helpers */

    private static void validateRange(BigDecimal low, BigDecimal high) {
        if (low == null || high == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "目标范围不能为空");
        }
        if (low.stripTrailingZeros().scale() > 1 || high.stripTrailingZeros().scale() > 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "目标值最多保留一位小数");
        }
        if (low.compareTo(BigDecimal.valueOf(0.5)) < 0 || high.compareTo(BigDecimal.valueOf(40.0)) > 0
                || low.compareTo(high) >= 0) {
            throw new BusinessException(ErrorCode.VALUE_OUT_OF_RANGE, "目标范围须满足 0.5 ≤ 低值 < 高值 ≤ 40.0");
        }
    }

    private SysUser requireUser(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return user;
    }

    private PatientProfile requireProfile(long userId) {
        PatientProfile profile = profileMapper.selectOne(Wrappers.<PatientProfile>lambdaQuery()
                .eq(PatientProfile::getUserId, userId));
        if (profile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return profile;
    }

    private PatientProfile profileByUser(long userId) {
        return profileMapper.selectOne(Wrappers.<PatientProfile>lambdaQuery()
                .eq(PatientProfile::getUserId, userId));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
