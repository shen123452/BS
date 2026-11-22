package com.glucose.analysis.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.analysis.algorithm.GlucoseStatistics;
import com.glucose.common.enums.Codes;
import com.glucose.common.exception.BusinessException;
import com.glucose.common.api.ErrorCode;
import com.glucose.config.AppProperties;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.patient.entity.GlucoseTargetRule;
import com.glucose.patient.entity.PatientProfile;
import com.glucose.patient.mapper.GlucoseTargetRuleMapper;
import com.glucose.patient.mapper.PatientProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 分析服务:同一短只读事务取数据/目标/版本快照,事务外纯计算;
 * 缓存键包含患者/来源/性质/区间/数据版本/目标版本/算法版本。
 */
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final GlucoseRecordMapper recordMapper;
    private final PatientProfileMapper profileMapper;
    private final GlucoseTargetRuleMapper targetRuleMapper;
    private final com.glucose.system.mapper.SysUserMapper sysUserMapper;
    private final StringRedisTemplate redis;
    private final AppProperties props;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final AnalysisSnapshotReader snapshotReader;

    public record AnalysisQuery(LocalDate startDate, LocalDate endDate, String dataSource, String dataKind) {
    }

    public GlucoseStatistics.Summary summary(long patientId, AnalysisQuery q) {
        String dataSource = resolveSource(q.dataSource());
        String dataKind = resolveKind(patientId, q.dataKind());
        LocalDate[] window = window(q.startDate(), q.endDate());
        String cacheKey = ("analysis:s:" + patientId + ":" + dataSource + ":" + dataKind + ":"
                + window[0] + ":" + window[1] + ":v" + algorithmCacheVersion(patientId));
        String cached = null;
        try { cached = redis.opsForValue().get(cacheKey); } catch (Exception ignored) { }
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, GlucoseStatistics.Summary.class);
            } catch (Exception ignored) {
            }
        }
        AnalysisSnapshotReader.Snapshot snap = snapshotReader.read(patientId, dataSource, dataKind, window[0], window[1]);
        cacheKey = "analysis:s:" + patientId + ":" + dataSource + ":" + dataKind + ":" + window[0] + ":" + window[1]
                + ":v" + snap.dataRevision() + ":" + snap.targetRevision() + ":" + Codes.ALGORITHM_VERSION;
        GlucoseStatistics.Summary summary = GlucoseStatistics.summarize(dataSource, dataKind, snap.points(),
                window[0], window[1], snap.cgmLow(), snap.cgmHigh(), snap.rules(),
                snap.dataRevision(), snap.targetRevision());
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(summary),
                    Duration.ofSeconds(props.getAnalysis().getCacheTtlSeconds()));
        } catch (Exception ignored) {
        }
        return summary;
    }

    public GlucoseStatistics.Trend trend(long patientId, AnalysisQuery q) {
        String dataSource = resolveSource(q.dataSource());
        String dataKind = resolveKind(patientId, q.dataKind());
        LocalDate[] window = window(q.startDate(), q.endDate());
        AnalysisSnapshotReader.Snapshot snap = snapshotReader.read(patientId, dataSource, dataKind, window[0], window[1]);
        return GlucoseStatistics.trend(dataSource, dataKind, snap.points(), 2000);
    }

    public List<GlucoseStatistics.DailyStat> daily(long patientId, AnalysisQuery q) {
        String dataSource = resolveSource(q.dataSource());
        String dataKind = resolveKind(patientId, q.dataKind());
        LocalDate[] window = window(q.startDate(), q.endDate());
        AnalysisSnapshotReader.Snapshot snap = snapshotReader.read(patientId, dataSource, dataKind, window[0], window[1]);
        return GlucoseStatistics.daily(snap.points(), dataSource, window[0], window[1]);
    }

    public com.glucose.analysis.algorithm.AgpStatistics.Profile agp(long patientId, AnalysisQuery q) {
        String source = resolveSource(q.dataSource());
        String kind = resolveKind(patientId, q.dataKind());
        LocalDate[] window = window(q.startDate(), q.endDate());
        var snap = snapshotReader.read(patientId, source, kind, window[0], window[1]);
        var summary = GlucoseStatistics.summarize(source, kind, snap.points(), window[0], window[1],
                snap.cgmLow(), snap.cgmHigh(), snap.rules(), snap.dataRevision(), snap.targetRevision());
        return com.glucose.analysis.algorithm.AgpStatistics.calculate(summary, snap.points());
    }

    /* ================= helpers ================= */

    /** 汇总窗口 [start,end):end 不晚于今日;start 必须早于 end。 */
    private static LocalDate[] window(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        if (start == null) {
            start = today.minusDays(14);
        }
        if (end == null) {
            end = today;
        }
        if (end.isAfter(today)) {
            end = today;
        }
        if (!start.isBefore(end)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "startDate 必须早于 endDate(窗口为 [start,end))");
        }
        if (Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() > 366) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "查询窗口最大 366 天");
        }
        return new LocalDate[]{start, end};
    }

    private static String resolveSource(String source) {
        if (source == null || source.isBlank()) {
            return Codes.SOURCE_CGM;
        }
        if (!Codes.SOURCE_SMBG.equals(source) && !Codes.SOURCE_CGM.equals(source)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "dataSource 仅支持 SMBG 或 CGM");
        }
        return source;
    }

    /** DEMO 仅演示账号可用;普通患者固定 REAL。 */
    private String resolveKind(long patientId, String kind) {
        if (kind == null || kind.isBlank()) return isDemoAccount(patientId) ? Codes.KIND_DEMO : Codes.KIND_REAL;
        if (Codes.KIND_REAL.equals(kind)) {
            return Codes.KIND_REAL;
        }
        if (Codes.KIND_DEMO.equals(kind)) {
            if (isDemoAccount(patientId)) {
                return Codes.KIND_DEMO;
            }
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅演示账号可查询演示数据");
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "dataKind 仅支持 REAL 或 DEMO");
    }

    private boolean isDemoAccount(long patientId) {
        var user = sysUserMapper.selectById(patientId);
        return user != null && Codes.KIND_DEMO.equals(user.getDataMode());
    }

    /** 缓存键版本:随数据版本与目标版本变化,使旧缓存立即失效。 */
    private String algorithmCacheVersion(long patientId) {
        PatientProfile profile = profileMapper.selectOne(Wrappers.<PatientProfile>lambdaQuery()
                .eq(PatientProfile::getUserId, patientId));
        long dataRev = profile == null ? 0 : profile.getDataRevision();
        long targetRev = profile == null ? 1 : profile.getTargetRevision();
        return dataRev + ":" + targetRev + ":" + Codes.ALGORITHM_VERSION;
    }

}
