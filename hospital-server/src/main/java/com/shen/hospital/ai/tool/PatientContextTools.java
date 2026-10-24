package com.shen.hospital.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.*;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.masterdata.domain.PatientProfile;
import com.shen.hospital.masterdata.mapper.PatientProfileMapper;
import com.shen.hospital.record.domain.MedicalRecord;
import com.shen.hospital.record.mapper.MedicalRecordMapper;
import com.shen.hospital.registration.domain.Registration;
import com.shen.hospital.registration.mapper.RegistrationMapper;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 患者上下文只读工具：患者身份一律来自导诊运行上下文，工具参数不接受患者ID。
 */
@Service
public class PatientContextTools {
    private final PatientProfileMapper patients;
    private final RegistrationMapper registrations;
    private final MedicalRecordMapper records;
    private final ObjectMapper json;

    public PatientContextTools(PatientProfileMapper patients, RegistrationMapper registrations,
                               MedicalRecordMapper records, ObjectMapper json) {
        this.patients = patients; this.registrations = registrations; this.records = records; this.json = json;
    }

    public JsonNode profile(Long patientId) {
        requirePatientArgFree();
        PatientProfile profile = patientId == null ? null : patients.selectById(patientId);
        if (profile == null) throw BusinessException.missing("患者档案");
        var node = json.createObjectNode();
        node.put("patientId", profile.getId());
        node.put("gender", profile.getGender());
        if (profile.getAge() == null) node.putNull("age"); else node.put("age", profile.getAge());
        node.put("chronicStatus", profile.getChronicStatus());
        node.put("chronicHistory", orEmpty(profile.getChronicHistory()));
        node.put("allergyStatus", profile.getAllergyStatus());
        node.put("allergyHistory", orEmpty(profile.getAllergyHistory()));
        node.put("note", "UNKNOWN 表示尚未提供，NONE 表示本人明确否认，不代表正常或异常结论");
        return node;
    }

    public JsonNode visits(Long patientId) {
        requirePatientArgFree();
        if (patientId == null) throw BusinessException.missing("患者档案");
        var completed = registrations.selectList(new LambdaQueryWrapper<Registration>()
                .eq(Registration::getPatientId, patientId).eq(Registration::getStatus, "COMPLETED")
                .orderByDesc(Registration::getVisitDate).orderByDesc(Registration::getId).last("LIMIT 3"));
        var array = json.createArrayNode();
        for (var row : completed) {
            var record = records.selectOne(new LambdaQueryWrapper<MedicalRecord>()
                    .eq(MedicalRecord::getRegistrationId, row.getId()).eq(MedicalRecord::getStatus, "SUBMITTED")
                    .orderByDesc(MedicalRecord::getId).last("LIMIT 1"));
            var node = json.createObjectNode();
            node.put("visitDate", row.getVisitDate().toString());
            node.put("timeSlot", row.getTimeSlot());
            node.put("departmentName", row.getDepartmentNameSnapshot());
            node.put("doctorName", row.getDoctorNameSnapshot());
            node.put("chiefComplaint", clip(record == null ? null : record.getChiefComplaint(), 200));
            node.put("preliminaryDiagnosis", clip(record == null ? null : record.getPreliminaryDiagnosis(), 200));
            array.add(node);
        }
        var result = json.createObjectNode();
        result.set("completedVisits", array);
        result.put("note", completed.isEmpty() ? "本人暂无已完成就诊" : "最近三次已完成就诊摘要");
        return result;
    }

    private static void requirePatientArgFree() {
        // 工具入参不接受任何患者身份字段；身份只来自运行上下文注入。
    }

    private static String orEmpty(String value) { return value == null ? "" : value.strip(); }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
    }
}
