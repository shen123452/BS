package com.shen.hospital.ai;

import com.shen.hospital.common.BusinessException;
import dev.langchain4j.model.openai.*;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Objects;

@Component
@EnableConfigurationProperties(AiProperties.class)
public class ModelFactory {
    private final AiProperties config;
    private final AiApiKeyStore keys;
    private String clientKey;
    private OpenAiChatModel chatModel;
    private OpenAiStreamingChatModel streamingModel;
    private OpenAiEmbeddingModel embeddingModel;

    public ModelFactory(AiProperties config) {
        this(config, null);
    }

    @Autowired
    public ModelFactory(AiProperties config, AiApiKeyStore keys) {
        this.config = config;
        this.keys = keys;
    }

    public boolean configured() {
        String key = apiKey();
        return key != null && !key.isBlank();
    }

    public AiProperties config() {
        return config;
    }

    public synchronized OpenAiChatModel chat() {
        String key = prepareClients();
        if (chatModel != null) return chatModel;
        chatModel = OpenAiChatModel.builder().baseUrl(config.baseUrl()).apiKey(key)
                .modelName(config.chatModel()).timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxTokens(1024).maxRetries(0).logRequests(false).logResponses(false).build();
        return chatModel;
    }

    public synchronized OpenAiStreamingChatModel stream() {
        String key = prepareClients();
        if (streamingModel != null) return streamingModel;
        streamingModel = OpenAiStreamingChatModel.builder().baseUrl(config.baseUrl()).apiKey(key)
                .modelName(config.chatModel()).timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxTokens(1024).logRequests(false).logResponses(false).build();
        return streamingModel;
    }

    public synchronized OpenAiEmbeddingModel embedding() {
        String key = prepareClients();
        if (embeddingModel != null) return embeddingModel;
        embeddingModel = OpenAiEmbeddingModel.builder().baseUrl(config.baseUrl()).apiKey(key)
                .modelName(config.embeddingModel()).dimensions(config.embeddingDimension())
                .timeout(Duration.ofSeconds(config.timeoutSeconds())).maxRetries(0)
                .logRequests(false).logResponses(false).build();
        return embeddingModel;
    }

    public String fingerprint() {
        try {
            String value = config.baseUrl() + "|" + config.chatModel() + "|" + config.embeddingModel() + "|"
                    + config.embeddingDimension() + "|" + config.timeoutSeconds() + "|maxTokens=1024|retries=0|langchain4j=1.0.1";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void requireConfigured() {
        if (!configured())
            throw missingKey();
    }

    private String apiKey() { return keys == null ? config.apiKey() : keys.apiKey(); }

    private String prepareClients() {
        String key = apiKey();
        if (key == null || key.isBlank()) throw missingKey();
        if (!Objects.equals(clientKey, key)) {
            chatModel = null; streamingModel = null; embeddingModel = null; clientKey = key;
        }
        return key;
    }

    private BusinessException missingKey() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "AI 服务尚未配置，请联系管理员在 AI 配置中保存 API Key");
    }
}
