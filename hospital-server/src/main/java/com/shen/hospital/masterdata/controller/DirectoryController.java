package com.shen.hospital.masterdata.controller;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.ApiResponse;
import com.shen.hospital.masterdata.dto.*;
import com.shen.hospital.masterdata.service.MasterdataService;

import java.util.List;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DirectoryController {
    private final MasterdataService data;

    public DirectoryController(MasterdataService data) {
        this.data = data;
    }

    @GetMapping("/departments")
    public ApiResponse<List<DepartmentView>> departments() {
        return ApiResponse.ok(data.enabledDepartments());
    }

    @GetMapping("/doctor/profile")
    public ApiResponse<DoctorView> own() {
        return ApiResponse.ok(data.ownDoctor(CurrentUser.get().id()));
    }
}
