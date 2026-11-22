package com.glucose;

import com.glucose.alert.service.AlertService;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.glucose.service.GlucoseService;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.system.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class XlsxExportTest {

    @Mock GlucoseRecordMapper recordMapper;
    @Mock PatientProfileMapper profileMapper;
    @Mock AlertService alertService;
    @Mock AuditService auditService;
    GlucoseService service;

    @BeforeEach
    void setUp() {
        service = new GlucoseService(recordMapper, profileMapper, alertService, auditService);
    }

    @Test
    void xlsxIsAValidMinimalWorkbookWithExpectedCellsAndFormulaGuard() throws Exception {
        GlucoseRecord record = new GlucoseRecord();
        record.setMeasuredAt(LocalDateTime.of(2026, 10, 30, 7, 42));
        record.setGlucoseMmol(new BigDecimal("6.4"));
        record.setDataSource("CGM");
        record.setDataKind("REAL");
        record.setMeasureType(1);
        record.setRiskFlag("NONE");
        record.setNote("=SUM(A1)");
        record.setIngestMethod("MANUAL");
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        byte[] workbook = service.exportXlsx(201L, "CGM",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        Map<String, String> entries = unzip(workbook);
        assertTrue(entries.containsKey("[Content_Types].xml"));
        assertTrue(entries.containsKey("_rels/.rels"));
        assertTrue(entries.containsKey("xl/workbook.xml"));
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"));
        assertTrue(entries.get("xl/workbook.xml").contains("血糖记录"));

        String sheet = entries.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("测量时间"));
        assertTrue(sheet.contains("血糖值(mmol/L)"));
        assertTrue(sheet.contains("2026-10-30 07:42:00"));
        assertTrue(sheet.contains(">6.4<"));
        assertTrue(sheet.contains("&apos;=SUM(A1)") || sheet.contains("'=SUM(A1)"), "公式样式文本必须以单引号前缀写入");
        assertFalse(sheet.contains("<f>"), "导出不应写入可执行公式节点");
    }

    @Test
    void workbookCanBeWrittenForSpreadsheetRoundTrip() throws Exception {
        GlucoseRecord record = new GlucoseRecord();
        record.setMeasuredAt(LocalDateTime.of(2026, 10, 30, 7, 42));
        record.setGlucoseMmol(new BigDecimal("6.4"));
        record.setDataSource("CGM");
        record.setDataKind("REAL");
        record.setMeasureType(1);
        record.setRiskFlag("NONE");
        record.setNote("晨起");
        record.setIngestMethod("MANUAL");
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        byte[] workbook = service.exportXlsx(201L, "CGM",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Path output = Path.of("target", "xlsx-export-test.xlsx");
        Files.write(output, workbook);
        assertTrue(Files.size(output) > 500);
    }

    @Test
    void xlsxRejectsInvertedDateWindowAndOversizedRange() {
        var error = assertThrows(com.glucose.common.exception.BusinessException.class,
                () -> service.exportXlsx(201L, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)));
        assertEquals(com.glucose.common.api.ErrorCode.PARAM_ERROR, error.getErrorCode());

        var tooLong = assertThrows(com.glucose.common.exception.BusinessException.class,
                () -> service.exportXlsx(201L, null, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31)));
        assertEquals(com.glucose.common.api.ErrorCode.PARAM_ERROR, tooLong.getErrorCode());
    }

    private Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
