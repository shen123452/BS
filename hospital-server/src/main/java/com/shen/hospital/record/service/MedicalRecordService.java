package com.shen.hospital.record.service;

import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.mapper.*;
import com.shen.hospital.schedule.mapper.ScheduleMapper;
import com.shen.hospital.record.domain.MedicalRecord;
import com.shen.hospital.record.dto.*;
import com.shen.hospital.record.mapper.MedicalRecordMapper;
import com.shen.hospital.registration.domain.Registration;
import com.shen.hospital.registration.dto.RegistrationView;
import com.shen.hospital.registration.mapper.RegistrationMapper;
import com.shen.hospital.registration.service.RegistrationService;

import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MedicalRecordService {
    private final MedicalRecordMapper records;
    private final RegistrationMapper registrations;
    private final RegistrationService registrationService;
    private final DoctorMapper doctors;
    private final PatientProfileMapper patients;
    private final DepartmentMapper departments;
    private final ScheduleMapper schedules;
    private final BusinessTime time;

    public MedicalRecordService(MedicalRecordMapper records, RegistrationMapper registrations,
                                RegistrationService registrationService, DoctorMapper doctors, PatientProfileMapper patients,
                                DepartmentMapper departments, ScheduleMapper schedules, BusinessTime time) {
        this.records = records;
        this.registrations = registrations;
        this.registrationService = registrationService;
        this.doctors = doctors;
        this.patients = patients;
        this.departments = departments;
        this.schedules = schedules;
        this.time = time;
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public RegistrationView start(Long userId, Long registrationId) {
        var r = lockOwn(userId, registrationId);
        if ("IN_PROGRESS".equals(r.getStatus()) || "COMPLETED".equals(r.getStatus())) return RegistrationView.of(r);
        if (!"WAITING".equals(r.getStatus()))
            throw BusinessException.conflict("REGISTRATION_NOT_WAITING", "预约已取消或不可接诊");
        if (!r.getVisitDate().equals(time.today()))
            throw BusinessException.conflict("VISIT_DATE_MISMATCH", "仅可接诊当天的预约");
        var now = time.now();
        if (registrations.start(r.getId(), r.getDoctorId(), now) != 1)
            throw BusinessException.conflict("REGISTRATION_CHANGED", "预约状态已变化，请刷新");
        var record = new MedicalRecord();
        record.setRegistrationId(r.getId());
        record.setDoctorId(r.getDoctorId());
        record.setChiefComplaint(r.getSymptomDescription());
        record.setStatus("DRAFT");
        record.setVersion(0);
        records.insert(record);
        return RegistrationView.of(registrations.selectById(registrationId));
    }

    public RecordView doctorRecord(Long userId, Long registrationId) {
        registrationService.doctorEntity(userId, registrationId);
        var record = records.byRegistration(registrationId);
        if (record == null) throw BusinessException.missing("病历");
        return RecordView.of(record);
    }

    public RecordView patientRecord(Long userId, Long registrationId) {
        registrationService.patientEntity(userId, registrationId);
        var record = records.byRegistration(registrationId);
        if (record == null || !"SUBMITTED".equals(record.getStatus())) throw BusinessException.missing("已提交病历");
        return RecordView.of(record);
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public RecordView save(Long userId, Long registrationId, RecordRequest request) {
        var r = lockOwn(userId, registrationId);
        if (!"IN_PROGRESS".equals(r.getStatus()))
            throw BusinessException.conflict("RECORD_NOT_EDITABLE", "仅可编辑接诊中的病历草稿");
        if (records.save(registrationId, r.getDoctorId(), request) != 1)
            throw BusinessException.conflict("RECORD_VERSION_CONFLICT", "病历已更新或提交，请刷新后编辑");
        return RecordView.of(records.byRegistration(registrationId));
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public RecordView complete(Long userId, Long registrationId, int version) {
        var r = lockOwn(userId, registrationId);
        var record = records.lockByRegistration(registrationId);
        if ("COMPLETED".equals(r.getStatus()) && record != null && "SUBMITTED".equals(record.getStatus()))
            return RecordView.of(record);
        if (!"IN_PROGRESS".equals(r.getStatus()) || record == null || !"DRAFT".equals(record.getStatus()))
            throw BusinessException.conflict("RECORD_NOT_EDITABLE", "当前预约不能提交病历");
        if (record.getVersion() != version)
            throw BusinessException.conflict("RECORD_VERSION_CONFLICT", "病历版本已变化，请刷新后提交");
        if (Stream.of(record.getChiefComplaint(), record.getPresentIllness(), record.getPastHistory(), record.getPhysicalExam(),
                        record.getAuxiliaryExam(), record.getPreliminaryDiagnosis(), record.getTreatmentPlan())
                .anyMatch(text -> text == null || text.isBlank() || text.length() > 10000))
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "RECORD_INCOMPLETE", "提交前请完整填写七段病历，每段不超过10000字符");
        var now = time.now();
        if (records.submit(registrationId, r.getDoctorId(), version, now) != 1
                || registrations.complete(registrationId, r.getDoctorId(), now) != 1)
            throw BusinessException.conflict("RECORD_VERSION_CONFLICT", "病历或预约状态已变化，本次提交未生效");
        return RecordView.of(records.byRegistration(registrationId));
    }

    private Registration lockOwn(Long userId, Long registrationId) {
        var doctorId = registrationService.doctorId(userId);
        var initial = registrations.selectById(registrationId);
        if (initial == null || !initial.getDoctorId().equals(doctorId)) throw BusinessException.missing("预约");
        // 与预约/取消统一：患者→科室→医生→场次→挂号。状态更新也可能检查外键父行。
        if (patients.lockById(initial.getPatientId()) == null || departments.lockById(initial.getDepartmentId()) == null
                || doctors.lockById(doctorId) == null || schedules.lockById(initial.getScheduleId()) == null)
            throw BusinessException.missing("预约关联信息");
        var r = registrations.lockById(registrationId);
        if (r == null || !r.getDoctorId().equals(doctorId)) throw BusinessException.missing("预约");
        return r;
    }
}
