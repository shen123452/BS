package com.shen.hospital.masterdata.dto;

import com.shen.hospital.masterdata.domain.Department;

public record DepartmentView(String id, String code, String name, String description, String location,
                             boolean isEmergency, String status) {
    public static DepartmentView of(Department d) {
        return new DepartmentView(d.getId().toString(), d.getCode(), d.getName(), d.getDescription(),
                d.getLocation(), Integer.valueOf(1).equals(d.getIsEmergency()), d.getStatus());
    }
}
