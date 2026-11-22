package com.glucose.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.alert.entity.AlertRecord;
import com.glucose.alert.mapper.AlertRecordMapper;
import com.glucose.common.enums.Codes;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.patient.entity.PatientProfile;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.security.LoginUser;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkbenchDashboardService {

    private final SysUserMapper userMapper;
    private final PatientProfileMapper profileMapper;
    private final GlucoseRecordMapper recordMapper;
    private final AlertRecordMapper alertMapper;
    private final Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    public record Overview(long activePatients, long recordsLast7Days, long pendingAlerts,
                           LocalDateTime updatedAt) {}

    public Overview overview(LoginUser viewer) {
        if (!Codes.ROLE_DOCTOR.equals(viewer.role()) && !Codes.ROLE_ADMIN.equals(viewer.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> scopedPatientIds = null;
        long patientCount;

        if (Codes.ROLE_DOCTOR.equals(viewer.role())) {
            List<Long> boundPatientIds = profileMapper.selectList(Wrappers.<PatientProfile>lambdaQuery()
                            .select(PatientProfile::getUserId)
                            .eq(PatientProfile::getDoctorId, viewer.userId()))
                    .stream().map(PatientProfile::getUserId).filter(id -> id != null).distinct().toList();
            if (boundPatientIds.isEmpty()) return empty(now);

            scopedPatientIds = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                            .select(SysUser::getId)
                            .eq(SysUser::getRole, Codes.ROLE_PATIENT)
                            .eq(SysUser::getStatus, 1)
                            .in(SysUser::getId, boundPatientIds))
                    .stream().map(SysUser::getId).filter(id -> id != null).distinct().toList();
            patientCount = scopedPatientIds.size();
            if (scopedPatientIds.isEmpty()) return empty(now);
        } else {
            patientCount = value(userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getRole, Codes.ROLE_PATIENT)
                    .eq(SysUser::getStatus, 1)));
        }

        var recordQuery = Wrappers.<GlucoseRecord>lambdaQuery()
                .ge(GlucoseRecord::getMeasuredAt, now.minusDays(7))
                .lt(GlucoseRecord::getMeasuredAt, now);
        var alertQuery = Wrappers.<AlertRecord>lambdaQuery()
                .eq(AlertRecord::getHandleStatus, Codes.HANDLE_PENDING)
                .ne(AlertRecord::getEventStatus, Codes.EVENT_INVALIDATED);
        if (scopedPatientIds != null) {
            recordQuery.in(GlucoseRecord::getPatientId, scopedPatientIds);
            alertQuery.in(AlertRecord::getPatientId, scopedPatientIds);
        }

        return new Overview(patientCount, value(recordMapper.selectCount(recordQuery)),
                value(alertMapper.selectCount(alertQuery)), now);
    }

    private static Overview empty(LocalDateTime now) {
        return new Overview(0, 0, 0, now);
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }
}
