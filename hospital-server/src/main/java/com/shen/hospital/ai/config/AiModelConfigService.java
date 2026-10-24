package com.shen.hospital.ai.config;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shen.hospital.common.BusinessException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 完整版阶段二：聊天与向量分离的模型配置修订。
 * 有效配置 = 同类型唯一 ENABLED 配置的当前修订；无配置时回退环境属性 + ai_provider_credential。
 * 修订发布后不可变；测试通过才能激活；激活只切指针，下一次新请求生效。
 */
@Service
public class AiModelConfigService {
    public static final String CHAT = "CHAT";
    public static final String EMBEDDING = "EMBEDDING";

    private final AiModelConfigMapper configs;
    private final AiModelConfigRevisionMapper revisions;
    private final Semaphore testGate = new Semaphore(1);

    public AiModelConfigService(AiModelConfigMapper configs, AiModelConfigRevisionMapper revisions) {
        this.configs = configs; this.revisions = revisions;
    }

    /** 当前生效修订参数；同类型无启用配置时返回 null（调用方回退环境属性）。 */
    public ActiveRevision resolveActive(String modelType) {
        var config = configs.enabledByType(modelType);
        if (config == null || config.getCurrentRevisionId() == null) return null;
        var revision = revisions.selectById(config.getCurrentRevisionId());
        if (revision == null) return null;
        return new ActiveRevision(config.getId(), revision.getId(), revision.getRevision(), revision.getBaseUrl(),
                revision.getModelName(), revision.getApiKey(), revision.getTemperature(), revision.getMaxOutputTokens(),
                revision.getTimeoutMs(), revision.getEmbeddingDimension());
    }

    public record ActiveRevision(Long configId, Long revisionId, int revision, String baseUrl, String modelName,
                                 String apiKey, java.math.BigDecimal temperature, Integer maxOutputTokens,
                                 Integer timeoutMs, Integer embeddingDimension) {}

    public List<AiModelConfig> list() { return configs.all(); }

    public List<AiModelConfigRevision> revisionsOf(Long configId) {
        owned(configId);
        return revisions.byConfig(configId);
    }

    public record SaveRequest(String name, String baseUrl, String modelName, String apiKey,
                              Integer timeoutMs, java.math.BigDecimal temperature, Integer maxOutputTokens,
                              Integer embeddingDimension) {}
    public record RevisionMeta(Long id, int revision, String baseUrl, String modelName, boolean apiKeySet,
                               java.math.BigDecimal temperature, Integer maxOutputTokens, Integer timeoutMs,
                               Integer embeddingDimension, String testStatus, LocalDateTime testedAt,
                               String testDetail, LocalDateTime createdAt) {}

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public AiModelConfig create(String modelType, SaveRequest request, Long userId) {
        if (!CHAT.equals(modelType) && !EMBEDDING.equals(modelType)) throw BusinessException.invalid("模型类型不合法");
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        requireText(request.modelName(), 100, "模型名");
        String key = validateKey(request.apiKey());
        if (EMBEDDING.equals(modelType) && (request.embeddingDimension() == null
                || request.embeddingDimension() < 1 || request.embeddingDimension() > 16000))
            throw BusinessException.invalid("向量模型必须填写实测维度（1～16000）");
        var config = new AiModelConfig();
        config.setCode(modelType + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        config.setName(requireText(request.name(), 100, "配置名"));
        config.setModelType(modelType); config.setStatus("DISABLED");
        configs.insert(config);
        insertRevision(config, 1, baseUrl, key, request, userId);
        return config;
    }

    /** 修改生成新修订：不提交 apiKey 时沿用最近一次修订的密钥，不会误清。 */
    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public RevisionMeta newRevision(Long configId, SaveRequest request, Long userId) {
        var config = owned(configId);
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        requireText(request.modelName(), 100, "模型名");
        if (EMBEDDING.equals(config.getModelType()) && (request.embeddingDimension() == null
                || request.embeddingDimension() < 1 || request.embeddingDimension() > 16000))
            throw BusinessException.invalid("向量模型必须填写实测维度（1～16000）");
        int next = revisions.byConfig(configId).stream().mapToInt(AiModelConfigRevision::getRevision).max().orElse(0) + 1;
        String key = request.apiKey() == null || request.apiKey().isBlank()
                ? latestKey(configId) : validateKey(request.apiKey());
        return meta(insertRevision(config, next, baseUrl, key, request, userId));
    }

    /** 对指定修订做真实供应商能力测试；聊天测 chat/stream/tools，向量测维度与有限性。 */
    public RevisionMeta test(Long configId, Long revisionId, String traceId) {
        var config = owned(configId);
        var revision = revisionId == null ? latest(configId)
                : Optional.ofNullable(revisions.selectById(revisionId)).filter(r -> r.getConfigId().equals(configId))
                        .orElseThrow(() -> BusinessException.missing("目标修订"));
        if (!testGate.tryAcquire()) throw new BusinessException(HttpStatus.CONFLICT, "AI_PROBE_BUSY", "能力验证正在执行");
        try {
            int timeoutMs = Math.min(revision.getTimeoutMs(), 20000);
            if (CHAT.equals(config.getModelType())) revisionTestChat(revision, timeoutMs);
            else revisionTestEmbedding(revision, timeoutMs);
            markTested(revision, "PASSED", "已通过全部能力检查");
        } catch (BusinessException ex) {
            markTested(revision, "FAILED", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            markTested(revision, "FAILED", "调用或响应校验失败，请核对接口路径、模型权限、网络或额度");
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "AI_TEST_FAILED", "修订能力测试未通过，详见测试说明");
        } finally {
            testGate.release();
        }
        return meta(revisions.selectById(revision.getId()));
    }

    /** 激活已通过测试的修订：同类型唯一启用，切换当前指针；下一次新请求生效。 */
    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public AiModelConfig activate(Long configId, Long revisionId) {
        var config = owned(configId);
        var revision = revisions.selectById(revisionId);
        if (revision == null || !revision.getConfigId().equals(configId)) throw BusinessException.missing("目标修订");
        if (!"PASSED".equals(revision.getTestStatus()))
            throw new BusinessException(HttpStatus.CONFLICT, "REVISION_NOT_TESTED", "修订尚未通过能力测试，不能启用为默认");
        configs.disableOthers(config.getModelType(), configId);
        var update = new LambdaUpdateWrapper<AiModelConfig>().eq(AiModelConfig::getId, configId)
                .set(AiModelConfig::getCurrentRevisionId, revisionId).set(AiModelConfig::getStatus, "ENABLED");
        configs.update(null, update);
        return configs.selectById(configId);
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public AiModelConfig disable(Long configId) {
        owned(configId);
        var update = new LambdaUpdateWrapper<AiModelConfig>().eq(AiModelConfig::getId, configId)
                .set(AiModelConfig::getStatus, "DISABLED");
        configs.update(null, update);
        return configs.selectById(configId);
    }

    // —— 修订能力测试：一次性客户端，不经过 ModelFactory 的当前生效配置 ——

    private void revisionTestChat(AiModelConfigRevision revision, int timeoutMs) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        var key = requireKey(revision);
        var chat = OpenAiChatModel.builder().baseUrl(revision.getBaseUrl()).apiKey(key).modelName(revision.getModelName())
                .timeout(timeout).maxRetries(0).logRequests(false).logResponses(false).build();
        ChatResponse response = chat.chat(UserMessage.from("这是接口连通性测试，请只回复：连接成功"));
        require(response.aiMessage().text() != null && !response.aiMessage().text().isBlank());
        var streaming = OpenAiStreamingChatModel.builder().baseUrl(revision.getBaseUrl()).apiKey(key)
                .modelName(revision.getModelName()).timeout(timeout).logRequests(false).logResponses(false).build();
        var chunks = new AtomicInteger();
        var streamed = new java.util.concurrent.CompletableFuture<ChatResponse>();
        streaming.chat(ChatRequest.builder().messages(UserMessage.from("这是流式接口测试，请用一句话介绍医院导诊，不要给医学建议。"))
                .maxOutputTokens(256).build(),
                new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                    public void onPartialResponse(String text) { if (text != null && !text.isEmpty()) chunks.incrementAndGet(); }
                    public void onCompleteResponse(ChatResponse value) { streamed.complete(value); }
                    public void onError(Throwable error) { streamed.completeExceptionally(error); }
                });
        var streamResult = streamed.join();
        require(chunks.get() > 0 && streamResult.aiMessage().text() != null && !streamResult.aiMessage().text().isBlank());
        var tool = ToolSpecification.builder().name("capability_echo").description("接口能力验证工具，回显输入")
                .parameters(JsonObjectSchema.builder().addStringProperty("text").required("text").build()).build();
        var toolRequest = ChatRequest.builder().messages(UserMessage.from("请调用 capability_echo，text 为 ping。"))
                .toolSpecifications(tool).toolChoice(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED)
                .maxOutputTokens(128).build();
        var toolResponse = chat.chat(toolRequest);
        var calls = toolResponse.aiMessage().toolExecutionRequests();
        require(calls != null && calls.size() == 1 && "capability_echo".equals(calls.getFirst().name()));
    }

    private void revisionTestEmbedding(AiModelConfigRevision revision, int timeoutMs) {
        var model = OpenAiEmbeddingModel.builder().baseUrl(revision.getBaseUrl()).apiKey(requireKey(revision))
                .modelName(revision.getModelName()).dimensions(revision.getEmbeddingDimension())
                .timeout(Duration.ofMillis(timeoutMs)).maxRetries(0).logRequests(false).logResponses(false).build();
        float[] vector = model.embed("医院门诊科室导诊知识").content().vector();
        require(vector != null && vector.length == revision.getEmbeddingDimension());
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite embedding");
            norm += (double) value * value;
        }
        require(norm > 0);
    }

    // —— 内部工具 ——

    private AiModelConfigRevision insertRevision(AiModelConfig config, int number, String baseUrl, String key,
                                                 SaveRequest request, Long userId) {
        if (request.timeoutMs() != null && (request.timeoutMs() < 1000 || request.timeoutMs() > 120000))
            throw BusinessException.invalid("超时须为1000～120000毫秒");
        if (request.temperature() != null && (request.temperature().signum() < 0
                || request.temperature().compareTo(new java.math.BigDecimal("2.00")) > 0))
            throw BusinessException.invalid("temperature 须在 0～2 之间");
        if (request.maxOutputTokens() != null && (request.maxOutputTokens() < 64 || request.maxOutputTokens() > 8192))
            throw BusinessException.invalid("输出上限须为 64～8192");
        var revision = new AiModelConfigRevision();
        revision.setConfigId(config.getId()); revision.setRevision(number);
        revision.setProvider("BAILIAN"); revision.setBaseUrl(baseUrl);
        revision.setModelName(request.modelName().strip()); revision.setApiKey(key);
        revision.setTemperature(request.temperature()); revision.setMaxOutputTokens(request.maxOutputTokens());
        revision.setTimeoutMs(request.timeoutMs() == null ? 30000 : request.timeoutMs());
        revision.setEmbeddingDimension(request.embeddingDimension());
        revision.setTestStatus("UNTESTED"); revision.setCreatedBy(userId);
        revisions.insert(revision);
        return revision;
    }

    private void markTested(AiModelConfigRevision revision, String status, String detail) {
        var update = new LambdaUpdateWrapper<AiModelConfigRevision>().eq(AiModelConfigRevision::getId, revision.getId())
                .set(AiModelConfigRevision::getTestStatus, status)
                .set(AiModelConfigRevision::getTestedAt, LocalDateTime.now())
                .set(AiModelConfigRevision::getTestDetail, detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        revisions.update(null, update);
    }

    private AiModelConfig owned(Long id) {
        var config = configs.selectById(id);
        if (config == null) throw BusinessException.missing("模型配置");
        return config;
    }

    private AiModelConfigRevision latest(Long configId) {
        return revisions.byConfig(configId).stream().findFirst().orElseThrow(() -> BusinessException.missing("修订"));
    }

    private String latestKey(Long configId) {
        return revisions.byConfig(configId).stream().map(AiModelConfigRevision::getApiKey)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private RevisionMeta meta(AiModelConfigRevision revision) {
        return new RevisionMeta(revision.getId(), revision.getRevision(), revision.getBaseUrl(), revision.getModelName(),
                revision.getApiKey() != null && !revision.getApiKey().isBlank(), revision.getTemperature(),
                revision.getMaxOutputTokens(), revision.getTimeoutMs(), revision.getEmbeddingDimension(),
                revision.getTestStatus(), revision.getTestedAt(), revision.getTestDetail(), revision.getCreatedAt());
    }

    private static String normalizeBaseUrl(String value) {
        String url = value == null ? "" : value.strip().replaceAll("/+$", "");
        if (url.length() < 8 || url.length() > 500 || !url.startsWith("http")) throw BusinessException.invalid("baseUrl 须为 http(s) 接口根路径");
        return url;
    }

    /** 与 ai_provider_credential 相同的密钥规则：8～512 位非空格可见 ASCII。 */
    static String validateKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip();
        if (value.length() < 8 || value.length() > 512 || value.chars().anyMatch(c -> c < 33 || c > 126))
            throw BusinessException.invalid("API Key 须为 8～512 位非空格英文字符，请检查粘贴内容");
        return value;
    }

    private static String requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.strip().length() > max) throw BusinessException.invalid(field + "必填且不超过" + max + "字");
        return value.strip();
    }

    private static String requireKey(AiModelConfigRevision revision) {
        if (revision.getApiKey() == null || revision.getApiKey().isBlank())
            throw new BusinessException(HttpStatus.CONFLICT, "AI_KEY_MISSING", "该修订未保存 API Key，请在修订中一并填写");
        return revision.getApiKey();
    }

    private static void require(boolean valid) { if (!valid) throw new IllegalStateException("Model capability response invalid"); }
}
