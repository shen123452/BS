package com.glucose.glucose.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.glucose.alert.service.AlertService;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.api.PageResult;
import com.glucose.common.enums.Codes;
import com.glucose.common.enums.MeasureType;
import com.glucose.common.exception.BusinessException;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.patient.entity.PatientProfile;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.security.LoginUser;
import com.glucose.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 手工 SMBG 记录:0.5–40.0 mmol/L、最多一位小数;同一来源同一分钟重复返回 40002;
 * 记录与事件同事务;dataKind 由账号模式决定,不由请求指定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlucoseService {

    private final GlucoseRecordMapper recordMapper;
    private final PatientProfileMapper profileMapper;
    private final AlertService alertService;
    private final AuditService auditService;

    public record ManualReq(String measuredAt, BigDecimal glucoseMmol, Integer measureType, String note) {
    }

    public record RecordVO(String id, String measuredAt, String glucoseMmol, String dataSource,
                           String dataKind, Integer measureType, String measureTypeLabel,
                           String riskFlag, String note, String batchId, String createdAt, String ingestMethod) {
    }

    public static void validateGlucose(BigDecimal value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "血糖值不能为空");
        }
        if (value.stripTrailingZeros().scale() > 1) {
            throw new BusinessException(ErrorCode.VALUE_OUT_OF_RANGE, "血糖值最多一位小数");
        }
        if (value.compareTo(BigDecimal.valueOf(0.5)) < 0 || value.compareTo(BigDecimal.valueOf(40.0)) > 0) {
            throw new BusinessException(ErrorCode.VALUE_OUT_OF_RANGE, "血糖值须在 0.5–40.0 mmol/L 之间");
        }
    }

    public static LocalDateTime parseTime(String time) {
        if (time == null || time.isBlank()) {
            return LocalDateTime.now().withNano(0);
        }
        try {
            return LocalDateTime.parse(time, com.glucose.config.JacksonConfig.DATETIME);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "时间格式须为 yyyy-MM-dd HH:mm:ss");
        }
    }

    @Transactional
    public RecordVO addManual(LoginUser user, ManualReq req) {
        long patientId = user.userId();
        profileMapper.requireLock(patientId);
        validateGlucose(req.glucoseMmol());
        if (!MeasureType.isValid(req.measureType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择有效的测量类型");
        }
        LocalDateTime measuredAt = parseTime(req.measuredAt());
        if (measuredAt.isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "测量时间不能超过当前时间 5 分钟");
        }
        String dataKind = kindOf(user);
        requireMinuteAvailable(patientId, dataKind, measuredAt, null);
        var classification = alertService.classify(req.glucoseMmol(), alertService.loadGlobalRules());

        GlucoseRecord record = new GlucoseRecord();
        record.setPatientId(patientId);
        record.setMeasuredAt(measuredAt);
        record.setGlucoseMmol(req.glucoseMmol());
        record.setDataSource(Codes.SOURCE_SMBG);
        record.setIngestMethod(Codes.INGEST_MANUAL);
        record.setDataKind(dataKind);
        record.setMeasureType(req.measureType());
        record.setRiskFlag(classification.flag().name());
        record.setNote(req.note());
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_RECORD, "该时间已有同来源有效记录");
        }
        afterDataChange(patientId, dataKind);
        auditService.record(patientId, "glucose.manual.add", "glucose_record",
                String.valueOf(record.getId()), "手工录入 SMBG 记录", null);
        return toVO(record);
    }

    @Transactional
    public RecordVO updateManual(LoginUser user, long id, ManualReq req) {
        profileMapper.requireLock(user.userId());
        GlucoseRecord record = requireOwnSmbg(user, id);
        validateGlucose(req.glucoseMmol());
        if (!MeasureType.isValid(req.measureType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择有效的测量类型");
        }
        LocalDateTime measuredAt = parseTime(req.measuredAt());
        if (measuredAt.isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "测量时间不能超过当前时间 5 分钟");
        }
        record.setMeasuredAt(measuredAt);
        record.setGlucoseMmol(req.glucoseMmol());
        record.setMeasureType(req.measureType());
        requireMinuteAvailable(user.userId(), record.getDataKind(), measuredAt, id);
        record.setNote(req.note());
        record.setRiskFlag(alertService.classify(req.glucoseMmol(), alertService.loadGlobalRules()).flag().name());
        try {
            recordMapper.updateById(record);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_RECORD, "该时间已有同来源有效记录");
        }
        afterDataChange(user.userId(), record.getDataKind());
        return toVO(record);
    }

    @Transactional
    public void deleteManual(LoginUser user, long id) {
        profileMapper.requireLock(user.userId());
        GlucoseRecord record = requireOwnSmbg(user, id);
        recordMapper.deleteById(record.getId());
        afterDataChange(user.userId(), record.getDataKind());
        auditService.record(user.userId(), "glucose.manual.delete", "glucose_record",
                String.valueOf(id), "删除本人 SMBG 记录", null);
    }

    /** 记录变化后:重算事件 + 数据版本递增(缓存随 dataRevision 失效)。 */
    private void afterDataChange(long patientId, String dataKind) {
        alertService.recomputeAfterChange(patientId, Codes.SOURCE_SMBG, dataKind, false);
        PatientProfile profile = profileMapper.selectOne(Wrappers.<PatientProfile>lambdaQuery()
                .eq(PatientProfile::getUserId, patientId));
        if (profile != null) {
            profileMapper.bumpDataRevision(patientId);
        }
    }

    private GlucoseRecord requireOwnSmbg(LoginUser user, long id) {
        GlucoseRecord record = recordMapper.selectById(id);
        if (record == null || record.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "记录不存在");
        }
        if (!java.util.Objects.equals(record.getPatientId(), user.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!Codes.SOURCE_SMBG.equals(record.getDataSource())
                || !Codes.INGEST_MANUAL.equals(record.getIngestMethod())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "CGM 记录不支持单条编辑,请通过批次撤销纠正");
        }
        return record;
    }

    public static String kindOf(LoginUser user) {
        return Codes.KIND_DEMO.equals(user.dataMode()) ? Codes.KIND_DEMO : Codes.KIND_REAL;
    }

    private void requireMinuteAvailable(long patientId, String kind, LocalDateTime measuredAt, Long excludedId) {
        LocalDateTime minute = measuredAt.withSecond(0).withNano(0);
        Long count = recordMapper.selectCount(Wrappers.<GlucoseRecord>lambdaQuery()
                .eq(GlucoseRecord::getPatientId, patientId).eq(GlucoseRecord::getDataSource, Codes.SOURCE_SMBG)
                .eq(GlucoseRecord::getDataKind, kind).ge(GlucoseRecord::getMeasuredAt, minute)
                .lt(GlucoseRecord::getMeasuredAt, minute.plusMinutes(1))
                .ne(excludedId != null, GlucoseRecord::getId, excludedId));
        if (count != null && count > 0) throw new BusinessException(ErrorCode.DUPLICATE_RECORD, "该分钟已有同来源有效记录");
    }

    public static RecordVO toVO(GlucoseRecord record) {
        return new RecordVO(String.valueOf(record.getId()),
                record.getMeasuredAt().format(com.glucose.config.JacksonConfig.DATETIME),
                String.valueOf(record.getGlucoseMmol()), record.getDataSource(), record.getDataKind(),
                record.getMeasureType(), MeasureType.labelOf(record.getMeasureType()),
                record.getRiskFlag(), record.getNote(),
                record.getBatchId() == null ? "" : String.valueOf(record.getBatchId()),
                record.getCreatedAt() == null ? "" : record.getCreatedAt().format(com.glucose.config.JacksonConfig.DATETIME), record.getIngestMethod());
    }

    /* ================= 查询 ================= */

    public PageResult<RecordVO> pageRecords(long patientId, String dataSource, Integer measureType,
                                            LocalDate date, long pageNum, long pageSize) {
        return pageRecords(patientId, dataSource, measureType, date, null, null, pageNum, pageSize);
    }

    public PageResult<RecordVO> pageRecords(long patientId, String dataSource, Integer measureType,
                                            LocalDate date, LocalDate startDate, LocalDate endDate,
                                            long pageNum, long pageSize) {
        if (pageNum < 1 || pageSize < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "页码和每页条数须为正数");
        }
        if (startDate != null && endDate != null && !startDate.isBefore(endDate)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "startDate 必须早于 endDate");
        }
        var query = Wrappers.<GlucoseRecord>lambdaQuery()
                .eq(GlucoseRecord::getPatientId, patientId)
                .eq(dataSource != null && !dataSource.isBlank(), GlucoseRecord::getDataSource, dataSource)
                .eq(measureType != null, GlucoseRecord::getMeasureType, measureType);
        if (date != null) {
            query.ge(GlucoseRecord::getMeasuredAt, date.atStartOfDay())
                    .lt(GlucoseRecord::getMeasuredAt, date.plusDays(1).atStartOfDay());
        }
        if (startDate != null) {
            query.ge(GlucoseRecord::getMeasuredAt, startDate.atStartOfDay());
        }
        if (endDate != null) {
            query.lt(GlucoseRecord::getMeasuredAt, endDate.atStartOfDay());
        }
        Page<GlucoseRecord> page = recordMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                query.orderByDesc(GlucoseRecord::getMeasuredAt));
        return PageResult.of(page.getTotal(), page.getCurrent(),
                page.getRecords().stream().map(GlucoseService::toVO).toList());
    }

    /** Export a bounded, authorization-filtered CSV snapshot; endDate is exclusive. */
    public byte[] exportCsv(long patientId, String dataSource, LocalDate startDate, LocalDate endDate) {
        var records = exportRecords(patientId, dataSource, startDate, endDate);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("测量时间,血糖值(mmol/L),来源,数据性质,测量类型,提醒标记,备注,导入方式\r\n");
        for (GlucoseRecord record : records) {
            csv.append(csv(record.getMeasuredAt().format(com.glucose.config.JacksonConfig.DATETIME))).append(',')
                    .append(csv(record.getGlucoseMmol())).append(',').append(csv(record.getDataSource())).append(',')
                    .append(csv(record.getDataKind())).append(',').append(csv(MeasureType.labelOf(record.getMeasureType())))
                    .append(',').append(csv(record.getRiskFlag())).append(',').append(csv(record.getNote()))
                    .append(',').append(csv(record.getIngestMethod())).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Export a bounded, authorization-filtered OOXML workbook; endDate is exclusive. */
    public byte[] exportXlsx(long patientId, String dataSource, LocalDate startDate, LocalDate endDate) {
        var records = exportRecords(patientId, dataSource, startDate, endDate);
        String[] headers = {"测量时间", "血糖值(mmol/L)", "来源", "数据性质", "测量类型", "提醒标记", "备注", "导入方式"};
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zipEntry(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                          <Default Extension="xml" ContentType="application/xml"/>
                          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                          <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                        </Types>
                        """);
                zipEntry(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                        </Relationships>
                        """);
                zipEntry(zip, "xl/workbook.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                          <sheets><sheet name="血糖记录" sheetId="1" r:id="rId1"/></sheets>
                        </workbook>
                        """);
                zipEntry(zip, "xl/_rels/workbook.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                        </Relationships>
                        """);
                zipEntry(zip, "xl/styles.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <numFmts count="0"/>
                          <fonts count="2"><font><sz val="11"/><name val="等线"/></font><font><b/><sz val="11"/><name val="等线"/></font></fonts>
                          <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                          <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                          <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                          <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/></cellXfs>
                          <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                          <dxfs count="0"/>
                        </styleSheet>
                        """);

                StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
                sheet.append("<row r=\"1\">");
                for (int i = 0; i < headers.length; i++) {
                    sheet.append(inlineCell(i + 1, 1, headers[i], true));
                }
                sheet.append("</row>");
                int row = 2;
                for (GlucoseRecord record : records) {
                    sheet.append("<row r=\"").append(row).append("\">")
                            .append(inlineCell(1, row, record.getMeasuredAt().format(com.glucose.config.JacksonConfig.DATETIME), false))
                            .append(numberCell(2, row, record.getGlucoseMmol()))
                            .append(inlineCell(3, row, record.getDataSource(), false))
                            .append(inlineCell(4, row, record.getDataKind(), false))
                            .append(inlineCell(5, row, MeasureType.labelOf(record.getMeasureType()), false))
                            .append(inlineCell(6, row, record.getRiskFlag(), false))
                            .append(inlineCell(7, row, record.getNote(), false))
                            .append(inlineCell(8, row, record.getIngestMethod(), false))
                            .append("</row>");
                    row++;
                }
                sheet.append("</sheetData></worksheet>");
                zipEntry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            }
            return output.toByteArray();
        } catch (IOException e) {
            log.error("xlsx export failed", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Excel 导出失败");
        }
    }

    private List<GlucoseRecord> exportRecords(long patientId, String dataSource, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "导出日期范围必须为有效的开始日和结束日");
        }
        if (Duration.between(startDate.atStartOfDay(), endDate.atStartOfDay()).toDays() > 366) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "单次最多导出 366 天");
        }
        if (dataSource != null && !dataSource.isBlank() && !Codes.SOURCE_CGM.equals(dataSource)
                && !Codes.SOURCE_SMBG.equals(dataSource)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "dataSource 仅支持 SMBG 或 CGM");
        }
        var records = recordMapper.selectList(Wrappers.<GlucoseRecord>lambdaQuery()
                .eq(GlucoseRecord::getPatientId, patientId)
                .eq(dataSource != null && !dataSource.isBlank(), GlucoseRecord::getDataSource, dataSource)
                .ge(GlucoseRecord::getMeasuredAt, startDate.atStartOfDay())
                .lt(GlucoseRecord::getMeasuredAt, endDate.atStartOfDay())
                .orderByAsc(GlucoseRecord::getMeasuredAt).last("LIMIT 10001"));
        if (records.size() > 10000) throw new BusinessException(ErrorCode.PARAM_ERROR, "单次最多导出 10000 条记录，请缩小日期范围");
        return records;
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replace("\"", "\"\"").replace("\r", " ").replace("\n", " ");
        if (value instanceof String && startsLikeFormula(text)) text = "'" + text;
        return "\"" + text + "\"";
    }

    private static void zipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String inlineCell(int column, int row, String value, boolean header) {
        String ref = columnName(column) + row;
        String text = value == null ? "" : value;
        if (startsLikeFormula(text)) text = "'" + text;
        return "<c r=\"" + ref + "\" t=\"inlineStr\"" + (header ? " s=\"1\"" : "") + "><is><t xml:space=\"preserve\">"
                + xml(text) + "</t></is></c>";
    }

    private static String numberCell(int column, int row, Object value) {
        String ref = columnName(column) + row;
        return value == null ? "<c r=\"" + ref + "\"/>" : "<c r=\"" + ref + "\"><v>" + xml(String.valueOf(value)) + "</v></c>";
    }

    private static String columnName(int column) {
        StringBuilder out = new StringBuilder();
        int n = column;
        while (n > 0) {
            int remainder = (n - 1) % 26;
            out.append((char) ('A' + remainder));
            n = (n - 1) / 26;
        }
        return out.reverse().toString();
    }

    private static String xml(String value) {
        StringBuilder valid = new StringBuilder(value.length());
        value.codePoints().filter(cp -> cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                        || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF))
                .forEach(valid::appendCodePoint);
        return valid.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static boolean startsLikeFormula(String value) {
        int index = 0;
        while (index < value.length() && (Character.isWhitespace(value.charAt(index)) || Character.isISOControl(value.charAt(index)))) index++;
        return index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0;
    }

    /** 分来源最新值与今日描述性概览。 */
    public Map<String, Object> latestOverview(LoginUser user) {
        long patientId = user.userId();
        String dataKind = kindOf(user);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String source : List.of(Codes.SOURCE_SMBG, Codes.SOURCE_CGM)) {
            GlucoseRecord latest = recordMapper.selectOne(Wrappers.<GlucoseRecord>lambdaQuery()
                    .eq(GlucoseRecord::getPatientId, patientId)
                    .eq(GlucoseRecord::getDataSource, source)
                    .eq(GlucoseRecord::getDataKind, dataKind)
                    .orderByDesc(GlucoseRecord::getMeasuredAt)
                    .last("LIMIT 1"));
            LocalDateTime dayStart = LocalDate.now().atStartOfDay();
            List<GlucoseRecord> today = recordMapper.selectList(Wrappers.<GlucoseRecord>lambdaQuery()
                    .eq(GlucoseRecord::getPatientId, patientId)
                    .eq(GlucoseRecord::getDataSource, source)
                    .eq(GlucoseRecord::getDataKind, dataKind)
                    .ge(GlucoseRecord::getMeasuredAt, dayStart)
                    .orderByAsc(GlucoseRecord::getMeasuredAt));
            Map<String, Object> sourceData = new LinkedHashMap<>();
            sourceData.put("latest", latest == null ? null : toVO(latest));
            sourceData.put("todayCount", today.size());
            sourceData.put("todayAvg", today.isEmpty() ? null
                    : today.stream().map(GlucoseRecord::getGlucoseMmol)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(today.size()), 1, java.math.RoundingMode.HALF_UP));
            sourceData.put("todayMin", today.isEmpty() ? null
                    : today.stream().map(GlucoseRecord::getGlucoseMmol).min(BigDecimal::compareTo).orElse(null));
            sourceData.put("todayMax", today.isEmpty() ? null
                    : today.stream().map(GlucoseRecord::getGlucoseMmol).max(BigDecimal::compareTo).orElse(null));
            result.put(source, sourceData);
        }
        result.put("dataKind", dataKind);
        return result;
    }

    /** 判断测量时间是否为补录(>15 分钟前),供导入与生成器标记历史事件。 */
    public static boolean isBackfilled(LocalDateTime measuredAt) {
        return Duration.between(measuredAt, LocalDateTime.now()).toMinutes() > 15;
    }
}
