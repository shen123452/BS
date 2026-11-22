package com.glucose.glucose.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.alert.service.AlertService;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.enums.Codes;
import com.glucose.common.exception.BusinessException;
import com.glucose.config.AppProperties;
import com.glucose.glucose.entity.GlucoseImportBatch;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseImportBatchMapper;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.security.LoginUser;
import com.glucose.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CGM 标准 CSV 导入(§6.5):固定表头 measured_at/glucose_mmol,5 分钟网格,
 * ≤10000 行、≤5MB;预检→确认→撤销,批次可追溯;相同 requestId 幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CgmImportService {

    private static final DateTimeFormatter TS = com.glucose.config.JacksonConfig.DATETIME;
    private static final int MAX_ROWS = 10000;
    private static final int ERROR_DETAIL_LIMIT = 100;

    private final GlucoseImportBatchMapper batchMapper;
    private final GlucoseRecordMapper recordMapper;
    private final PatientProfileMapper profileMapper;
    private final AlertService alertService;
    private final AuditService auditService;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public record ImportError(int line, String field, String reason) {
    }

    public record PreviewVO(String batchId, String status, int totalRows, int validRows, int duplicateRows,
                            int conflictRows, int errorRows, List<ImportError> errors, String expiresAt) {
    }

    public record BatchVO(String id, String status, int totalRows, int validRows, int duplicateRows,
                          int conflictRows, int errorRows, int insertedRows, String startAt, String endAt,
                          String expiresAt, String createdAt, List<ImportError> errors) {
    }

    /* ================= 模板 ================= */

    public byte[] template() {
        return """
                measured_at,glucose_mmol
                2026-10-18 00:00:00,8.1
                2026-10-18 00:05:00,8.3
                2026-10-18 00:10:00,8.2
                """.getBytes(StandardCharsets.UTF_8);
    }

    /* ================= 预检 ================= */

    @Transactional
    public PreviewVO preview(LoginUser user, MultipartFile file, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 36) throw new BusinessException(ErrorCode.PARAM_ERROR, "requestId 长度须为 1–36 字符");
        long patientId = user.userId();
        profileMapper.requireLock(patientId);
        // 相同 requestId 幂等:返回原批次
        GlucoseImportBatch existing = batchMapper.selectOne(Wrappers.<GlucoseImportBatch>lambdaQuery()
                .eq(GlucoseImportBatch::getPatientId, patientId)
                .eq(GlucoseImportBatch::getRequestId, requestId));
        if (existing != null) {
            return new PreviewVO(String.valueOf(existing.getId()), existing.getStatus(),
                    existing.getTotalRows(), existing.getValidRows(), existing.getDuplicateRows(),
                    existing.getConflictRows(), existing.getErrorRows(), loadErrors(existing),
                    existing.getExpiresAt().format(com.glucose.config.JacksonConfig.DATETIME));
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "请选择 CSV 文件");
        }
        if (file.getSize() > 5L * 1024 * 1024) throw new BusinessException(ErrorCode.FILE_INVALID, "单批文件不能超过 5 MB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".csv")) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "仅支持 .csv 文件");
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "文件读取失败");
        }
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        String sha256 = sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        // 相同文件已有可确认批次:重新检查已有点(不能仅凭文件摘要永久禁止重新导入)
        String[] lines = content.split("\r?\n");
        if (lines.length < 2) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "文件缺少数据行");
        }
        if (!"measured_at,glucose_mmol".equalsIgnoreCase(lines[0].trim())) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "表头必须为 measured_at,glucose_mmol");
        }
        int dataRows = lines.length - 1;
        if (dataRows > MAX_ROWS) {
            throw new BusinessException(ErrorCode.FILE_INVALID, "单批最多 " + MAX_ROWS + " 行数据");
        }

        String dataKind = GlucoseService.kindOf(user);
        Map<LocalDateTime, BigDecimal> parsed = new LinkedHashMap<>();
        Set<LocalDateTime> fileDupTimes = new HashSet<>();
        List<ImportError> errors = new ArrayList<>();
        int validRows = 0;
        int duplicateRows = 0;
        int conflictRows = 0;
        int errorRows = 0;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length != 2) {
                errorRows++;
                errors.add(new ImportError(i + 1, "row", "行列数不为 2"));
                continue;
            }
            LocalDateTime time;
            try {
                time = LocalDateTime.parse(parts[0].trim(), TS);
            } catch (Exception e) {
                errorRows++;
                errors.add(new ImportError(i + 1, "measured_at", "时间格式须为 yyyy-MM-dd HH:mm:ss"));
                continue;
            }
            if (time.getSecond() != 0 || time.getMinute() % 5 != 0) {
                errorRows++;
                errors.add(new ImportError(i + 1, "measured_at", "时间须为 5 分钟网格(分钟为 5 的倍数,秒为 00)"));
                continue;
            }
            if (time.isAfter(LocalDateTime.now().plusMinutes(5))) {
                errorRows++;
                errors.add(new ImportError(i + 1, "measured_at", "测量时间不能超过当前时间 5 分钟"));
                continue;
            }
            BigDecimal value;
            try {
                value = new BigDecimal(parts[1].trim());
            } catch (Exception e) {
                errorRows++;
                errors.add(new ImportError(i + 1, "glucose_mmol", "数值格式错误"));
                continue;
            }
            try {
                GlucoseService.validateGlucose(value);
            } catch (BusinessException e) {
                errorRows++;
                errors.add(new ImportError(i + 1, "glucose_mmol", e.getMessage()));
                continue;
            }
            validRows++;
            BigDecimal prev = parsed.get(time);
            if (prev != null) {
                if (prev.compareTo(value) == 0) {
                    duplicateRows++;
                    fileDupTimes.add(time);
                } else {
                    conflictRows++;
                    errors.add(new ImportError(i + 1, "glucose_mmol", "文件内同一时间存在不同数值"));
                }
                continue;
            }
            GlucoseRecord dbRecord = recordMapper.selectOne(Wrappers.<GlucoseRecord>lambdaQuery()
                    .eq(GlucoseRecord::getPatientId, patientId)
                    .eq(GlucoseRecord::getDataSource, Codes.SOURCE_CGM)
                    .eq(GlucoseRecord::getDataKind, dataKind)
                    .eq(GlucoseRecord::getMeasuredAt, time));
            if (dbRecord != null) {
                if (dbRecord.getGlucoseMmol().compareTo(value) == 0) {
                    duplicateRows++;
                } else {
                    conflictRows++;
                    errors.add(new ImportError(i + 1, "glucose_mmol",
                            "与已有记录(" + dbRecord.getGlucoseMmol() + " mmol/L)时间相同但数值不同"));
                }
                continue;
            }
            parsed.put(time, value);
        }

        GlucoseImportBatch batch = new GlucoseImportBatch();
        batch.setPatientId(patientId);
        batch.setRequestId(requestId);
        batch.setFileSha256(sha256);
        batch.setOriginalName(file.getOriginalFilename());
        batch.setDataSource(Codes.SOURCE_CGM);
        batch.setDataKind(dataKind);
        batch.setSampleIntervalMinutes(5);
        batch.setStatus(Codes.BATCH_READY);
        batch.setTotalRows(dataRows);
        batch.setValidRows(validRows);
        batch.setDuplicateRows(duplicateRows);
        batch.setConflictRows(conflictRows);
        batch.setErrorRows(errorRows);
        batch.setInsertedRows(0);
        batch.setExpiresAt(LocalDateTime.now().plusMinutes(props.getImportCfg().getPreviewExpireMinutes()));
        if (!parsed.isEmpty() || validRows > 0) {
            batch.setStartAt(parsed.keySet().stream().min(LocalDateTime::compareTo).orElse(null));
            batch.setEndAt(parsed.keySet().stream().max(LocalDateTime::compareTo).orElse(null));
        }
        batchMapper.insert(batch);
        saveTemp(batch, content, errors);
        return new PreviewVO(String.valueOf(batch.getId()), batch.getStatus(), batch.getTotalRows(),
                batch.getValidRows(), batch.getDuplicateRows(), batch.getConflictRows(),
                batch.getErrorRows(), cap(errors), batch.getExpiresAt().format(com.glucose.config.JacksonConfig.DATETIME));
    }

    /* ================= 批次查询 ================= */

    public BatchVO getBatch(LoginUser user, long batchId) {
        GlucoseImportBatch batch = requireOwnBatch(user, batchId);
        return new BatchVO(String.valueOf(batch.getId()), batch.getStatus(), batch.getTotalRows(),
                batch.getValidRows(), batch.getDuplicateRows(), batch.getConflictRows(),
                batch.getErrorRows(), batch.getInsertedRows(),
                batch.getStartAt() == null ? "" : batch.getStartAt().format(com.glucose.config.JacksonConfig.DATETIME),
                batch.getEndAt() == null ? "" : batch.getEndAt().format(com.glucose.config.JacksonConfig.DATETIME),
                batch.getExpiresAt().format(com.glucose.config.JacksonConfig.DATETIME),
                batch.getCreatedAt().format(com.glucose.config.JacksonConfig.DATETIME),
                loadErrors(batch));
    }

    public List<BatchVO> listBatches(LoginUser user) {
        return batchMapper.selectList(Wrappers.<GlucoseImportBatch>lambdaQuery()
                .eq(GlucoseImportBatch::getPatientId, user.userId())
                .orderByDesc(GlucoseImportBatch::getCreatedAt))
                .stream().map(b -> new BatchVO(String.valueOf(b.getId()), b.getStatus(), b.getTotalRows(),
                        b.getValidRows(), b.getDuplicateRows(), b.getConflictRows(), b.getErrorRows(),
                        b.getInsertedRows(),
                        b.getStartAt() == null ? "" : b.getStartAt().format(com.glucose.config.JacksonConfig.DATETIME),
                        b.getEndAt() == null ? "" : b.getEndAt().format(com.glucose.config.JacksonConfig.DATETIME),
                        b.getExpiresAt().format(com.glucose.config.JacksonConfig.DATETIME),
                        b.getCreatedAt().format(com.glucose.config.JacksonConfig.DATETIME),
                        List.<ImportError>of()))
                .toList();
    }

    /* ================= 确认 ================= */

    @Transactional
    public BatchVO confirm(LoginUser user, long batchId) {
        profileMapper.requireLock(user.userId());
        GlucoseImportBatch batch = requireOwnBatch(user, batchId);
        if (Codes.BATCH_CONFIRMED.equals(batch.getStatus()) || Codes.BATCH_NO_CHANGE.equals(batch.getStatus())) {
            return getBatch(user, batchId); // 重复确认返回原结果
        }
        if (Codes.BATCH_REVOKED.equals(batch.getStatus()) || Codes.BATCH_EXPIRED.equals(batch.getStatus())) {
            throw new BusinessException(ErrorCode.BATCH_NOT_CONFIRMABLE, "批次已" + batch.getStatus() + ",不能确认");
        }
        if (LocalDateTime.now().isAfter(batch.getExpiresAt())) {
            batch.setStatus(Codes.BATCH_EXPIRED);
            batchMapper.updateById(batch);
            throw new BusinessException(ErrorCode.IMPORT_REPREVIEW_REQUIRED, "预检已过期,请重新上传预检");
        }
        if (batch.getErrorRows() > 0 || batch.getConflictRows() > 0) {
            throw new BusinessException(ErrorCode.BATCH_NOT_CONFIRMABLE, "存在错误或冲突行,不能确认");
        }
        String content = readTempContent(batch);
        if (!sha256Hex(content.getBytes(StandardCharsets.UTF_8)).equals(batch.getFileSha256())) {
            throw new BusinessException(ErrorCode.IMPORT_REPREVIEW_REQUIRED, "文件内容已变化,请重新预检");
        }
        // 确认前重新核对已有点;有变化需重新预检
        int recheckConflict = 0;
        List<String[]> rows = parseRows(content);
        Map<LocalDateTime, BigDecimal> toInsert = new TreeMap<>();
        for (String[] parts : rows) {
            LocalDateTime time = LocalDateTime.parse(parts[0].trim(), TS);
            BigDecimal value = new BigDecimal(parts[1].trim());
            GlucoseRecord dbRecord = recordMapper.selectOne(Wrappers.<GlucoseRecord>lambdaQuery()
                    .eq(GlucoseRecord::getPatientId, user.userId())
                    .eq(GlucoseRecord::getDataSource, Codes.SOURCE_CGM)
                    .eq(GlucoseRecord::getDataKind, batch.getDataKind())
                    .eq(GlucoseRecord::getMeasuredAt, time));
            if (dbRecord != null) {
                if (dbRecord.getGlucoseMmol().compareTo(value) != 0) {
                    recheckConflict++;
                }
                continue;
            }
            toInsert.put(time, value);
        }
        if (recheckConflict > 0) {
            throw new BusinessException(ErrorCode.IMPORT_REPREVIEW_REQUIRED,
                    "已有记录发生变化(" + recheckConflict + " 处冲突),请重新预检");
        }
        int inserted = 0;
        for (Map.Entry<LocalDateTime, BigDecimal> entry : toInsert.entrySet()) {
            GlucoseRecord record = new GlucoseRecord();
            record.setPatientId(user.userId());
            record.setMeasuredAt(entry.getKey());
            record.setGlucoseMmol(entry.getValue());
            record.setDataSource(Codes.SOURCE_CGM);
            record.setIngestMethod(Codes.INGEST_CSV);
            record.setDataKind(batch.getDataKind());
            record.setSampleIntervalMinutes(5);
            record.setBatchId(batch.getId());
            record.setRiskFlag(alertService.classify(entry.getValue(), alertService.loadGlobalRules()).flag().name());
            try {
                recordMapper.insert(record);
                inserted++;
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.IMPORT_REPREVIEW_REQUIRED, "已有记录发生变化，请重新预检");
            }
        }
        batch.setInsertedRows(inserted);
        batch.setConfirmedAt(LocalDateTime.now());
        batch.setStatus(inserted == 0 ? Codes.BATCH_NO_CHANGE : Codes.BATCH_CONFIRMED);
        batchMapper.updateById(batch);
        if (inserted > 0) {
            // 导入产生的事件标历史,不作实时提醒
            alertService.recomputeAfterChange(user.userId(), Codes.SOURCE_CGM, batch.getDataKind(), true);
            profileMapper.bumpDataRevision(user.userId());
        }
        auditService.record(user.userId(), "glucose.import.confirm", "glucose_import_batch",
                String.valueOf(batch.getId()), "确认 CGM 导入批次,新增 " + inserted + " 条", null);
        return getBatch(user, batchId);
    }

    /* ================= 撤销 ================= */

    @Transactional
    public BatchVO revoke(LoginUser user, long batchId) {
        profileMapper.requireLock(user.userId());
        GlucoseImportBatch batch = requireOwnBatch(user, batchId);
        if (Codes.BATCH_REVOKED.equals(batch.getStatus())) return getBatch(user, batchId);
        if (!Codes.BATCH_CONFIRMED.equals(batch.getStatus())) {
            throw new BusinessException(ErrorCode.BATCH_NOT_CONFIRMABLE, "仅已确认批次可撤销");
        }
        List<GlucoseRecord> batchRecords = recordMapper.selectList(Wrappers.<GlucoseRecord>lambdaQuery()
                .eq(GlucoseRecord::getBatchId, batch.getId()));
        int removed = 0;
        for (GlucoseRecord record : batchRecords) {
            // 只删本批新增点;不删除其他批次的同值重复点
            recordMapper.deleteById(record.getId());
            removed++;
        }
        batch.setStatus(Codes.BATCH_REVOKED);
        batch.setRevokedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        if (removed > 0) {
            alertService.recomputeAfterChange(user.userId(), Codes.SOURCE_CGM, batch.getDataKind(), true);
            profileMapper.bumpDataRevision(user.userId());
        }
        auditService.record(user.userId(), "glucose.import.revoke", "glucose_import_batch",
                String.valueOf(batch.getId()), "撤销 CGM 导入批次,移除 " + removed + " 条", null);
        return getBatch(user, batchId);
    }

    /* ================= helpers ================= */

    private GlucoseImportBatch requireOwnBatch(LoginUser user, long batchId) {
        GlucoseImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "批次不存在");
        }
        if (!Objects.equals(batch.getPatientId(), user.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return batch;
    }

    private static List<String[]> parseRows(String content) {
        String[] lines = content.split("\r?\n");
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            rows.add(line.split(","));
        }
        return rows;
    }

    private Path tempDir() {
        return Paths.get(props.getImportCfg().getTempDir());
    }

    private void saveTemp(GlucoseImportBatch batch, String content, List<ImportError> errors) {
        try {
            Files.createDirectories(tempDir());
            Path file = tempDir().resolve("batch-" + batch.getId() + ".csv");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            Path errorsFile = tempDir().resolve("batch-" + batch.getId() + ".errors.json");
            Files.writeString(errorsFile, objectMapper.writeValueAsString(cap(errors)), StandardCharsets.UTF_8);
            batch.setTempFileKey(file.getFileName().toString());
            batchMapper.updateById(batch);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入临时文件保存失败");
        }
    }

    private String readTempContent(GlucoseImportBatch batch) {
        try {
            return Files.readString(tempDir().resolve(batch.getTempFileKey()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.IMPORT_REPREVIEW_REQUIRED, "预检临时文件已清理,请重新预检");
        }
    }

    @SuppressWarnings("unchecked")
    private List<ImportError> loadErrors(GlucoseImportBatch batch) {
        if (batch.getTempFileKey() == null || batch.getErrorRows() == 0 && batch.getConflictRows() == 0) {
            return List.of();
        }
        try {
            Path errorsFile = tempDir().resolve(
                    batch.getTempFileKey().replace(".csv", ".errors.json"));
            if (!Files.exists(errorsFile)) {
                return List.of();
            }
            return objectMapper.readValue(Files.readString(errorsFile, StandardCharsets.UTF_8),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ImportError.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<ImportError> cap(List<ImportError> errors) {
        return errors.size() > ERROR_DETAIL_LIMIT ? errors.subList(0, ERROR_DETAIL_LIMIT) : errors;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
