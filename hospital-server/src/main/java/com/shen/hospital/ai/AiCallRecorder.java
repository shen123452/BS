package com.shen.hospital.ai;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shen.hospital.ai.domain.AiCallLog;
import com.shen.hospital.ai.mapper.AiCallLogMapper;
import dev.langchain4j.model.output.TokenUsage;

import java.time.*;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AiCallRecorder {
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private record Context(Long runId, Long stepId) {}
    public static <T> T withContext(Long runId, Long stepId, Supplier<T> action) {
        Context previous = CONTEXT.get(); CONTEXT.set(new Context(runId, stepId));
        try { return action.get(); } finally { if (previous == null) CONTEXT.remove(); else CONTEXT.set(previous); }
    }
    public record Call(long id, long startedNanos) {
    }

    private final AiCallLogMapper logs;
    private final ModelFactory models;

    public AiCallRecorder(AiCallLogMapper logs, ModelFactory models) {
        this.logs = logs;
        this.models = models;
    }

    @Transactional(transactionManager = "mysqlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Call start(String purpose, String model, String traceId) {
        var row = new AiCallLog();
        row.setTraceId(traceId == null ? UUID.randomUUID().toString() : traceId);
        row.setRequestId(UUID.randomUUID().toString());
        Context context = CONTEXT.get();
        if (context != null) { row.setRunId(context.runId()); row.setStepId(context.stepId()); }
        row.setPurpose(purpose);
        row.setModelName(model);
        row.setConfigFingerprint(models.fingerprint());
        row.setStatus("RUNNING");
        row.setUsageSource("UNAVAILABLE");
        row.setStartedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        logs.insert(row);
        return new Call(row.getId(), System.nanoTime());
    }

    @Transactional(transactionManager = "mysqlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finish(Call call, String status, TokenUsage usage, Long firstTokenLatency) {
        var update = new LambdaUpdateWrapper<AiCallLog>().eq(AiCallLog::getId, call.id()).eq(AiCallLog::getStatus, "RUNNING")
                .set(AiCallLog::getStatus, status)
                .set(AiCallLog::getFinishedAt, LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                .set(AiCallLog::getDurationMs, (System.nanoTime() - call.startedNanos()) / 1_000_000)
                .set(AiCallLog::getFirstTokenLatencyMs, firstTokenLatency);
        boolean hasUsage = usage != null && (usage.inputTokenCount() != null || usage.outputTokenCount() != null || usage.totalTokenCount() != null);
        update.set(AiCallLog::getUsageSource, hasUsage ? "PROVIDER" : "UNAVAILABLE");
        if (hasUsage) update.set(AiCallLog::getInputTokens, usage.inputTokenCount())
                .set(AiCallLog::getOutputTokens, usage.outputTokenCount()).set(AiCallLog::getTotalTokens, usage.totalTokenCount());
        if (!"SUCCEEDED".equals(status))
            update.set(AiCallLog::getErrorCode, "TIMEOUT".equals(status) ? "AI_TIMEOUT" : "AI_CALL_FAILED")
                    .set(AiCallLog::getErrorMessage, "模型调用失败，核对服务配置、模型可用性或网络");
        logs.update(null, update);
    }
}
