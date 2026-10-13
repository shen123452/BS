package com.shen.hospital.masterdata.dto;

import com.shen.hospital.masterdata.domain.PatientProfile;

public record ProfileView(String id, String name, String gender, Integer age, String chronicStatus,
                          String chronicHistory, String allergyStatus, String allergyHistory) {
    public static ProfileView of(PatientProfile p) {
        return new ProfileView(p.getId().toString(), p.getName(), p.getGender(), p.getAge(),
                p.getChronicStatus(), p.getChronicHistory(), p.getAllergyStatus(), p.getAllergyHistory());
    }
}
