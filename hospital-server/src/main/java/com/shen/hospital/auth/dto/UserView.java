package com.shen.hospital.auth.dto;

import com.shen.hospital.auth.domain.SysUser;

public record UserView(String id, String username, String displayName, String role, String status,
                       String patientId, String doctorId) {
    public static UserView of(SysUser user, Long patientId, Long doctorId) {
        return new UserView(user.getId().toString(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.getStatus(), patientId == null ? null : patientId.toString(), doctorId == null ? null : doctorId.toString());
    }
}
