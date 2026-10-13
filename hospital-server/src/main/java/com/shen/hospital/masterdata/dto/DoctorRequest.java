package com.shen.hospital.masterdata.dto;

import jakarta.validation.constraints.*;

public record DoctorRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull @Min(1) Long departmentId,
        @NotBlank @Size(max = 64) String name,
        @Size(max = 40) String title,
        @Size(max = 10000) String specialty) {
}
