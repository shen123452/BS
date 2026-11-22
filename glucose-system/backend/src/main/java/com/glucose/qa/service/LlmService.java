package com.glucose.qa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.config.AppProperties;
import com.glucose.common.http.ExternalCalls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大模型网关(OpenAI 兼容 chat/completions):连接 3 秒、读取 8 秒;
 * 支持同步与 SSE 流式生成;只接收匿名化摘要与问题,不发送姓名、手机号、openid。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppProperties props;
    private final ExternalCalls externalCalls;

    public boolean available() {
        return props.getLlm().configured();
    }

    public String model() {
        return props.getLlm().getModel();
    }

    public String chat(String systemPrompt, String userPrompt) {
        return externalCalls.execute(() -> doChat(systemPrompt, userPrompt));
    }

    public String chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        return externalCalls.execute(() -> doChatStream(systemPrompt, userPrompt, onDelta));
    }

    private String doChat(String systemPrompt, String userPrompt) {
        if (!available()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "大模型服务未配置");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = externalCalls.postJson(props.getLlm().getBaseUrl(), "/chat/completions", props.getLlm().getApiKey(),
                    props.getLlm().getConnectTimeoutMs(), props.getLlm().getReadTimeoutMs(), Map.of(
                            "model", props.getLlm().getModel(),
                            "temperature", 0.3,
                            "max_tokens", 800,
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userPrompt))));
            if (resp == null || resp.get("choices") == null) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型响应异常");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices.isEmpty()) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型无返回内容");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = message == null ? null : (String) message.get("content");
            if (content == null || content.isBlank()) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型返回为空");
            }
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("llm call failed ({})", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型服务不可用");
        }
    }

    private String doChatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        if (!available()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "大模型服务未配置");
        }
        StringBuilder full = new StringBuilder();
        try {
            externalCalls.postStream(props.getLlm().getBaseUrl(), "/chat/completions", props.getLlm().getApiKey(),
                    props.getLlm().getConnectTimeoutMs(), Math.max(props.getLlm().getReadTimeoutMs(), 15000), Map.of(
                            "model", props.getLlm().getModel(),
                            "temperature", 0.3,
                            "max_tokens", 800,
                            "stream", true,
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userPrompt))),
                    line -> parseStreamLine(line, full, onDelta));
            String content = full.toString();
            if (content.isBlank()) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型返回为空");
            }
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("llm stream call failed ({})", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型服务不可用");
        }
    }

    public static void parseStreamLine(String line, StringBuilder full, Consumer<String> onDelta) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":") || !trimmed.startsWith("data:")) return;
        String data = trimmed.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) return;
        try {
            JsonNode root = JSON.readTree(data);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return;
            JsonNode contentNode = choices.get(0).path("delta").path("content");
            if (contentNode.isTextual()) {
                String delta = contentNode.asText();
                if (!delta.isEmpty()) {
                    full.append(delta);
                    if (onDelta != null) onDelta.accept(delta);
                }
            }
        } catch (Exception ignored) {
            // Skip malformed non-JSON control lines gracefully.
        }
    }
}
