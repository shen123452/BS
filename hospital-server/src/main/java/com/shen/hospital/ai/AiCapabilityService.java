package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.shen.hospital.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiCapabilityService {
    private final ModelFactory models;
    private final HospitalAiService ai;
    private final ObjectMapper json;
    private final Semaphore gate = new Semaphore(1);

    public AiCapabilityService(ModelFactory models, HospitalAiService ai, ObjectMapper json) {
        this.models = models;
        this.ai = ai;
        this.json = json;
    }

    public record Check(String capability, String status, long durationMs, String detail) {
    }

    public record Report(boolean configured, String chatModel, String embeddingModel, List<Check> checks) {
    }

    public Map<String, Object> config() {
        return Map.of("configured", models.configured(), "baseUrl", models.config().baseUrl(),
                "chatModel", models.config().chatModel(), "embeddingModel", models.config().embeddingModel(),
                "embeddingDimension", models.config().embeddingDimension());
    }

    public Report probe(String traceId) {
        models.requireConfigured();
        if (!gate.tryAcquire()) throw new BusinessException(HttpStatus.CONFLICT, "AI_PROBE_BUSY", "能力验证正在执行");
        try {
            var checks = new ArrayList<Check>();
            checks.add(check("chat", () -> {
                var response = ai.chat("这是接口连通性测试，请只回复：连接成功", traceId);
                require(response.aiMessage().text() != null && !response.aiMessage().text().isBlank());
                return "已收到非空模型回答";
            }));
            checks.add(check("stream", () -> {
                var chunks = new AtomicInteger();
                var response = ai.streamChat("这是流式接口测试，请用一句话介绍医院导诊，不要给医学建议。", traceId,
                        text -> {
                            if (!text.isEmpty()) chunks.incrementAndGet();
                        }).join();
                require(chunks.get() > 0 && response.aiMessage().text() != null && !response.aiMessage().text().isBlank());
                return "实际接收 " + chunks.get() + " 次非空流式回调";
            }));
            checks.add(check("embedding", () -> {
                float[] vector = ai.embed("医院门诊科室导诊知识", traceId);
                return "已收到 " + vector.length + " 维有限非零向量";
            }));
            checks.add(check("tools", () -> toolProbe(traceId)));
            return new Report(true, models.config().chatModel(), models.config().embeddingModel(), checks);
        } finally {
            gate.release();
        }
    }

    private String toolProbe(String traceId) {
        var tool = ToolSpecification.builder().name("capability_echo").description("接口能力验证工具，回显输入")
                .parameters(JsonObjectSchema.builder().addStringProperty("text").required("text").build()).build();
        var request = ChatRequest.builder().messages(UserMessage.from("请调用 capability_echo，text 为 ping，然后根据工具返回结果回复。"))
                .toolSpecifications(tool).toolChoice(ToolChoice.REQUIRED).maxOutputTokens(128).build();
        var response = ai.chatWithTools(request, "AGENT", traceId);
        var calls = response.aiMessage().toolExecutionRequests();
        require(calls != null && calls.size() == 1);
        var call = calls.getFirst();
        require("capability_echo".equals(call.name()) && call.id() != null && !call.id().isBlank());
        try {
            require("ping".equals(json.readTree(call.arguments()).path("text").asText()));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Invalid tool arguments");
        }
        var followUp = ai.chatWithTools(ChatRequest.builder()
                .messages(request.messages().getFirst(), response.aiMessage(),
                        ToolExecutionResultMessage.from(call, "{\"echo\":\"ping\"}")).maxOutputTokens(128).build(), "AGENT", traceId);
        require(followUp.aiMessage().text() != null && !followUp.aiMessage().text().isBlank());
        return "工具名称、调用ID和参数有效，工具结果回传后收到最终回答";
    }

    private Check check(String name, Supplier<String> action) {
        long start = System.nanoTime();
        try {
            String detail = action.get();
            return new Check(name, "PASSED", (System.nanoTime() - start) / 1_000_000, detail);
        } catch (RuntimeException ex) {
            return new Check(name, "FAILED", (System.nanoTime() - start) / 1_000_000,
                    "调用或响应校验失败，请核对本地域模型权限、接口路径、网络或额度");
        }
    }

    private void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Model capability response invalid");
    }
}
