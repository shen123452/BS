package com.shen.hospital.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String clientMessageId,
        @NotBlank @Size(max = 10000) String content) {
}
