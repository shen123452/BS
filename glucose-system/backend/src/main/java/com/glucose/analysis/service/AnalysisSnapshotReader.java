package com.glucose.analysis.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.analysis.algorithm.GlucoseStatistics;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.patient.entity.*;
import com.glucose.patient.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Reads versions, targets and records in a single short MVCC snapshot. */
@Service
@RequiredArgsConstructor
public class AnalysisSnapshotReader {
    private final GlucoseRecordMapper recordMapper;
    private final PatientProfileMapper profileMapper;
    private final GlucoseTargetRuleMapper targetRuleMapper;

    public record Snapshot(List<GlucoseStatistics.Point> points, BigDecimal cgmLow, BigDecimal cgmHigh,
                            List<GlucoseStatistics.TargetRule> rules,
                            long dataRevision, long targetRevision) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Snapshot read(long patientId, String dataSource, String dataKind,
                                  LocalDate startDate, LocalDate endDate) {
        PatientProfile profile = profileMapper.selectOne(Wrappers.<PatientProfile>lambdaQuery()
                .eq(PatientProfile::getUserId, patientId));
        if (profile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "患者档案不存在");
        }
        List<GlucoseStatistics.Point> points = recordMapper.selectList(Wrappers.<GlucoseRecord>lambdaQuery()
                .eq(GlucoseRecord::getPatientId, patientId)
                .eq(GlucoseRecord::getDataSource, dataSource)
                .eq(GlucoseRecord::getDataKind, dataKind)
                .ge(GlucoseRecord::getMeasuredAt, startDate.atStartOfDay())
                .lt(GlucoseRecord::getMeasuredAt, endDate.atStartOfDay())
                .orderByAsc(GlucoseRecord::getMeasuredAt))
                .stream()
                .map(r -> new GlucoseStatistics.Point(r.getMeasuredAt(), r.getGlucoseMmol(), r.getMeasureType()))
                .toList();
        List<GlucoseStatistics.TargetRule> rules = targetRuleMapper.selectList(
                Wrappers.<GlucoseTargetRule>lambdaQuery().eq(GlucoseTargetRule::getPatientId, patientId))
                .stream()
                .map(r -> new GlucoseStatistics.TargetRule(r.getMeasureType(), r.getTargetLow(), r.getTargetHigh()))
                .toList();
        return new Snapshot(points, profile.getCgmTargetLow(), profile.getCgmTargetHigh(), rules,
                profile.getDataRevision(), profile.getTargetRevision());
    }
}
