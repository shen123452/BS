package com.shen.hospital.schedule.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.mapper.DoctorMapper;
import com.shen.hospital.registration.mapper.RegistrationMapper;
import com.shen.hospital.schedule.domain.DoctorSchedule;
import com.shen.hospital.schedule.dto.*;
import com.shen.hospital.schedule.mapper.ScheduleMapper;

import java.time.*;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ScheduleService {
    private final ScheduleMapper schedules;
    private final RegistrationMapper registrations;
    private final DoctorMapper doctors;
    private final ScheduleParticipants participants;
    private final BusinessTime time;

    public ScheduleService(ScheduleMapper schedules, RegistrationMapper registrations, DoctorMapper doctors,
                           ScheduleParticipants participants, BusinessTime time) {
        this.schedules = schedules;
        this.registrations = registrations;
        this.doctors = doctors;
        this.participants = participants;
        this.time = time;
    }

    public PageResult<ScheduleView> available(Long departmentId, Long doctorId, LocalDate date, long page, long pageSize) {
        var now = time.now();
        var start = date == null ? now.toLocalDate() : date;
        var end = date == null ? start.plusDays(6) : date;
        if (start.isBefore(now.toLocalDate()) || end.isAfter(now.toLocalDate().plusDays(6)))
            throw BusinessException.invalid("仅可查询今天起七天内的场次");
        return PageResult.from(schedules.available(new Page<>(page, pageSize), departmentId, doctorId, start, end, now), ScheduleView::of);
    }

    public ScheduleView visible(Long id) {
        var now = time.now();
        var s = schedules.visibleById(id, now.toLocalDate(), now.toLocalDate().plusDays(6), now);
        if (s == null) throw BusinessException.missing("排班");
        return ScheduleView.of(s);
    }

    public PageResult<ScheduleView> availableRange(Long departmentId, LocalDate start, LocalDate end, long page, long size) {
        var now = time.now();
        if (start == null || end == null || start.isBefore(now.toLocalDate()) || end.isBefore(start)
                || end.isAfter(now.toLocalDate().plusDays(6))) throw BusinessException.invalid("仅可查询今天起七天内的场次");
        return PageResult.from(schedules.available(new Page<>(page, size), departmentId, null, start, end, now), ScheduleView::of);
    }

    public PageResult<ScheduleView> managed(Long departmentId, Long doctorId, String status,
                                            LocalDate startDate, LocalDate endDate, long page, long pageSize) {
        var start = startDate == null ? time.today() : startDate;
        var end = endDate == null ? start.plusDays(6) : endDate;
        requireRange(start, end);
        return PageResult.from(schedules.managed(new Page<>(page, pageSize), departmentId, doctorId, status,
                start, end, time.now()), ScheduleView::of);
    }

    public PageResult<ScheduleView> own(Long userId, LocalDate start, LocalDate end, long page, long pageSize) {
        var doctorId = doctors.findIdByUserId(userId);
        if (doctorId == null) throw BusinessException.missing("医生信息");
        return managed(null, doctorId, null, start, end, page, pageSize);
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public ScheduleView create(ScheduleRequest r) {
        var actor = participants.lockEnabled(r.doctorId());
        var s = new DoctorSchedule();
        s.setDoctorId(actor.doctor().getId());
        s.setDepartmentId(actor.department().getId());
        s.setVisitDate(r.visitDate());
        s.setTimeSlot(r.timeSlot());
        s.setBookingDeadline(deadline(r));
        s.setTotalCount(r.totalCount());
        s.setRemainingCount(r.totalCount());
        s.setFee(r.fee());
        s.setStatus(r.status() == null ? "OPEN" : r.status());
        validate(s, true);
        try {
            schedules.insert(s);
        } catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        return view(s.getId());
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public ScheduleView update(Long id, ScheduleRequest r) {
        var actor = participants.lockEnabled(r.doctorId());
        var s = schedules.lockById(id);
        if (s == null) throw BusinessException.missing("排班");
        boolean changed = !s.getDoctorId().equals(r.doctorId()) || !s.getDepartmentId().equals(actor.department().getId())
                || !s.getVisitDate().equals(r.visitDate()) || !s.getTimeSlot().equals(r.timeSlot());
        if (changed && registrations.anyHistory(id) != null)
            throw BusinessException.conflict("SCHEDULE_HAS_HISTORY", "已有历史预约，不能修改医生、科室、日期或时段");
        int used = s.getTotalCount() - s.getRemainingCount();
        if (r.totalCount() < used)
            throw BusinessException.conflict("SCHEDULE_CAPACITY_TOO_SMALL", "总号源不能小于已占用号源");
        String status = r.status() == null ? s.getStatus() : r.status();
        if ("CLOSED".equals(status)) requireClosable(id);
        s.setDoctorId(r.doctorId());
        s.setDepartmentId(actor.department().getId());
        s.setVisitDate(r.visitDate());
        s.setTimeSlot(r.timeSlot());
        s.setBookingDeadline(deadline(r));
        s.setTotalCount(r.totalCount());
        s.setRemainingCount(r.totalCount() - used);
        s.setFee(r.fee());
        s.setStatus(status);
        validate(s, changed);
        try {
            schedules.updateById(s);
        } catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        return view(id);
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public ScheduleView close(Long id) {
        var s = schedules.lockById(id);
        if (s == null) throw BusinessException.missing("排班");
        requireClosable(id);
        schedules.update(null, new LambdaUpdateWrapper<DoctorSchedule>().eq(DoctorSchedule::getId, id)
                .set(DoctorSchedule::getStatus, "CLOSED"));
        return view(id);
    }

    private ScheduleView view(Long id) {
        return ScheduleView.of(schedules.viewById(id, time.now()));
    }

    private void requireClosable(Long id) {
        if (registrations.activeOnSchedule(id) != null)
            throw BusinessException.conflict("SCHEDULE_IN_USE", "仍有待就诊或接诊中的预约，不能关闭场次");
    }

    private LocalDateTime deadline(ScheduleRequest r) {
        return (r.bookingDeadline() == null ? startsAt(r.visitDate(), r.timeSlot()).minusMinutes(30)
                : r.bookingDeadline()).truncatedTo(ChronoUnit.MILLIS);
    }

    private LocalDateTime startsAt(LocalDate date, String slot) {
        return date.atTime("AM".equals(slot) ? LocalTime.of(8, 0) : LocalTime.of(14, 0));
    }

    private void validate(DoctorSchedule s, boolean newIdentity) {
        var now = time.now();
        if (newIdentity && s.getVisitDate().isBefore(now.toLocalDate()))
            throw BusinessException.invalid("不能新建或移动至过去日期");
        if (s.getBookingDeadline().isAfter(startsAt(s.getVisitDate(), s.getTimeSlot())))
            throw BusinessException.invalid("预约截止时间不能晚于场次开始时间（上午08:00、下午14:00）");
        if ("OPEN".equals(s.getStatus()) && !s.getBookingDeadline().isAfter(now))
            throw BusinessException.conflict("BOOKING_EXPIRED", "开放场次的预约截止时间必须晚于当前时间");
    }

    private void requireRange(LocalDate start, LocalDate end) {
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 92)
            throw BusinessException.invalid("日期范围不能倒置，且一次最多查询93天");
    }

    private BusinessException duplicate() {
        return BusinessException.conflict("DUPLICATE_SCHEDULE", "同一医生、日期和时段已存在排班");
    }
}
