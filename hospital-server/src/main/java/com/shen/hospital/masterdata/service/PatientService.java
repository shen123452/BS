package com.shen.hospital.masterdata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.masterdata.domain.PatientProfile;
import com.shen.hospital.masterdata.dto.*;
import com.shen.hospital.masterdata.mapper.PatientProfileMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {
    private final PatientProfileMapper patients;

    public PatientService(PatientProfileMapper patients) {
        this.patients = patients;
    }

    public ProfileView get(Long userId) {
        return ProfileView.of(own(userId));
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public ProfileView update(Long userId, ProfileRequest request) {
        requireHistory(request.chronicStatus(), request.chronicHistory(), "慢病史");
        requireHistory(request.allergyStatus(), request.allergyHistory(), "过敏史");
        var p = own(userId);
        p.setName(request.name().strip());
        p.setGender(request.gender());
        p.setAge(request.age());
        p.setChronicStatus(request.chronicStatus());
        p.setChronicHistory(cleanHistory(request.chronicStatus(), request.chronicHistory()));
        p.setAllergyStatus(request.allergyStatus());
        p.setAllergyHistory(cleanHistory(request.allergyStatus(), request.allergyHistory()));
        patients.updateOwn(userId, p);
        return ProfileView.of(p);
    }

    private PatientProfile own(Long userId) {
        var profile = patients.selectOne(new LambdaQueryWrapper<PatientProfile>().eq(PatientProfile::getUserId, userId));
        if (profile == null) throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "患者档案不存在");
        return profile;
    }

    private void requireHistory(String status, String text, String field) {
        if ("KNOWN".equals(status) && (text == null || text.isBlank()))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "HISTORY_REQUIRED", field + "状态为已知时，请填写正文");
    }

    private String cleanHistory(String status, String text) {
        return "KNOWN".equals(status) ? text.strip() : null;
    }
}
