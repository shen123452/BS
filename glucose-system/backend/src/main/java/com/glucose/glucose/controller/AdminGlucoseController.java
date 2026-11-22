package com.glucose.glucose.controller;

import com.glucose.common.api.PageResult;
import com.glucose.common.api.Result;
import com.glucose.patient.service.PatientAccessService;
import com.glucose.glucose.service.GlucoseService;
import com.glucose.security.LoginContext;
import com.glucose.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

/**
 * 管理端:授权患者的血糖记录查询。医生仅绑定患者;管理员全量但留审计。
 */
@RestController
@RequestMapping("/api/admin/glucose")
@PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
@RequiredArgsConstructor
public class AdminGlucoseController {

    private final GlucoseService glucoseService;
    private final PatientAccessService accessService;
    private final com.glucose.system.service.AuditService auditService;

    @GetMapping
    public Result<PageResult<GlucoseService.RecordVO>> page(
            @RequestParam long patientId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) Integer measureType,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        accessService.requireReadable(LoginContext.current(), patientId);
        if (com.glucose.common.enums.Codes.ROLE_ADMIN.equals(LoginContext.current().role())) {
            auditService.record(LoginContext.currentUserId(), "admin.view.glucose", "patient",
                    String.valueOf(patientId), "管理员查看患者血糖记录", null);
        }
        LocalDate d = date == null || date.isBlank() ? null : LocalDate.parse(date);
        LocalDate start = startDate == null || startDate.isBlank() ? null : LocalDate.parse(startDate);
        LocalDate end = endDate == null || endDate.isBlank() ? null : LocalDate.parse(endDate);
        return Result.ok(glucoseService.pageRecords(patientId, dataSource, measureType, d, start, end, pageNum, pageSize));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam long patientId, @RequestParam String startDate,
                                         @RequestParam String endDate, @RequestParam(required=false) String dataSource) {
        LoginUser user = LoginContext.current();
        accessService.requireReadable(user, patientId);
        LocalDate start = LocalDate.parse(startDate), end = LocalDate.parse(endDate);
        byte[] body = glucoseService.exportCsv(patientId, dataSource, start, end);
        if (com.glucose.common.enums.Codes.ROLE_ADMIN.equals(user.role())) {
            auditService.record(user.userId(), "admin.export.glucose", "patient", String.valueOf(patientId), "管理员导出血糖记录", null);
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("glucose-export.csv").build().toString())
                .body(body);
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> exportXlsx(@RequestParam long patientId, @RequestParam String startDate,
                                             @RequestParam String endDate, @RequestParam(required = false) String dataSource) {
        LoginUser user = LoginContext.current();
        accessService.requireReadable(user, patientId);
        LocalDate start = LocalDate.parse(startDate), end = LocalDate.parse(endDate);
        byte[] body = glucoseService.exportXlsx(patientId, dataSource, start, end);
        if (com.glucose.common.enums.Codes.ROLE_ADMIN.equals(user.role())) {
            auditService.record(user.userId(), "admin.export.glucose.xlsx", "patient", String.valueOf(patientId), "管理员导出血糖 Excel", null);
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("glucose-export.xlsx").build().toString())
                .body(body);
    }
}
