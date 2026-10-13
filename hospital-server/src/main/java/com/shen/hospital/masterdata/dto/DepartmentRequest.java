package com.shen.hospital.masterdata.dto;

import jakarta.validation.constraints.*;

public record DepartmentRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String code,
        @NotBlank @Size(max = 80) String name,
        @Size(max = 10000) String description,
        @Size(max = 120) String location,
        @NotNull Boolean isEmergency,
        @NotNull @Pattern(regexp = "ENABLED|DISABLED") String status) {
}
