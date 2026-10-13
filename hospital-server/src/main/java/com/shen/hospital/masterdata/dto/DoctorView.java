package com.shen.hospital.masterdata.dto;

import com.shen.hospital.masterdata.domain.Doctor;

public record DoctorView(String id, String userId, String departmentId, String name, String title, String specialty,
                         String status) {
    public static DoctorView of(Doctor d) {
        return new DoctorView(d.getId().toString(), d.getUserId().toString(), d.getDepartmentId().toString(),
                d.getName(), d.getTitle(), d.getSpecialty(), d.getStatus());
    }
}
