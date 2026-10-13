package com.shen.hospital.masterdata.controller;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.ApiResponse;
import com.shen.hospital.masterdata.dto.*;
import com.shen.hospital.masterdata.service.PatientService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patient/profile")
public class PatientController {
    private final PatientService patients;

    public PatientController(PatientService patients) {
        this.patients = patients;
    }

    @GetMapping
    public ApiResponse<ProfileView> get() {
        return ApiResponse.ok(patients.get(CurrentUser.get().id()));
    }

    @PutMapping
    public ApiResponse<ProfileView> update(@Valid @RequestBody ProfileRequest request) {
        return ApiResponse.ok(patients.update(CurrentUser.get().id(), request));
    }
}
