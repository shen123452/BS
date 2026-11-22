package com.glucose;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.alert.service.AlertService;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.config.AppProperties;
import com.glucose.glucose.entity.*;
import com.glucose.glucose.mapper.*;
import com.glucose.glucose.service.CgmImportService;
import com.glucose.knowledge.entity.*;
import com.glucose.knowledge.mapper.*;
import com.glucose.knowledge.service.EmbeddingService;
import com.glucose.patient.controller.AppProfileController;
import com.glucose.patient.entity.*;
import com.glucose.patient.mapper.*;
import com.glucose.qa.service.RetrievalService;
import com.glucose.security.LoginUser;
import com.glucose.system.mapper.SysUserMapper;
import com.glucose.system.service.AuditService;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataConsistencyTest {
    @BeforeAll static void metadata() {
        for (Class<?> entity : List.of(KbDocument.class, KbChunk.class, GlucoseImportBatch.class, PatientProfile.class, GlucoseTargetRule.class)) {
            var helper = new MapperBuilderAssistant(new MybatisConfiguration(), "data"); helper.setCurrentNamespace("test." + entity.getSimpleName()); TableInfoHelper.initTableInfo(helper, entity);
        }
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void retrievalExcludesOldIndexOldDocumentAndMalformedVector() {
        var docs = mock(KbDocumentMapper.class); var chunks = mock(KbChunkMapper.class); var embed = mock(EmbeddingService.class);
        var doc = new KbDocument(); doc.setId(1L); doc.setTitle("current"); doc.setDocumentVersion(3); doc.setActiveIndexVersion("active");
        when(docs.selectList(any())).thenReturn(List.of(doc));
        when(embed.available()).thenReturn(true); when(embed.model()).thenReturn("model"); when(embed.embed(anyString())).thenReturn(new float[]{1, 0});
        List<KbChunk> candidates = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var c = new KbChunk(); c.setId((long) i); c.setDocId(1L); c.setIndexVersion(i == 0 ? "old" : "active"); c.setDocumentVersion(i == 1 ? 2 : 3);
            c.setDim(2); c.setEmbeddingModel("model"); c.setEmbedding(i == 2 ? new byte[3] : EmbeddingService.toBytes(new float[]{1, 0})); c.setContent(i == 3 ? "current evidence" : "stale evidence"); candidates.add(c);
        }
        when(chunks.selectList(any())).thenReturn(candidates);
        var result = new RetrievalService(docs, chunks, embed, new AppProperties()).retrieve("test");
        assertEquals(1, result.hits().size()); assertEquals("current evidence", result.hits().get(0).content()); assertEquals(3, result.hits().get(0).documentVersion());
    }
    @Test void invalidImportRowsAreReportedTogetherInsteadOfAbortingPreview(@TempDir Path temp) {
        var batches = mock(GlucoseImportBatchMapper.class); var props = new AppProperties(); props.getImportCfg().setTempDir(temp.toString());
        when(batches.insert(any(GlucoseImportBatch.class))).thenAnswer(inv -> { GlucoseImportBatch b = inv.getArgument(0); b.setId(1L); return 1; });
        var service = new CgmImportService(batches, mock(GlucoseRecordMapper.class), mock(PatientProfileMapper.class), mock(AlertService.class), mock(AuditService.class), props, new ObjectMapper());
        String csv = "measured_at,glucose_mmol\n2026-01-01 08:00:00,100\n2099-01-01 08:00:00,6.0\n2026-02-30 08:00:00,6.0\n";
        var file = new MockMultipartFile("file", "test.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        var result = service.preview(new LoginUser(201L, "patient", "PATIENT", "REAL", 0L, "jti"), file, "req");
        assertEquals(3, result.errorRows()); assertEquals(0, result.validRows()); assertEquals(3, result.errors().size());
    }
    private PatientProfile profile() { var p = new PatientProfile(); p.setId(1L); p.setUserId(201L); p.setTargetRevision(1L); p.setCgmTargetLow(new BigDecimal("3.9")); p.setCgmTargetHigh(new BigDecimal("10.0")); return p; }
    @Test void privacyOnlySubmissionIsPersistedAndStaleTargetVersionRejected() {
        var mapper = mock(PatientProfileMapper.class); var p = profile(); when(mapper.requireLock(201)).thenReturn(p); when(mapper.selectOne(any())).thenReturn(p);
        var rules = mock(GlucoseTargetRuleMapper.class); when(rules.selectList(any())).thenReturn(List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(201L,"patient","PATIENT","REAL",0L,"jti"), "token", List.of()));
        var controller = new AppProfileController(mock(SysUserMapper.class), mapper, rules, mock(AuditService.class), null, null, null);
        var req = new AppProfileController.TargetsReq(); req.setTargetRevision(1L); req.setPrivacyVersion("privacy-v1");
        controller.updateTargets(req);
        assertEquals("privacy-v1", p.getPrivacyVersion()); assertNotNull(p.getPrivacyAcceptedAt()); verify(mapper).updateById(p); assertEquals(2L, p.getTargetRevision());
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> controller.updateTargets(req)).getErrorCode());
    }
    @Test void targetRequestValidatesNestedTypesAndLimits() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var r = new AppProfileController.TargetRuleReq(); r.setMeasureType(7); r.setBasis("x".repeat(201));
            var req = new AppProfileController.TargetsReq(); req.setRules(List.of(r));
            assertTrue(factory.getValidator().validate(req).size() >= 2);
        }
    }
    @Test void targetPrecisionIsRejectedBeforeAnyWrite() {
        var mapper = mock(PatientProfileMapper.class); when(mapper.requireLock(201)).thenReturn(profile());
        var rules = mock(GlucoseTargetRuleMapper.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(201L,"patient","PATIENT","REAL",0L,"jti"), "token", List.of()));
        var controller = new AppProfileController(mock(SysUserMapper.class), mapper, rules, mock(AuditService.class), null, null, null);
        var req = new AppProfileController.TargetsReq(); req.setTargetRevision(1L); req.setCgmTargetLow(new BigDecimal("3.94")); req.setCgmTargetHigh(new BigDecimal("10.0"));
        assertEquals(ErrorCode.PARAM_ERROR, assertThrows(BusinessException.class, () -> controller.updateTargets(req)).getErrorCode());
        verify(mapper, never()).updateById(any(PatientProfile.class)); verifyNoInteractions(rules);
    }
}
