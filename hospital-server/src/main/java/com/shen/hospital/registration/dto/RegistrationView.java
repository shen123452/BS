package com.shen.hospital.registration.dto;

import com.shen.hospital.registration.domain.Registration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record RegistrationView(String id, String registrationNo, String patientId, String scheduleId,
                               String doctorId, String departmentId, String triageResultId, LocalDate visitDate,
                               String timeSlot,
                               String patientName, String doctorName, String departmentName, BigDecimal fee,
                               String symptomDescription, String status, LocalDateTime startedAt,
                               LocalDateTime completedAt,
                               LocalDateTime cancelledAt, String cancelReason, LocalDateTime createdAt) {
    public static RegistrationView of(Registration r) {
        return new RegistrationView(r.getId().toString(), r.getRegistrationNo(), r.getPatientId().toString(),
                r.getScheduleId().toString(), r.getDoctorId().toString(), r.getDepartmentId().toString(),
                r.getTriageResultId() == null ? null : r.getTriageResultId().toString(), r.getVisitDate(), r.getTimeSlot(),
                r.getPatientNameSnapshot(), r.getDoctorNameSnapshot(), r.getDepartmentNameSnapshot(), r.getFeeSnapshot(),
                r.getSymptomDescription(), r.getStatus(), r.getStartedAt(), r.getCompletedAt(), r.getCancelledAt(),
                r.getCancelReason(), r.getCreatedAt());
    }
}
