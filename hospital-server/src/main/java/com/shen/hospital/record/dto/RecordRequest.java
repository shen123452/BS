package com.shen.hospital.record.dto;

import jakarta.validation.constraints.*;

public record RecordRequest(@NotNull @Min(0) @Max(2147483646) Integer version,
                            @Size(max = 10000) String chiefComplaint, @Size(max = 10000) String presentIllness,
                            @Size(max = 10000) String pastHistory, @Size(max = 10000) String physicalExam,
                            @Size(max = 10000) String auxiliaryExam, @Size(max = 10000) String preliminaryDiagnosis,
                            @Size(max = 10000) String treatmentPlan) {
}
