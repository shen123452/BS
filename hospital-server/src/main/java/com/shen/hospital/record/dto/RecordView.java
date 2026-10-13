package com.shen.hospital.record.dto;

import com.shen.hospital.record.domain.MedicalRecord;

import java.time.LocalDateTime;

public record RecordView(String id, String registrationId, String doctorId, String chiefComplaint,
                         String presentIllness, String pastHistory, String physicalExam, String auxiliaryExam,
                         String preliminaryDiagnosis, String treatmentPlan, String status, int version,
                         LocalDateTime submittedAt) {
    public static RecordView of(MedicalRecord r) {
        return new RecordView(r.getId().toString(), r.getRegistrationId().toString(), r.getDoctorId().toString(),
                r.getChiefComplaint(), r.getPresentIllness(), r.getPastHistory(), r.getPhysicalExam(), r.getAuxiliaryExam(),
                r.getPreliminaryDiagnosis(), r.getTreatmentPlan(), r.getStatus(), r.getVersion(), r.getSubmittedAt());
    }
}
