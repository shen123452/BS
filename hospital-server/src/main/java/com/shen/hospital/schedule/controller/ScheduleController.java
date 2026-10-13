package com.shen.hospital.schedule.controller;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import com.shen.hospital.schedule.dto.*;
import com.shen.hospital.schedule.service.ScheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ScheduleController {
    private final ScheduleService schedules;

    public ScheduleController(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping("/schedules")
    public ApiResponse<PageResult<ScheduleView>> available(@RequestParam(required = false) @Positive Long departmentId,
                                                           @RequestParam(required = false) @Positive Long doctorId,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                           @RequestParam(defaultValue = "1") @Min(1) long page,
                                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(schedules.available(departmentId, doctorId, date, page, pageSize));
    }

    @GetMapping("/schedules/{id}")
    public ApiResponse<ScheduleView> detail(@PathVariable @Positive Long id) {
        return ApiResponse.ok(schedules.visible(id));
    }

    @GetMapping("/admin/schedules")
    public ApiResponse<PageResult<ScheduleView>> managed(@RequestParam(required = false) @Positive Long departmentId,
                                                         @RequestParam(required = false) @Positive Long doctorId,
                                                         @RequestParam(required = false) @Pattern(regexp = "OPEN|CLOSED") String status,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                                         @RequestParam(defaultValue = "1") @Min(1) long page,
                                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(schedules.managed(departmentId, doctorId, status, startDate, endDate, page, pageSize));
    }

    @PostMapping("/admin/schedules")
    public ResponseEntity<ApiResponse<ScheduleView>> create(@Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(schedules.create(request)));
    }

    @PutMapping("/admin/schedules/{id}")
    public ApiResponse<ScheduleView> update(@PathVariable @Positive Long id, @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.ok(schedules.update(id, request));
    }

    @PostMapping("/admin/schedules/{id}/close")
    public ApiResponse<ScheduleView> close(@PathVariable @Positive Long id) {
        return ApiResponse.ok(schedules.close(id));
    }

    @GetMapping("/doctor/schedules")
    public ApiResponse<PageResult<ScheduleView>> own(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(schedules.own(CurrentUser.get().id(), startDate, endDate, page, pageSize));
    }
}
