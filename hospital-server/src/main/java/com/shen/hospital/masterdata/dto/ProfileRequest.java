package com.shen.hospital.masterdata.dto;

import jakarta.validation.constraints.*;

public record ProfileRequest(
        @NotBlank @Size(max = 64) String name,
        @NotNull @Pattern(regexp = "MALE|FEMALE|UNKNOWN") String gender,
        @Min(0) @Max(120) Integer age,
        @NotNull @Pattern(regexp = "UNKNOWN|NONE|KNOWN") String chronicStatus,
        @Size(max = 10000) String chronicHistory,
        @NotNull @Pattern(regexp = "UNKNOWN|NONE|KNOWN") String allergyStatus,
        @Size(max = 10000) String allergyHistory) {
}
