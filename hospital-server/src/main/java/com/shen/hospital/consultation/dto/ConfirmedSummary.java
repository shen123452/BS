package com.shen.hospital.consultation.dto;

import com.shen.hospital.common.BusinessException;
import java.util.*;
import jakarta.validation.constraints.*;

public record ConfirmedSummary(
        @NotBlank @Size(max=1000) String chiefComplaint,
        @NotBlank @Size(max=100) String duration,
        @NotBlank @Size(max=100) String severity,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=100) String> associatedSymptoms,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=100) String> pertinentNegatives,
        @NotNull Map<String,String> riskAnswers) {
    public static final Map<String,String> RISKS = Map.of(
            "chestPain", "胸痛", "severeBreathing", "严重呼吸困难", "unconscious", "意识不清",
            "majorBleeding", "大出血", "paralysis", "突发偏瘫");
    public void validateRisks() {
        if (riskAnswers == null || !riskAnswers.keySet().equals(RISKS.keySet())
                || riskAnswers.values().stream().anyMatch(v -> !Set.of("YES","NO","UNKNOWN").contains(Objects.toString(v, ""))))
            throw BusinessException.invalid("请逐项核对危险信号，选择有、无或不确定");
    }
    public boolean emergency() { return riskAnswers.values().contains("YES"); }
    public boolean uncertainRisk() { return riskAnswers.values().contains("UNKNOWN"); }
}
