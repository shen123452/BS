package com.shen.hospital.masterdata.controller;

import com.shen.hospital.auth.dto.UserView;
import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.dto.*;
import com.shen.hospital.masterdata.service.MasterdataService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final MasterdataService data;

    public AdminController(MasterdataService data) {
        this.data = data;
    }

    @GetMapping("/departments")
    public ApiResponse<PageResult<DepartmentView>> departments(@RequestParam(defaultValue = "1") @Min(1) long page,
                                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(data.departments(page, pageSize));
    }

    @PostMapping("/departments")
    public ResponseEntity<ApiResponse<DepartmentView>> createDepartment(@Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(data.createDepartment(request)));
    }

    @PutMapping("/departments/{id}")
    public ApiResponse<DepartmentView> updateDepartment(@PathVariable @Min(1) Long id, @Valid @RequestBody DepartmentRequest request) {
        return ApiResponse.ok(data.updateDepartment(id, request));
    }

    @GetMapping("/doctors")
    public ApiResponse<PageResult<DoctorView>> doctors(@RequestParam(defaultValue = "1") @Min(1) long page,
                                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(data.doctors(page, pageSize));
    }

    @PostMapping("/doctors")
    public ResponseEntity<ApiResponse<DoctorView>> createDoctor(@Valid @RequestBody DoctorRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(data.createDoctor(request)));
    }

    @PutMapping("/doctors/{id}")
    public ApiResponse<DoctorView> updateDoctor(@PathVariable @Min(1) Long id, @Valid @RequestBody DoctorUpdateRequest request) {
        return ApiResponse.ok(data.updateDoctor(id, request));
    }

    @GetMapping("/users")
    public ApiResponse<PageResult<UserView>> users(@RequestParam(defaultValue = "1") @Min(1) long page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(data.users(page, pageSize));
    }

    public record StatusRequest(@NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status) {
    }

    @RequestMapping(path = "/users/{id}/status", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ApiResponse<Void> status(@PathVariable @Min(1) Long id, @Valid @RequestBody StatusRequest request) {
        data.status(id, request.status(), CurrentUser.get().id());
        return ApiResponse.ok(null);
    }
}
