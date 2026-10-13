package com.shen.hospital.registration.dto;

import jakarta.validation.constraints.*;

public record RegistrationRequest(
        @NotNull @Positive Long scheduleId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String clientRequestId,
        @Positive Long triageResultId,
        @Size(max = 10000) String symptomDescription) {
}
