package com.glucose.alert.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.glucose.alert.entity.AlertRecord;
import com.glucose.alert.mapper.AlertRecordMapper;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.api.PageResult;
import com.glucose.common.api.Result;
import com.glucose.common.exception.BusinessException;
import com.glucose.patient.service.PatientAccessService;
import com.glucose.security.LoginContext;
import com.glucose.security.LoginUser;
import com.glucose.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 预警接口:患者提交已读(noticeVersion);医生处理(version 乐观校验)。
 */
@RestController
@RequiredArgsConstructor
public class AlertController {

    private final AlertRecordMapper alertMapper;
    private final PatientAccessService accessService;
    private final AuditService auditService;

    public record AlertVO(String id, String patientId, String dataSource, String dataKind, String alertType,
                          Integer level, String startAt, String lastAbnormalAt, String endAt,
                          String minGlucose, String maxGlucose, Integer sampleCount, String eventStatus,
                          String handleStatus, Integer isHistorical, String message, Long version,
                          Long noticeVersion, Long patientSeenNoticeVersion, String handledAt,
                          String handleNote, String createdAt) {
    }

    private static AlertVO toVO(AlertRecord a) {
        return new AlertVO(String.valueOf(a.getId()), String.valueOf(a.getPatientId()), a.getDataSource(),
                a.getDataKind(), a.getAlertType(), a.getLevel(),
                a.getStartAt() == null ? "" : a.getStartAt().format(com.glucose.config.JacksonConfig.DATETIME),
                a.getLastAbnormalAt() == null ? "" : a.getLastAbnormalAt().format(com.glucose.config.JacksonConfig.DATETIME),
                a.getEndAt() == null ? "" : a.getEndAt().format(com.glucose.config.JacksonConfig.DATETIME),
                String.valueOf(a.getMinGlucose()), String.valueOf(a.getMaxGlucose()), a.getSampleCount(),
                a.getEventStatus(), a.getHandleStatus(), a.getIsHistorical(), a.getMessage(), a.getVersion(),
                a.getNoticeVersion(), a.getPatientSeenNoticeVersion(),
                a.getHandledAt() == null ? "" : a.getHandledAt().format(com.glucose.config.JacksonConfig.DATETIME),
                a.getHandleNote() == null ? "" : a.getHandleNote(),
                a.getCreatedAt() == null ? "" : a.getCreatedAt().format(com.glucose.config.JacksonConfig.DATETIME));
    }

    /* ================= 患者端 ================= */

    @GetMapping("/api/app/alerts")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<PageResult<AlertVO>> appList(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String eventStatus,
            @RequestParam(required = false) Integer isHistorical) {
        long patientId = LoginContext.currentUserId();
        var query = Wrappers.<AlertRecord>lambdaQuery()
                .eq(AlertRecord::getPatientId, patientId)
                // 待处理优先(按未读与级别),再按发生时间倒序
                .orderByAsc(AlertRecord::getHandleStatus)
                .orderByDesc(AlertRecord::getLevel)
                .orderByDesc(AlertRecord::getStartAt);
        if (eventStatus != null && !eventStatus.isBlank()) {
            query.eq(AlertRecord::getEventStatus, eventStatus);
        }
        if (isHistorical != null) {
            query.eq(AlertRecord::getIsHistorical, isHistorical);
        }
        Page<AlertRecord> page = alertMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), query);
        return Result.ok(PageResult.of(page.getTotal(), page.getCurrent(),
                page.getRecords().stream().map(AlertController::toVO).toList()));
    }

    @PutMapping("/api/app/alerts/{id}/read")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<Void> markRead(@PathVariable long id, @RequestBody Map<String, Long> body) {
        long patientId = LoginContext.currentUserId();
        AlertRecord alert = alertMapper.selectById(id);
        if (alert == null || alert.getPatientId() != patientId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        Long noticeVersion = body.get("noticeVersion");
        if (noticeVersion == null || noticeVersion < 0 || noticeVersion > alert.getNoticeVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "通知版本无效,请刷新");
        }
        // 只确认实际看过的版本;更新后再次升级仍会产生未读
        alertMapper.update(null, new LambdaUpdateWrapper<AlertRecord>()
                .eq(AlertRecord::getId, id)
                .lt(AlertRecord::getPatientSeenNoticeVersion, noticeVersion)
                .set(AlertRecord::getPatientSeenNoticeVersion, noticeVersion));
        return Result.ok();
    }

    /* ================= 管理端 ================= */

    @GetMapping("/api/admin/alerts")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<PageResult<AlertVO>> adminList(
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String handleStatus,
            @RequestParam(required = false) Integer isHistorical,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        LoginUser user = LoginContext.current();
        var query = Wrappers.<AlertRecord>lambdaQuery()
                .orderByAsc(AlertRecord::getHandleStatus)
                .orderByDesc(AlertRecord::getLevel)
                .orderByDesc(AlertRecord::getStartAt);
        if (patientId != null) {
            accessService.requireReadable(user, patientId);
            query.eq(AlertRecord::getPatientId, patientId);
        } else if ("DOCTOR".equals(user.role())) {
            // 医生不指定患者时,仅返回其绑定患者的事件
            List<Long> boundIds = profileMapper.selectList(
                            Wrappers.<com.glucose.patient.entity.PatientProfile>lambdaQuery()
                                    .eq(com.glucose.patient.entity.PatientProfile::getDoctorId, user.userId()))
                    .stream().map(com.glucose.patient.entity.PatientProfile::getUserId).toList();
            if (boundIds.isEmpty()) {
                return Result.ok(PageResult.of(0, pageNum, List.of()));
            }
            query.in(AlertRecord::getPatientId, boundIds);
        }
        if (alertType != null && !alertType.isBlank()) {
            query.eq(AlertRecord::getAlertType, alertType);
        }
        if (level != null) {
            query.eq(AlertRecord::getLevel, level);
        }
        if (handleStatus != null && !handleStatus.isBlank()) {
            query.eq(AlertRecord::getHandleStatus, handleStatus);
        }
        if (isHistorical != null) {
            query.eq(AlertRecord::getIsHistorical, isHistorical);
        }
        Page<AlertRecord> page = alertMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), query);
        if ("ADMIN".equals(user.role())) {
            auditService.record(user.userId(), "admin.view.alerts", "alert_record", "",
                    "管理员查询预警列表", null);
        }
        return Result.ok(PageResult.of(page.getTotal(), page.getCurrent(),
                page.getRecords().stream().map(AlertController::toVO).toList()));
    }

    private final com.glucose.patient.mapper.PatientProfileMapper profileMapper;

    @PutMapping("/api/admin/alerts/{id}/handle")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    @Transactional
    public Result<AlertVO> handle(@PathVariable long id, @RequestBody Map<String, Object> body) {
        LoginUser user = LoginContext.current();
        AlertRecord alert = alertMapper.selectById(id);
        if (alert == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        accessService.requireReadable(user, alert.getPatientId());
        // Long 对外序列化为字符串,兼容数字与字符串两种回传形式
        long version;
        try {
            version = Long.parseLong(String.valueOf(body.get("version")));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "version 无效");
        }
        String handleNote = body.get("handleNote") == null ? "" : String.valueOf(body.get("handleNote"));
        if (handleNote.length() > 255) throw new BusinessException(ErrorCode.PARAM_ERROR, "处理备注最多 255 字");
        if (version != alert.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "事件已被他人更新,请刷新后重试");
        }
        alert.setHandleStatus("HANDLED");
        alert.setHandledBy(user.userId());
        alert.setHandledAt(java.time.LocalDateTime.now());
        alert.setHandleNote(handleNote);
        int updated = alertMapper.update(null, Wrappers.<AlertRecord>lambdaUpdate()
                .eq(AlertRecord::getId, id).eq(AlertRecord::getVersion, version)
                .ne(AlertRecord::getEventStatus, "INVALIDATED")
                .set(AlertRecord::getHandleStatus, alert.getHandleStatus())
                .set(AlertRecord::getHandledBy, alert.getHandledBy())
                .set(AlertRecord::getHandledAt, alert.getHandledAt())
                .set(AlertRecord::getHandleNote, handleNote)
                .set(AlertRecord::getVersion, version + 1));
        if (updated != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "事件已变化，请刷新后重试");
        alert.setVersion(version + 1);
        auditService.record(user.userId(), "alert.handle", "alert_record", String.valueOf(id),
                "处理预警事件", Map.of("note", handleNote));
        return Result.ok(toVO(alert));
    }
}
