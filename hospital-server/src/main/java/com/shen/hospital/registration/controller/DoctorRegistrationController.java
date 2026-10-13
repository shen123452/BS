package com.shen.hospital.registration.controller;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import com.shen.hospital.record.dto.*;
import com.shen.hospital.record.service.MedicalRecordService;
import com.shen.hospital.registration.dto.RegistrationView;
import com.shen.hospital.registration.service.RegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/doctor/registrations")
public class DoctorRegistrationController {
    private final RegistrationService registrations;
    private final MedicalRecordService records;

    public DoctorRegistrationController(RegistrationService registrations, MedicalRecordService records) {
        this.registrations = registrations;
        this.records = records;
    }

    @GetMapping
    public ApiResponse<PageResult<RegistrationView>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @Pattern(regexp = "WAITING|IN_PROGRESS|COMPLETED|CANCELLED") String status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(registrations.doctorList(CurrentUser.get().id(), date, status, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<RegistrationService.DoctorDetail> detail(@PathVariable @Positive Long id) {
        return ApiResponse.ok(registrations.doctorDetail(CurrentUser.get().id(), id));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<RegistrationView> start(@PathVariable @Positive Long id) {
        return ApiResponse.ok(records.start(CurrentUser.get().id(), id));
    }

    @GetMapping("/{id}/medical-record")
    public ApiResponse<RecordView> record(@PathVariable @Positive Long id) {
        return ApiResponse.ok(records.doctorRecord(CurrentUser.get().id(), id));
    }

    @PutMapping("/{id}/medical-record")
    public ApiResponse<RecordView> save(@PathVariable @Positive Long id, @Valid @RequestBody RecordRequest request) {
        return ApiResponse.ok(records.save(CurrentUser.get().id(), id, request));
    }

    public record CompleteRequest(@NotNull @Min(0) @Max(2147483646) Integer version) {
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<RecordView> complete(@PathVariable @Positive Long id, @Valid @RequestBody CompleteRequest request) {
        return ApiResponse.ok(records.complete(CurrentUser.get().id(), id, request.version()));
    }
}
