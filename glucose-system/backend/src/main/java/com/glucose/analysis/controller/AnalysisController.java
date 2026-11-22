package com.glucose.analysis.controller;

import com.glucose.analysis.algorithm.GlucoseStatistics;
import com.glucose.analysis.service.AnalysisService;
import com.glucose.common.api.Result;
import com.glucose.patient.service.PatientAccessService;
import com.glucose.security.LoginContext;
import com.glucose.security.LoginUser;
import com.glucose.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 分析接口:summary/trend/daily;必须明确 dataSource;窗口 [start,end)。
 */
@RestController
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final PatientAccessService accessService;
    private final AuditService auditService;

    private static AnalysisService.AnalysisQuery query(String startDate, String endDate,
                                                       String dataSource, String dataKind) {
        return new AnalysisService.AnalysisQuery(
                startDate == null || startDate.isBlank() ? null : LocalDate.parse(startDate),
                endDate == null || endDate.isBlank() ? null : LocalDate.parse(endDate),
                dataSource, dataKind);
    }

    @GetMapping("/api/app/analysis/summary")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<GlucoseStatistics.Summary> appSummary(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "CGM") String dataSource,
            @RequestParam(required = false) String dataKind) {
        return Result.ok(analysisService.summary(LoginContext.currentUserId(),
                query(startDate, endDate, dataSource, dataKind)));
    }

    @GetMapping("/api/app/analysis/trend")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<GlucoseStatistics.Trend> appTrend(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "CGM") String dataSource,
            @RequestParam(required = false) String dataKind) {
        return Result.ok(analysisService.trend(LoginContext.currentUserId(),
                query(startDate, endDate, dataSource, dataKind)));
    }

    @GetMapping("/api/app/analysis/daily")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<List<GlucoseStatistics.DailyStat>> appDaily(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "CGM") String dataSource,
            @RequestParam(required = false) String dataKind) {
        return Result.ok(analysisService.daily(LoginContext.currentUserId(),
                query(startDate, endDate, dataSource, dataKind)));
    }

    @GetMapping("/api/app/analysis/agp")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<com.glucose.analysis.algorithm.AgpStatistics.Profile> appAgp(
            @RequestParam(required=false) String startDate, @RequestParam(required=false) String endDate,
            @RequestParam(defaultValue="CGM") String dataSource, @RequestParam(required=false) String dataKind) {
        return Result.ok(analysisService.agp(LoginContext.currentUserId(), query(startDate,endDate,dataSource,dataKind)));
    }

    @GetMapping("/api/admin/analysis/patient/{patientId}/agp")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<com.glucose.analysis.algorithm.AgpStatistics.Profile> adminAgp(
            @PathVariable long patientId, @RequestParam(required=false) String startDate,
            @RequestParam(required=false) String endDate, @RequestParam(defaultValue="CGM") String dataSource,
            @RequestParam(required=false) String dataKind) {
        var user = LoginContext.current();
        accessService.requireReadable(user,patientId);
        if ("ADMIN".equals(user.role())) auditService.record(user.userId(),"admin.view.agp","patient",String.valueOf(patientId),"管理员查看日间血糖分布",null);
        return Result.ok(analysisService.agp(patientId,query(startDate,endDate,dataSource,dataKind)));
    }

    @GetMapping("/api/admin/analysis/patient/{patientId}")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<GlucoseStatistics.Summary> adminSummary(
            @PathVariable long patientId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "CGM") String dataSource,
            @RequestParam(required = false) String dataKind) {
        LoginUser user = LoginContext.current();
        accessService.requireReadable(user, patientId);
        if ("ADMIN".equals(user.role())) {
            auditService.record(user.userId(), "admin.view.analysis", "patient",
                    String.valueOf(patientId), "管理员查看患者分析指标", null);
        }
        return Result.ok(analysisService.summary(patientId, query(startDate, endDate, dataSource, dataKind)));
    }

    @GetMapping("/api/admin/analysis/patient/{patientId}/trend")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<GlucoseStatistics.Trend> adminTrend(
            @PathVariable long patientId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "CGM") String dataSource,
            @RequestParam(required = false) String dataKind) {
        accessService.requireReadable(LoginContext.current(), patientId);
        return Result.ok(analysisService.trend(patientId, query(startDate, endDate, dataSource, dataKind)));
    }

    @GetMapping("/api/admin/analysis/patient/{patientId}/daily")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<List<GlucoseStatistics.DailyStat>> adminDaily(
            @PathVariable long patientId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "CGM") String dataSource,
            @RequestParam(required = false) String dataKind) {
        LoginUser user = LoginContext.current();
        accessService.requireReadable(user, patientId);
        return Result.ok(analysisService.daily(patientId, query(startDate, endDate, dataSource, dataKind)));
    }
}
