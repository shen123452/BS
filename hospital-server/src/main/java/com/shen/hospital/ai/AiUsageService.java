package com.shen.hospital.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shen.hospital.ai.domain.AiCallLog;
import com.shen.hospital.ai.mapper.AiCallLogMapper;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.common.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 完整版阶段三：AI 调用观测。成功率分母只含已终结调用；
 * usage 缺失单独统计，不把 NULL 当零消耗。
 */
@Service
public class AiUsageService {
    private static final Set<String> PURPOSES = Set.of("PRECONSULT", "AGENT", "EMBED_QUERY", "EMBED_DOCUMENT");
    private static final List<String> STATUSES = List.of("RUNNING", "SUCCEEDED", "FAILED", "TIMEOUT", "CANCELLED");
    private static final int SCAN_LIMIT = 20000;
    private final AiCallLogMapper logs;
    private final JdbcTemplate jdbc;

    public AiUsageService(AiCallLogMapper logs, @Qualifier("mysqlDataSource") javax.sql.DataSource datasource) {
        this.logs = logs;
        this.jdbc = new JdbcTemplate(datasource);
        this.jdbc.setQueryTimeout(10);
    }

    public record PurposeSummary(String purpose, long total, Map<String, Long> byStatus, long terminated,
                                 long succeeded, Double successRate, long tokenSum, long usageAvailable,
                                 Double usageMissingRate, Long durationP50Ms, Long durationP95Ms,
                                 Long firstTokenP50Ms, Long firstTokenP95Ms) {}
    public record Summary(String startDate, String endDate, long total, List<PurposeSummary> purposes) {}
    public record LogRow(String id, String traceId, Long runId, String purpose, String modelName, String status,
                         Long inputTokens, Long outputTokens, Long totalTokens, String usageSource,
                         Long durationMs, Long firstTokenLatencyMs, String errorCode, LocalDateTime startedAt) {}

    public Summary summary(LocalDate startDate, LocalDate endDate) {
        var range = range(startDate, endDate);
        List<AiCallLog> rows = jdbc.query(
                "SELECT * FROM ai_call_log WHERE started_at>=? AND started_at<? ORDER BY id LIMIT " + SCAN_LIMIT,
                (rs, i) -> { var row = new AiCallLog(); row.setId(rs.getLong("id")); row.setPurpose(rs.getString("purpose"));
                    row.setStatus(rs.getString("status")); row.setTotalTokens((Long) nullable(rs, "total_tokens"));
                    row.setUsageSource(rs.getString("usage_source")); row.setDurationMs((Long) nullable(rs, "duration_ms"));
                    row.setFirstTokenLatencyMs((Long) nullable(rs, "first_token_latency_ms")); return row; },
                range[0], range[1]);
        var grouped = rows.stream().collect(Collectors.groupingBy(AiCallLog::getPurpose));
        var purposes = grouped.entrySet().stream().map(e -> summarize(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(PurposeSummary::purpose)).toList();
        return new Summary(startDate.toString(), endDate.toString(), rows.size(), purposes);
    }

    public PageResult<LogRow> logs(LocalDate startDate, LocalDate endDate, String purpose, String status, long page, long size) {
        var range = range(startDate, endDate);
        var query = new LambdaQueryWrapper<AiCallLog>()
                .ge(AiCallLog::getStartedAt, range[0]).lt(AiCallLog::getStartedAt, range[1])
                .eq(purpose != null && !purpose.isBlank(), AiCallLog::getPurpose, purpose)
                .eq(status != null && !status.isBlank(), AiCallLog::getStatus, status)
                .orderByDesc(AiCallLog::getId);
        return PageResult.from(logs.selectPage(new Page<>(page, size), query), this::row);
    }

    private PurposeSummary summarize(String purpose, List<AiCallLog> rows) {
        var byStatus = new LinkedHashMap<String, Long>();
        for (String status : STATUSES) byStatus.put(status, rows.stream().filter(r -> status.equals(r.getStatus())).count());
        long terminated = rows.size() - byStatus.get("RUNNING");
        long succeeded = byStatus.get("SUCCEEDED");
        var durations = rows.stream().map(AiCallLog::getDurationMs).filter(Objects::nonNull).sorted().toList();
        var firstTokens = rows.stream().map(AiCallLog::getFirstTokenLatencyMs).filter(Objects::nonNull).sorted().toList();
        long usageAvailable = rows.stream().filter(r -> "PROVIDER".equals(r.getUsageSource())).count();
        long tokenSum = rows.stream().filter(r -> "PROVIDER".equals(r.getUsageSource()))
                .mapToLong(r -> r.getTotalTokens() == null ? 0 : r.getTotalTokens()).sum();
        return new PurposeSummary(purpose, rows.size(), byStatus, terminated, succeeded,
                terminated == 0 ? null : round(succeeded * 100.0 / terminated), tokenSum, usageAvailable,
                rows.isEmpty() ? null : round((rows.size() - usageAvailable) * 100.0 / rows.size()),
                percentile(durations, 50), percentile(durations, 95),
                percentile(firstTokens, 50), percentile(firstTokens, 95));
    }

    private static Double round(double value) { return java.math.BigDecimal.valueOf(value).setScale(1, java.math.RoundingMode.HALF_UP).doubleValue(); }

    private static Long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return null;
        int index = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private LocalDateTime[] range(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate) || startDate.plusDays(93).isBefore(endDate))
            throw BusinessException.invalid("日期范围无效，且最长 93 天");
        return new LocalDateTime[]{startDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toLocalDateTime(),
                endDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toLocalDateTime()};
    }

    private LogRow row(AiCallLog log) {
        return new LogRow(log.getId().toString(), log.getTraceId(), log.getRunId(), log.getPurpose(), log.getModelName(),
                log.getStatus(), log.getInputTokens(), log.getOutputTokens(), log.getTotalTokens(), log.getUsageSource(),
                log.getDurationMs(), log.getFirstTokenLatencyMs(), log.getErrorCode(), log.getStartedAt());
    }

    private static Object nullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
