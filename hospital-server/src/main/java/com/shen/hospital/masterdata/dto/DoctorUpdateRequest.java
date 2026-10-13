package com.shen.hospital.masterdata.dto;

import jakarta.validation.constraints.*;

public record DoctorUpdateRequest(@NotNull @Positive Long departmentId, @NotBlank @Size(max = 64) String name,
                                  @Size(max = 40) String title, @Size(max = 10000) String specialty,
                                  @NotNull @Pattern(regexp = "ENABLED|DISABLED") String status) {
}
