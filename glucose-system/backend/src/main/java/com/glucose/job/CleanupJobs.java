package com.glucose.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.enums.Codes;
import com.glucose.glucose.entity.GlucoseImportBatch;
import com.glucose.glucose.mapper.GlucoseImportBatchMapper;
import com.glucose.qa.entity.QaMessage;
import com.glucose.qa.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @Scheduled 任务:超时批次过期清理、问答 PROCESSING 占位恢复。
 * 历史数据不因定时扫描再次触发实时外部提醒(P0 无外部提醒)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupJobs {

    private final GlucoseImportBatchMapper batchMapper;
    private final QaMessageMapper messageMapper;
    private final com.glucose.config.AppProperties props;

    /** 过期预检批次置 EXPIRED 并清理临时文件(每 5 分钟)。 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void expireBatches() {
        List<GlucoseImportBatch> expired = batchMapper.selectList(Wrappers.<GlucoseImportBatch>lambdaQuery()
                .eq(GlucoseImportBatch::getStatus, Codes.BATCH_READY)
                .lt(GlucoseImportBatch::getExpiresAt, LocalDateTime.now()));
        for (GlucoseImportBatch batch : expired) {
            batch.setStatus(Codes.BATCH_EXPIRED);
            batchMapper.updateById(batch);
            deleteTempFiles(batch);
        }
        if (!expired.isEmpty()) {
            log.info("expired {} import batches", expired.size());
        }
    }

    /** 进程中断留下的 PROCESSING 占位超时标 FAILED,用户重试生成新 requestId(每分钟)。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverQaPlaceholders() {
        List<QaMessage> stuck = messageMapper.selectList(Wrappers.<QaMessage>lambdaQuery()
                .eq(QaMessage::getRole, "assistant")
                .eq(QaMessage::getStatus, "PROCESSING")
                .lt(QaMessage::getCreatedAt, LocalDateTime.now().minusMinutes(3)));
        for (QaMessage message : stuck) {
            message.setStatus("FAILED");
            message.setFailureReason("TIMEOUT_RECOVERED");
            message.setDegraded(1);
            messageMapper.updateById(message);
        }
        if (!stuck.isEmpty()) {
            log.info("recovered {} stuck qa placeholders", stuck.size());
        }
    }

    /** 导入临时目录清理:删除 2 小时以上的孤儿文件(每小时)。 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void cleanImportTemp() {
        Path dir = Paths.get(props.getImportCfg().getTempDir());
        if (!Files.exists(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> {
                        try {
                            return Files.getLastModifiedTime(f).toMillis()
                                    < System.currentTimeMillis() - 7_200_000;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("import temp cleanup failed: {}", e.getMessage());
        }
    }

    private void deleteTempFiles(GlucoseImportBatch batch) {
        if (batch.getTempFileKey() == null) {
            return;
        }
        Path dir = Paths.get(props.getImportCfg().getTempDir());
        try {
            Files.deleteIfExists(dir.resolve(batch.getTempFileKey()));
            Files.deleteIfExists(dir.resolve(batch.getTempFileKey().replace(".csv", ".errors.json")));
        } catch (IOException ignored) {
        }
    }
}
