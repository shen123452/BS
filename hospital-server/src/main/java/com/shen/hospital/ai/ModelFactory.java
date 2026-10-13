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

@Component
@EnableConfigurationProperties(AiProperties.class)
public class ModelFactory {
    private final AiProperties config;
    private OpenAiChatModel chatModel;
    private OpenAiStreamingChatModel streamingModel;
    private OpenAiEmbeddingModel embeddingModel;

    public ModelFactory(AiProperties config) {
        this.config = config;
    }

    public boolean configured() {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }

    public AiProperties config() {
        return config;
    }

    public synchronized OpenAiChatModel chat() {
        requireConfigured();
        if (chatModel != null) return chatModel;
        chatModel = OpenAiChatModel.builder().baseUrl(config.baseUrl()).apiKey(config.apiKey())
                .modelName(config.chatModel()).timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxTokens(1024).maxRetries(0).logRequests(false).logResponses(false).build();
        return chatModel;
    }

    public synchronized OpenAiStreamingChatModel stream() {
        requireConfigured();
        if (streamingModel != null) return streamingModel;
        streamingModel = OpenAiStreamingChatModel.builder().baseUrl(config.baseUrl()).apiKey(config.apiKey())
                .modelName(config.chatModel()).timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxTokens(1024).logRequests(false).logResponses(false).build();
        return streamingModel;
    }

    public synchronized OpenAiEmbeddingModel embedding() {
        requireConfigured();
        if (embeddingModel != null) return embeddingModel;
        embeddingModel = OpenAiEmbeddingModel.builder().baseUrl(config.baseUrl()).apiKey(config.apiKey())
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
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "请先配置百炼 DASHSCOPE_API_KEY");
    }
}
