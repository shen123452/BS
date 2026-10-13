package com.shen.hospital.auth.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}", message = "须为3–64位字母、数字、点、下划线或短横线") String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 64) String displayName) {
}
