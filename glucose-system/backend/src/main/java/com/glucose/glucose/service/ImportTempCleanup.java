package com.glucose.glucose.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.config.AppProperties;
import com.glucose.glucose.entity.GlucoseImportBatch;
import com.glucose.glucose.mapper.GlucoseImportBatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.file.*;
import java.time.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTempCleanup {
    private final AppProperties props;
    private final GlucoseImportBatchMapper batches;
    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        var expired = batches.selectList(Wrappers.<GlucoseImportBatch>lambdaQuery().lt(GlucoseImportBatch::getExpiresAt, cutoff)
                .isNotNull(GlucoseImportBatch::getTempFileKey).last("LIMIT 200"));
        Path root = Paths.get(props.getImportCfg().getTempDir()).toAbsolutePath().normalize();
        for (var batch : expired) {
            batches.update(null, Wrappers.<GlucoseImportBatch>lambdaUpdate()
                    .eq(GlucoseImportBatch::getId, batch.getId()).eq(GlucoseImportBatch::getStatus, "READY").set(GlucoseImportBatch::getStatus, "EXPIRED"));
            boolean cleaned = true;
            for (String suffix : new String[]{".csv", ".errors.json"}) {
                try { Files.deleteIfExists(root.resolve("batch-" + batch.getId() + suffix)); }
                catch (Exception e) { cleaned = false; log.warn("failed to clean temporary import file, batch={}", batch.getId()); }
            }
            if (cleaned) batches.update(null, Wrappers.<GlucoseImportBatch>lambdaUpdate().eq(GlucoseImportBatch::getId, batch.getId()).set(GlucoseImportBatch::getTempFileKey, null));
        }
    }
}
