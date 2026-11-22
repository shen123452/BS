package com.glucose.patient.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.config.AppProperties;
import com.glucose.glucose.entity.GlucoseImportBatch;
import com.glucose.glucose.mapper.GlucoseImportBatchMapper;
import com.glucose.patient.mapper.PatientCleanupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PatientCleanupService {
    private final PatientCleanupMapper cleanup;
    private final GlucoseImportBatchMapper batches;
    private final AppProperties props;
    private final com.glucose.patient.mapper.PatientProfileMapper profiles;

    @Transactional
    public void deleteData(long patientId) {
        profiles.requireLock(patientId);
        var batchIds = batches.selectList(Wrappers.<GlucoseImportBatch>lambdaQuery().eq(GlucoseImportBatch::getPatientId, patientId))
                .stream().map(GlucoseImportBatch::getId).toList();
        cleanup.messages(patientId); cleanup.sessions(patientId); cleanup.records(patientId); cleanup.batches(patientId);
        cleanup.alerts(patientId); cleanup.rules(patientId); cleanup.targets(patientId);
        cleanup.medications(patientId); cleanup.medicationPlans(patientId); cleanup.diets(patientId); cleanup.profile(patientId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                Path root = Paths.get(props.getImportCfg().getTempDir()).toAbsolutePath().normalize();
                for (Long id : batchIds) for (String suffix : new String[]{".csv", ".errors.json"}) {
                    try { Files.deleteIfExists(root.resolve("batch-" + id + suffix)); }
                    catch (Exception e) { log.warn("import file cleanup failed for batch {}", id); }
                }
            }
        });
    }
}
