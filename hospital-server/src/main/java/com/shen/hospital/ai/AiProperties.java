package com.shen.hospital.ai;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("hospital.ai")
@Validated
public record AiProperties(@NotBlank String baseUrl, String apiKey, @NotBlank String chatModel,
                           @NotBlank String embeddingModel, @Min(1) @Max(16000) int embeddingDimension,
                           @Min(1) @Max(120) int timeoutSeconds) {
}
