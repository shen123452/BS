package com.glucose.medication.controller;

import com.glucose.common.api.Result;
import com.glucose.medication.service.MedicationService;
import com.glucose.security.LoginContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/app/medication")
@PreAuthorize("hasRole('PATIENT')")
@RequiredArgsConstructor
public class AppMedicationController {
    private final MedicationService service;
    public record StopReq(@NotNull @Positive Long version) {}

    @GetMapping("/plans") public Result<List<MedicationService.PlanVO>> plans() {
        return Result.ok(service.listPlans(LoginContext.currentUserId()));
    }
    @PostMapping("/plans") public Result<MedicationService.PlanVO> create(@Valid @RequestBody MedicationService.PlanReq req) {
        return Result.ok(service.create(LoginContext.currentUserId(), req));
    }
    @PutMapping("/plans/{id}/stop") public Result<MedicationService.PlanVO> stop(@PathVariable long id, @Valid @RequestBody StopReq req) {
        return Result.ok(service.stop(LoginContext.currentUserId(), id, req.version()));
    }
    @GetMapping public Result<MedicationService.DayVO> day(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(service.day(LoginContext.currentUserId(), date));
    }
    @PutMapping("/{id}/taken") public Result<MedicationService.LogVO> status(@PathVariable long id, @Valid @RequestBody MedicationService.StatusReq req) {
        return Result.ok(service.setStatus(LoginContext.currentUserId(), id, req));
    }
}
