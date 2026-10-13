package com.shen.hospital.schedule.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ScheduleRequest(
        @NotNull @Positive Long doctorId,
        @NotNull LocalDate visitDate,
        @NotNull @Pattern(regexp = "AM|PM") String timeSlot,
        LocalDateTime bookingDeadline,
        @NotNull @Min(1) @Max(100000) Integer totalCount,
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal fee,
        @Pattern(regexp = "OPEN|CLOSED") String status) {
}
