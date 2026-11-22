package com.glucose.patient.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.enums.Codes;
import com.glucose.common.exception.BusinessException;
import com.glucose.patient.entity.PatientProfile;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 患者数据统一授权入口:所有详情、列表、消息与导入接口必须调用。
 * 医生范围由 patient_profile.doctor_id 决定;管理员放行并留审计(由调用方记录)。
 */
@Service
@RequiredArgsConstructor
public class PatientAccessService {

    private final PatientProfileMapper profileMapper;

    /** 校验当前用户可读取指定患者;返回该患者档案。 */
    public PatientProfile requireReadable(LoginUser user, long patientId) {
        if (Codes.ROLE_PATIENT.equals(user.role())) {
            if (user.userId() != patientId) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        } else if (Codes.ROLE_DOCTOR.equals(user.role())) {
            PatientProfile profile = requireProfile(patientId);
            if (!java.util.Objects.equals(profile.getDoctorId(), user.userId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            return profile;
        } else if (!Codes.ROLE_ADMIN.equals(user.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return requireProfile(patientId);
    }

    public PatientProfile requireProfile(long patientId) {
        PatientProfile profile = profileMapper.selectOne(Wrappers.<PatientProfile>lambdaQuery()
                .eq(PatientProfile::getUserId, patientId));
        if (profile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "患者不存在");
        }
        return profile;
    }
}
