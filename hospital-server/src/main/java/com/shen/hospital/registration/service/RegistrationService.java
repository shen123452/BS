package com.shen.hospital.registration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.dto.ProfileView;
import com.shen.hospital.masterdata.mapper.*;
import com.shen.hospital.registration.domain.Registration;
import com.shen.hospital.registration.dto.*;
import com.shen.hospital.registration.mapper.RegistrationMapper;
import com.shen.hospital.schedule.mapper.ScheduleMapper;
import com.shen.hospital.schedule.service.ScheduleParticipants;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RegistrationService {
    private final RegistrationMapper registrations;
    private final ScheduleMapper schedules;
    private final PatientProfileMapper patients;
    private final DoctorMapper doctors;
    private final DepartmentMapper departments;
    private final ScheduleParticipants participants;
    private final BusinessTime time;
    private final ObjectMapper json;

    public RegistrationService(RegistrationMapper registrations, ScheduleMapper schedules, PatientProfileMapper patients,
                               DoctorMapper doctors, DepartmentMapper departments, ScheduleParticipants participants, BusinessTime time, ObjectMapper json) {
        this.registrations = registrations;
        this.schedules = schedules;
        this.patients = patients;
        this.doctors = doctors;
        this.departments = departments;
        this.participants = participants;
        this.time = time;
        this.json = json;
    }

    public record BookingResult(RegistrationView registration, boolean created) {
    }

    public record DoctorDetail(RegistrationView registration, ProfileView patient) {
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public BookingResult book(Long userId, RegistrationRequest request) {
        // 同患者的建单和取消共用档案行锁；READ_COMMITTED避免空查询产生跨患者间隙锁。
        var patient = patients.lockByUserId(userId);
        if (patient == null) throw BusinessException.missing("患者档案");
        String symptom = normalize(request.symptomDescription());
        String hash = requestHash(request, symptom);
        var previous = registrations.byRequest(patient.getId(), request.clientRequestId());
        if (previous != null) {
            if (!hash.equals(previous.getRequestHash()))
                throw BusinessException.conflict("REQUEST_ID_CONFLICT", "该请求号已用于其他预约内容，请重新确认");
            return new BookingResult(RegistrationView.of(previous), false);
        }
        if (patient.getName() == null || patient.getName().isBlank())
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "PROFILE_INCOMPLETE", "请先完善本人姓名");
        var initial = schedules.selectById(request.scheduleId());
        if (initial == null) throw BusinessException.missing("排班");
        var actor = participants.lockEnabled(initial.getDoctorId());
        var schedule = schedules.lockById(initial.getId());
        if (schedule == null || !schedule.getDoctorId().equals(actor.doctor().getId())
                || !schedule.getDepartmentId().equals(actor.department().getId()))
            throw BusinessException.conflict("SCHEDULE_CHANGED", "场次所属医生或科室已变化，请刷新后重试");
        var now = time.now();
        if (!"OPEN".equals(schedule.getStatus()))
            throw BusinessException.conflict("SCHEDULE_CLOSED", "场次已关闭");
        if (schedule.getVisitDate().isBefore(now.toLocalDate()) || schedule.getVisitDate().isAfter(now.toLocalDate().plusDays(6)))
            throw BusinessException.conflict("SCHEDULE_OUT_OF_RANGE", "只能预约今天起七天内的场次");
        if (!schedule.getBookingDeadline().isAfter(now))
            throw BusinessException.conflict("BOOKING_EXPIRED", "该场次已截止预约");
        if (registrations.activeSlot(patient.getId(), schedule.getVisitDate(), schedule.getTimeSlot()) != null)
            throw BusinessException.conflict("DUPLICATE_ACTIVE_SLOT", "同一日期、时段已有待就诊或接诊中的预约");
        if (request.triageResultId() != null)
            requireTriage(request.triageResultId(), patient.getId(), schedule.getDepartmentId(), now);
        if (schedules.takeOne(schedule.getId(), now) != 1)
            throw BusinessException.conflict("SCHEDULE_FULL", "该场次暂无剩余号源，请刷新后选择");
        var r = new Registration();
        r.setRegistrationNo("R" + UUID.randomUUID().toString().replace("-", ""));
        r.setPatientId(patient.getId());
        r.setScheduleId(schedule.getId());
        r.setDoctorId(schedule.getDoctorId());
        r.setDepartmentId(schedule.getDepartmentId());
        r.setClientRequestId(request.clientRequestId());
        r.setRequestHash(hash);
        r.setTriageResultId(request.triageResultId());
        r.setVisitDate(schedule.getVisitDate());
        r.setTimeSlot(schedule.getTimeSlot());
        r.setPatientNameSnapshot(patient.getName().strip());
        r.setDoctorNameSnapshot(actor.doctor().getName());
        r.setDepartmentNameSnapshot(actor.department().getName());
        r.setFeeSnapshot(schedule.getFee());
        r.setSymptomDescription(symptom);
        r.setStatus("WAITING");
        registrations.insert(r);
        return new BookingResult(RegistrationView.of(registrations.selectById(r.getId())), true);
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public RegistrationView cancel(Long userId, Long id, String reason) {
        var patient = patients.lockByUserId(userId);
        if (patient == null) throw BusinessException.missing("患者档案");
        var initial = registrations.selectById(id);
        if (initial == null || !initial.getPatientId().equals(patient.getId())) throw BusinessException.missing("预约");
        if ("CANCELLED".equals(initial.getStatus())) return RegistrationView.of(initial);
        // MySQL5.7在状态UPDATE时也检查外键。先锁外键父行，避免与接诊/管理操作反向等待。
        if (departments.lockById(initial.getDepartmentId()) == null || doctors.lockById(initial.getDoctorId()) == null)
            throw BusinessException.missing("预约关联信息");
        var schedule = schedules.lockById(initial.getScheduleId());
        var r = registrations.lockById(id);
        if (r == null || !r.getPatientId().equals(patient.getId())) throw BusinessException.missing("预约");
        if ("CANCELLED".equals(r.getStatus())) return RegistrationView.of(r);
        if (!"WAITING".equals(r.getStatus()))
            throw BusinessException.conflict("REGISTRATION_NOT_CANCELLABLE", "当前预约状态不允许取消");
        var now = time.now();
        if (schedule == null || !schedule.getBookingDeadline().isAfter(now))
            throw BusinessException.conflict("BOOKING_EXPIRED", "已超过取消截止时间");
        if (registrations.cancel(id, patient.getId(), now, normalize(reason)) != 1)
            throw BusinessException.conflict("REGISTRATION_CHANGED", "预约状态已变化，请刷新");
        if (schedules.returnOne(schedule.getId()) != 1)
            throw BusinessException.conflict("STOCK_INCONSISTENT", "号源状态异常，本次取消未生效，请联系管理员");
        return RegistrationView.of(registrations.selectById(id));
    }

    public PageResult<RegistrationView> patientList(Long userId, String status, long page, long pageSize) {
        var patientId = patients.findIdByUserId(userId);
        if (patientId == null) throw BusinessException.missing("患者档案");
        return PageResult.from(registrations.selectPage(new Page<>(page, pageSize), new LambdaQueryWrapper<Registration>()
                .eq(Registration::getPatientId, patientId).eq(status != null, Registration::getStatus, status)
                .orderByDesc(Registration::getCreatedAt, Registration::getId)), RegistrationView::of);
    }

    public Registration patientEntity(Long userId, Long id) {
        var r = registrations.selectById(id);
        var patientId = patients.findIdByUserId(userId);
        if (r == null || patientId == null || !r.getPatientId().equals(patientId))
            throw BusinessException.missing("预约");
        return r;
    }

    public PageResult<RegistrationView> doctorList(Long userId, LocalDate date, String status, long page, long pageSize) {
        return PageResult.from(registrations.selectPage(new Page<>(page, pageSize), new LambdaQueryWrapper<Registration>()
                .eq(Registration::getDoctorId, doctorId(userId)).eq(Registration::getVisitDate, date == null ? time.today() : date)
                .eq(status != null, Registration::getStatus, status).orderByAsc(Registration::getCreatedAt, Registration::getId)), RegistrationView::of);
    }

    public DoctorDetail doctorDetail(Long userId, Long id) {
        var r = doctorEntity(userId, id);
        return new DoctorDetail(RegistrationView.of(r), ProfileView.of(patients.selectById(r.getPatientId())));
    }

    public Registration doctorEntity(Long userId, Long id) {
        var r = registrations.selectById(id);
        if (r == null || !r.getDoctorId().equals(doctorId(userId))) throw BusinessException.missing("预约");
        return r;
    }

    public Long doctorId(Long userId) {
        var id = doctors.findIdByUserId(userId);
        if (id == null) throw BusinessException.missing("医生信息");
        return id;
    }

    private void requireTriage(Long id, Long patientId, Long departmentId, LocalDateTime now) {
        var state = registrations.triageForBooking(id, patientId);
        if (state == null) throw BusinessException.missing("导诊结果");
        if (!"VALID".equals(state.getValidationStatus()) || !"ROUTINE".equals(state.getRiskLevel())
                || state.getDeletedAt() != null || state.getSummaryConfirmedAt() == null
                || !id.equals(state.getLatestResultId()) || !state.getSummaryVersion().equals(state.getCurrentSummaryVersion())
                || !state.getExpiresAt().isAfter(now))
            throw BusinessException.conflict("TRIAGE_RESULT_UNAVAILABLE", "导诊结果已失效或不允许关联普通预约，请重新确认");
        boolean recommended = departmentId.equals(state.getDepartmentId());
        try {
            var alternatives = json.readTree(state.getAlternativeDepartmentIds());
            if (alternatives.isArray()) for (var alternative : alternatives)
                recommended |= departmentId.toString().equals(alternative.asText());
        } catch (java.io.IOException ex) {
            throw BusinessException.conflict("TRIAGE_RESULT_UNAVAILABLE", "导诊结果格式异常，请重新导诊");
        }
        if (!recommended)
            throw BusinessException.conflict("TRIAGE_DEPARTMENT_MISMATCH", "所选科室不在推荐集合中，请清除导诊关联后普通预约");
    }

    private String requestHash(RegistrationRequest request, String symptom) {
        String content = "registration-v1\n" + request.scheduleId() + "\n" + request.triageResultId() + "\n" + (symptom == null ? "" : symptom);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
