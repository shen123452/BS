package com.shen.hospital.registration.controller;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import com.shen.hospital.record.dto.RecordView;
import com.shen.hospital.record.service.MedicalRecordService;
import com.shen.hospital.registration.dto.*;
import com.shen.hospital.registration.service.RegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patient/registrations")
public class PatientRegistrationController {
    private final RegistrationService registrations;
    private final MedicalRecordService records;

    public PatientRegistrationController(RegistrationService registrations, MedicalRecordService records) {
        this.registrations = registrations;
        this.records = records;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RegistrationView>> book(@Valid @RequestBody RegistrationRequest request) {
        var result = registrations.book(CurrentUser.get().id(), request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(ApiResponse.ok(result.registration()));
    }

    @GetMapping
    public ApiResponse<PageResult<RegistrationView>> list(
            @RequestParam(required = false) @Pattern(regexp = "WAITING|IN_PROGRESS|COMPLETED|CANCELLED") String status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(registrations.patientList(CurrentUser.get().id(), status, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<RegistrationView> detail(@PathVariable @Positive Long id) {
        return ApiResponse.ok(RegistrationView.of(registrations.patientEntity(CurrentUser.get().id(), id)));
    }

    public record CancelRequest(@Size(max = 255) String reason) {
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<RegistrationView> cancel(@PathVariable @Positive Long id,
                                                @Valid @RequestBody(required = false) CancelRequest request) {
        return ApiResponse.ok(registrations.cancel(CurrentUser.get().id(), id, request == null ? null : request.reason()));
    }

    @GetMapping("/{id}/medical-record")
    public ApiResponse<RecordView> record(@PathVariable @Positive Long id) {
        return ApiResponse.ok(records.patientRecord(CurrentUser.get().id(), id));
    }
}
