package com.shen.hospital.ai;

import com.shen.hospital.ai.config.AiModelConfigService;
import com.shen.hospital.common.BusinessException;
import dev.langchain4j.model.openai.*;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 模型客户端工厂。完整版阶段二：聊天与向量分别解析生效配置——
 * 数据库存在启用的 ai_model_config 修订时优先使用，否则回退环境属性 + ai_provider_credential。
 * 客户端缓存按“参数 + 密钥”指纹失效；修订激活在下一次新请求生效，已开始的任务不受影响。
 */
@Component
@EnableConfigurationProperties(AiProperties.class)
public class ModelFactory {
    private final AiProperties config;
    private final AiApiKeyStore keys;
    private final AiModelConfigService modelConfigs;
    private String chatCacheKey;
    private OpenAiChatModel chatModel;
    private String streamCacheKey;
    private OpenAiStreamingChatModel streamingModel;
    private String embeddingCacheKey;
    private OpenAiEmbeddingModel embeddingModel;

    public ModelFactory(AiProperties config) {
        this(config, null, null);
    }

    public ModelFactory(AiProperties config, AiApiKeyStore keys) {
        this(config, keys, null);
    }

    @Autowired
    public ModelFactory(AiProperties config, AiApiKeyStore keys, AiModelConfigService modelConfigs) {
        this.config = config;
        this.keys = keys;
        this.modelConfigs = modelConfigs;
    }

    /** 生效配置视图：字段名与 AiProperties 一致，调用方无感切换数据来源。 */
    public record AiEffectiveConfig(String baseUrl, String chatModel, String embeddingModel,
                                    int embeddingDimension, int timeoutSeconds, String source,
                                    Integer chatMaxOutputTokens, Double chatTemperature) {
        public static AiEffectiveConfig from(AiProperties properties, String source) {
            return new AiEffectiveConfig(properties.baseUrl(), properties.chatModel(), properties.embeddingModel(),
                    properties.embeddingDimension(), properties.timeoutSeconds(), source, 1024, null);
        }
    }

    public boolean configured() {
        String key = chatApiKey();
        return key != null && !key.isBlank();
    }

    public AiEffectiveConfig config() {
        var chat = resolveChat();
        var embedding = resolveEmbedding();
        return new AiEffectiveConfig(chat.baseUrl(), chat.modelName(), embedding.modelName(),
                embedding.dimension(), chat.timeoutSeconds(), chat.source(), chat.maxOutputTokens(), chat.temperature());
    }

    private record Resolved(String baseUrl, String modelName, String apiKey, int timeoutSeconds,
                            Integer maxOutputTokens, Double temperature, Integer dimension, String source) {}

    private Resolved resolveChat() {
        var active = modelConfigs == null ? null : modelConfigs.resolveActive(AiModelConfigService.CHAT);
        if (active != null)
            return new Resolved(active.baseUrl(), active.modelName(), active.apiKey(),
                    active.timeoutMs() / 1000, active.maxOutputTokens(),
                    active.temperature() == null ? null : active.temperature().doubleValue(), null, "DATABASE");
        String key = keys == null ? config.apiKey() : keys.apiKey();
        return new Resolved(config.baseUrl(), config.chatModel(), key, config.timeoutSeconds(), 1024, null, null, "ENV");
    }

    private Resolved resolveEmbedding() {
        var active = modelConfigs == null ? null : modelConfigs.resolveActive(AiModelConfigService.EMBEDDING);
        if (active != null)
            return new Resolved(active.baseUrl(), active.modelName(), active.apiKey(),
                    active.timeoutMs() / 1000, null, null, active.embeddingDimension(), "DATABASE");
        String key = keys == null ? config.apiKey() : keys.apiKey();
        return new Resolved(config.baseUrl(), config.embeddingModel(), key, config.timeoutSeconds(), null, null,
                config.embeddingDimension(), "ENV");
    }

    public synchronized OpenAiChatModel chat() {
        var resolved = resolveChat();
        requireKey(resolved);
        String cacheKey = fingerprintOf(resolved.baseUrl(), resolved.modelName(), resolved.apiKey(),
                resolved.timeoutSeconds(), "chat|maxTokens=" + resolved.maxOutputTokens() + "|retries=0|langchain4j=1.0.1");
        if (chatModel == null || !Objects.equals(chatCacheKey, cacheKey)) {
            var builder = OpenAiChatModel.builder().baseUrl(resolved.baseUrl()).apiKey(resolved.apiKey())
                    .modelName(resolved.modelName()).timeout(Duration.ofSeconds(resolved.timeoutSeconds()))
                    .maxRetries(0).logRequests(false).logResponses(false);
            if (resolved.maxOutputTokens() != null) builder.maxTokens(resolved.maxOutputTokens());
            if (resolved.temperature() != null) builder.temperature(resolved.temperature());
            chatModel = builder.build();
            chatCacheKey = cacheKey;
        }
        return chatModel;
    }

    public synchronized OpenAiStreamingChatModel stream() {
        var resolved = resolveChat();
        requireKey(resolved);
        String cacheKey = fingerprintOf(resolved.baseUrl(), resolved.modelName(), resolved.apiKey(),
                resolved.timeoutSeconds(), "stream|maxTokens=" + resolved.maxOutputTokens() + "|langchain4j=1.0.1");
        if (streamingModel == null || !Objects.equals(streamCacheKey, cacheKey)) {
            var builder = OpenAiStreamingChatModel.builder().baseUrl(resolved.baseUrl()).apiKey(resolved.apiKey())
                    .modelName(resolved.modelName()).timeout(Duration.ofSeconds(resolved.timeoutSeconds()))
                    .logRequests(false).logResponses(false);
            if (resolved.maxOutputTokens() != null) builder.maxTokens(resolved.maxOutputTokens());
            streamingModel = builder.build();
            streamCacheKey = cacheKey;
        }
        return streamingModel;
    }

    public synchronized OpenAiEmbeddingModel embedding() {
        var resolved = resolveEmbedding();
        requireKey(resolved);
        String cacheKey = fingerprintOf(resolved.baseUrl(), resolved.modelName(), resolved.apiKey(),
                resolved.timeoutSeconds(), "embed|dim=" + resolved.dimension() + "|langchain4j=1.0.1");
        if (embeddingModel == null || !Objects.equals(embeddingCacheKey, cacheKey)) {
            embeddingModel = OpenAiEmbeddingModel.builder().baseUrl(resolved.baseUrl()).apiKey(resolved.apiKey())
                    .modelName(resolved.modelName()).dimensions(resolved.dimension())
                    .timeout(Duration.ofSeconds(resolved.timeoutSeconds())).maxRetries(0)
                    .logRequests(false).logResponses(false).build();
            embeddingCacheKey = cacheKey;
        }
        return embeddingModel;
    }

    /** 生效配置指纹：进入运行快照与调用日志；数据库修订或环境回退变化时随之改变。 */
    public String fingerprint() {
        var chat = resolveChat();
        var embedding = resolveEmbedding();
        return fingerprintOf(chat.baseUrl(), chat.modelName(), "[chat]" + (chat.apiKey() == null ? "" : "set") + "[embed]" + embedding.modelName()
                        + (embedding.apiKey() == null ? "" : "set"),
                chat.timeoutSeconds(), "chat=" + chat.modelName() + "|embed=" + embedding.modelName()
                        + "|dim=" + embedding.dimension() + "|maxTokens=" + chat.maxOutputTokens()
                        + "|retries=0|langchain4j=1.0.1|src=" + chat.source());
    }

    private static String fingerprintOf(String baseUrl, String modelName, String key, int timeoutSeconds, String extras) {
        String value = baseUrl + "|" + modelName + "|" + key + "|" + timeoutSeconds + "s|" + extras;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void requireConfigured() {
        if (!configured()) throw missingKey();
    }

    private void requireKey(Resolved resolved) {
        if (resolved.apiKey() == null || resolved.apiKey().isBlank()) throw missingKey();
    }

    private String chatApiKey() {
        var active = modelConfigs == null ? null : modelConfigs.resolveActive(AiModelConfigService.CHAT);
        if (active != null) return active.apiKey();
        return keys == null ? config.apiKey() : keys.apiKey();
    }

    private BusinessException missingKey() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "AI 服务尚未配置，请联系管理员在 AI 配置中保存 API Key");
    }
}
