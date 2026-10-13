package com.shen.hospital.schedule.service;

import com.shen.hospital.auth.mapper.UserMapper;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.masterdata.domain.Department;
import com.shen.hospital.masterdata.domain.Doctor;
import com.shen.hospital.masterdata.mapper.DepartmentMapper;
import com.shen.hospital.masterdata.mapper.DoctorMapper;
import org.springframework.stereotype.Service;

/**
 * 调用方须在MySQL事务内使用；统一按科室、医生、账号顺序锁定。
 */
@Service
public class ScheduleParticipants {
    private final DepartmentMapper departments;
    private final DoctorMapper doctors;
    private final UserMapper users;

    public ScheduleParticipants(DepartmentMapper departments, DoctorMapper doctors, UserMapper users) {
        this.departments = departments;
        this.doctors = doctors;
        this.users = users;
    }

    public record Participants(Doctor doctor, Department department) {
    }

    public Participants lockEnabled(Long doctorId) {
        var initial = doctors.selectById(doctorId);
        if (initial == null) throw BusinessException.missing("医生");
        var department = departments.lockById(initial.getDepartmentId());
        var doctor = doctors.lockById(doctorId);
        if (doctor == null || !doctor.getDepartmentId().equals(initial.getDepartmentId()))
            throw BusinessException.conflict("DOCTOR_CHANGED", "医生所属科室已变化，请刷新后重试");
        var user = users.lockById(doctor.getUserId());
        if (department == null || !"ENABLED".equals(department.getStatus()) || !"ENABLED".equals(doctor.getStatus())
                || user == null || !"ACTIVE".equals(user.getStatus()) || !"DOCTOR".equals(user.getRole()))
            throw BusinessException.conflict("DOCTOR_UNAVAILABLE", "医生、科室或医生账号未启用");
        return new Participants(doctor, department);
    }
}
