package com.shen.hospital.masterdata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shen.hospital.auth.domain.*;
import com.shen.hospital.auth.dto.UserView;
import com.shen.hospital.auth.mapper.UserMapper;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.domain.*;
import com.shen.hospital.masterdata.dto.*;
import com.shen.hospital.masterdata.mapper.*;
import com.shen.hospital.registration.mapper.RegistrationMapper;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class MasterdataService {
    private final DepartmentMapper departments;
    private final DoctorMapper doctors;
    private final UserMapper users;
    private final AuthService auth;
    private final RegistrationMapper registrations;
    private final BusinessTime time;

    public MasterdataService(DepartmentMapper departments, DoctorMapper doctors, UserMapper users, AuthService auth,
                             RegistrationMapper registrations, BusinessTime time) {
        this.departments = departments;
        this.doctors = doctors;
        this.users = users;
        this.auth = auth;
        this.registrations = registrations;
        this.time = time;
    }

    public List<DepartmentView> enabledDepartments() {
        return departments.selectList(new LambdaQueryWrapper<Department>().eq(Department::getStatus, "ENABLED")
                .orderByAsc(Department::getId)).stream().map(DepartmentView::of).toList();
    }

    public PageResult<DepartmentView> departments(long page, long pageSize) {
        return PageResult.from(departments.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Department>().orderByAsc(Department::getId)), DepartmentView::of);
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public DepartmentView createDepartment(DepartmentRequest request) {
        var d = new Department();
        apply(d, request);
        departments.insert(d);
        return DepartmentView.of(d);
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public DepartmentView updateDepartment(Long id, DepartmentRequest request) {
        var d = departments.lockById(id);
        if (d == null) throw missing("科室");
        if ("DISABLED".equals(request.status()) && departments.countActiveDependencies(id) > 0)
            throw new BusinessException(HttpStatus.CONFLICT, "DEPARTMENT_IN_USE", "请先处理启用医生或未来开放排班");
        apply(d, request);
        departments.update(null, new LambdaUpdateWrapper<Department>().eq(Department::getId, id)
                .set(Department::getCode, d.getCode()).set(Department::getName, d.getName())
                .set(Department::getDescription, d.getDescription()).set(Department::getLocation, d.getLocation())
                .set(Department::getIsEmergency, d.getIsEmergency()).set(Department::getStatus, d.getStatus()));
        return DepartmentView.of(d);
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public DoctorView createDoctor(DoctorRequest request) {
        var department = departments.lockById(request.departmentId());
        if (department == null || !"ENABLED".equals(department.getStatus()))
            throw new BusinessException(HttpStatus.CONFLICT, "DEPARTMENT_UNAVAILABLE", "科室不存在或未启用");
        var user = auth.createUser(request.username(), request.password(), request.name(), Role.DOCTOR);
        var doctor = new Doctor();
        doctor.setUserId(user.getId());
        doctor.setDepartmentId(department.getId());
        doctor.setName(request.name().strip());
        doctor.setTitle(request.title());
        doctor.setSpecialty(request.specialty());
        doctor.setStatus("ENABLED");
        doctors.insert(doctor);
        return DoctorView.of(doctor);
    }

    public PageResult<DoctorView> doctors(long page, long pageSize) {
        return PageResult.from(doctors.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Doctor>().orderByAsc(Doctor::getId)), DoctorView::of);
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public DoctorView updateDoctor(Long id, DoctorUpdateRequest r) {
        var initial = doctors.selectById(id);
        if (initial == null) throw missing("医生");
        // 转科同时涉及两个科室，按ID排序锁定，避免两个医生互换科室时锁反转。
        java.util.stream.Stream.of(initial.getDepartmentId(), r.departmentId()).distinct().sorted().forEach(depId -> {
            if (departments.lockById(depId) == null) throw missing("科室");
        });
        var doctor = doctors.lockById(id);
        if (doctor == null || !doctor.getDepartmentId().equals(initial.getDepartmentId()))
            throw BusinessException.conflict("DOCTOR_CHANGED", "医生信息已变化，请刷新后重试");
        var user = users.lockById(doctor.getUserId());
        if (user == null || !Role.DOCTOR.name().equals(user.getRole())) throw missing("医生账号");
        var target = departments.lockById(r.departmentId());
        boolean transferring = !doctor.getDepartmentId().equals(r.departmentId());
        if ((transferring || "ENABLED".equals(r.status())) && !"ENABLED".equals(target.getStatus()))
            throw BusinessException.conflict("DEPARTMENT_UNAVAILABLE", "目标科室未启用");
        if (transferring && (registrations.futureActiveForDoctor(id, time.today()) != null || doctors.futureOpenSchedule(id, time.today()) != null))
            throw BusinessException.conflict("DOCTOR_IN_USE", "请先处理未来有效预约并关闭未来开放排班，再修改所属科室");
        doctors.update(null, new LambdaUpdateWrapper<Doctor>().eq(Doctor::getId, id)
                .set(Doctor::getDepartmentId, r.departmentId()).set(Doctor::getName, r.name().strip())
                .set(Doctor::getTitle, r.title()).set(Doctor::getSpecialty, r.specialty()).set(Doctor::getStatus, r.status()));
        users.update(null, new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, doctor.getUserId()).set(SysUser::getDisplayName, r.name().strip()));
        return DoctorView.of(doctors.selectById(id));
    }

    public DoctorView ownDoctor(Long userId) {
        var doctor = doctors.selectOne(new LambdaQueryWrapper<Doctor>().eq(Doctor::getUserId, userId));
        if (doctor == null) throw missing("医生信息");
        return DoctorView.of(doctor);
    }

    public PageResult<UserView> users(long page, long pageSize) {
        return PageResult.from(users.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId)), u -> UserView.of(u, null, null));
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public void status(Long userId, String status, Long currentId) {
        if (userId.equals(currentId) && "DISABLED".equals(status))
            throw new BusinessException(HttpStatus.CONFLICT, "CANNOT_DISABLE_SELF", "不能禁用当前登录账号");
        if (users.selectById(userId) == null) throw missing("账号");
        users.update(null, new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, userId).set(SysUser::getStatus, status));
    }

    private void apply(Department d, DepartmentRequest r) {
        d.setCode(r.code());
        d.setName(r.name().strip());
        d.setDescription(r.description());
        d.setLocation(r.location());
        d.setIsEmergency(r.isEmergency() ? 1 : 0);
        d.setStatus(r.status());
    }

    private BusinessException missing(String resource) {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", resource + "不存在");
    }
}
