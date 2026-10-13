package com.shen.hospital.schedule.dto;

import com.shen.hospital.schedule.domain.DoctorSchedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ScheduleView(String id, String doctorId, String departmentId, String doctorName,
                           String departmentName, LocalDate visitDate, String timeSlot, LocalDateTime bookingDeadline,
                           int totalCount, int remainingCount, BigDecimal fee, String status, boolean bookable) {
    public static ScheduleView of(DoctorSchedule s) {
        return new ScheduleView(s.getId().toString(), s.getDoctorId().toString(), s.getDepartmentId().toString(),
                s.getDoctorName(), s.getDepartmentName(), s.getVisitDate(), s.getTimeSlot(), s.getBookingDeadline(),
                s.getTotalCount(), s.getRemainingCount(), s.getFee(), s.getStatus(), Boolean.TRUE.equals(s.getBookable()));
    }
}
