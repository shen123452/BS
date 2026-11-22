package com.glucose.glucose.controller;

import com.glucose.alert.mapper.AlertRecordMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.PageResult;
import com.glucose.common.api.Result;
import com.glucose.common.enums.Codes;
import com.glucose.glucose.service.CgmImportService;
import com.glucose.glucose.service.GlucoseService;
import com.glucose.security.LoginContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 患者端血糖接口:手工 SMBG、分页查询、最新概览、CGM 导入。
 */
@RestController
@RequestMapping("/api/app/glucose")
@PreAuthorize("hasRole('PATIENT')")
@RequiredArgsConstructor
@Validated
public class AppGlucoseController {

    private final GlucoseService glucoseService;
    private final CgmImportService cgmImportService;
    private final AlertRecordMapper alertRecordMapper;

    public record AddReq(String measuredAt, java.math.BigDecimal glucoseMmol,
                         Integer measureType, @Size(max = 200) String note) {
    }

    public record UpdateReq(@NotBlank String measuredAt, java.math.BigDecimal glucoseMmol,
                            Integer measureType, @Size(max = 200) String note) {
    }

    @PostMapping
    public Result<GlucoseService.RecordVO> add(@RequestBody @Validated AddReq req) {
        return Result.ok(glucoseService.addManual(LoginContext.current(),
                new GlucoseService.ManualReq(req.measuredAt(), req.glucoseMmol(), req.measureType(), req.note())));
    }

    @GetMapping
    public Result<PageResult<GlucoseService.RecordVO>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) Integer measureType,
            @RequestParam(required = false) String date) {
        LocalDate d = date == null || date.isBlank() ? null : LocalDate.parse(date);
        return Result.ok(glucoseService.pageRecords(LoginContext.currentUserId(), dataSource, measureType,
                d, pageNum, pageSize));
    }

    @PutMapping("/{id}")
    public Result<GlucoseService.RecordVO> update(@PathVariable long id, @RequestBody @Validated UpdateReq req) {
        return Result.ok(glucoseService.updateManual(LoginContext.current(), id,
                new GlucoseService.ManualReq(req.measuredAt(), req.glucoseMmol(), req.measureType(), req.note())));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        glucoseService.deleteManual(LoginContext.current(), id);
        return Result.ok();
    }

    @GetMapping("/latest")
    public Result<Map<String, Object>> latest() {
        Map<String, Object> overview = glucoseService.latestOverview(LoginContext.current());
        Long unread = alertRecordMapper.selectCount(Wrappers.<com.glucose.alert.entity.AlertRecord>lambdaQuery()
                .eq(com.glucose.alert.entity.AlertRecord::getPatientId, LoginContext.currentUserId())
                .apply("notice_version > patient_seen_notice_version"));
        overview.put("unreadAlerts", unread == null ? 0 : unread);
        return Result.ok(overview);
    }

    /* ================= CGM 导入 ================= */

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=cgm-template.csv")
                .body(cgmImportService.template());
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<CgmImportService.PreviewVO> preview(@RequestParam("file") MultipartFile file,
                                                      @RequestParam("requestId") @NotBlank String requestId,
                                                      @RequestParam(value = "metaConfirmed", defaultValue = "false")
                                                      boolean metaConfirmed) {
        if (!metaConfirmed) {
            throw new com.glucose.common.exception.BusinessException(
                    com.glucose.common.api.ErrorCode.PARAM_ERROR,
                    "请先确认单位为 mmol/L、时区 Asia/Shanghai、5 分钟采样间隔");
        }
        return Result.ok(cgmImportService.preview(LoginContext.current(), file, requestId));
    }

    @GetMapping("/import/batches")
    public Result<List<CgmImportService.BatchVO>> batches() {
        return Result.ok(cgmImportService.listBatches(LoginContext.current()));
    }

    @GetMapping("/import/batches/{id}")
    public Result<CgmImportService.BatchVO> batch(@PathVariable long id) {
        return Result.ok(cgmImportService.getBatch(LoginContext.current(), id));
    }

    @PostMapping("/import/batches/{id}/confirm")
    public Result<CgmImportService.BatchVO> confirm(@PathVariable long id) {
        return Result.ok(cgmImportService.confirm(LoginContext.current(), id));
    }

    @PostMapping("/import/batches/{id}/revoke")
    public Result<CgmImportService.BatchVO> revoke(@PathVariable long id) {
        return Result.ok(cgmImportService.revoke(LoginContext.current(), id));
    }
}
