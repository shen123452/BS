package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.*;
import com.shen.hospital.common.BusinessException;
import com.sun.net.httpserver.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCapabilityTest {
    HttpServer server;
    ExecutorService executor;
    ModelFactory models;
    AiCallRecorder recorder;
    HospitalAiService ai;
    AiCapabilityService capabilities;
    List<JsonNode> requests = new CopyOnWriteArrayList<>();
    ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", this::chat);
        server.createContext("/v1/embeddings", this::embedding);
        server.start();
        models = new ModelFactory(new AiProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", "qwen-plus", "text-embedding-v4", 3, 2));
        recorder = mock(AiCallRecorder.class);
        when(recorder.start(anyString(), anyString(), anyString())).thenAnswer(inv -> new AiCallRecorder.Call(1, System.nanoTime()));
        ai = new HospitalAiService(models, recorder);
        capabilities = new AiCapabilityService(models, ai, json);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void validatesFourCapabilitiesAndActualStreamingProtocol() {
        var report = capabilities.probe("test-trace");
        assertEquals(4, report.checks().size());
        for (var check : report.checks())
            assertEquals("PASSED", check.status(), check.capability() + ": " + check.detail());
        assertEquals(5, requests.size());
        verify(recorder, times(5)).start(anyString(), anyString(), eq("test-trace"));
        verify(recorder, times(5)).finish(any(), eq("SUCCEEDED"), any(), nullable(Long.class));
        assertTrue(requests.stream().anyMatch(n -> n.path("tool_choice").asText().equals("required")));
        assertTrue(requests.stream().anyMatch(n -> n.path("stream").asBoolean()));
    }

    @Test
    void missingConfigurationNeverStartsPaidCall() {
        var missing = new ModelFactory(new AiProperties("https://example.invalid/v1", "", "qwen-plus", "text-embedding-v4", 1024, 30));
        assertFalse(missing.configured());
        assertThrows(BusinessException.class, missing::chat);
        assertThrows(BusinessException.class, () -> new AiCapabilityService(missing, ai, json).probe("trace"));
        verifyNoInteractions(recorder);
        assertFalse(missing.fingerprint().contains("test-key"));
    }

    @Test
    void embeddingDimensionMismatchIsFailure() {
        var badModels = new ModelFactory(new AiProperties(models.config().baseUrl(), "test-key", "qwen-plus", "text-embedding-v4", 4, 2));
        assertThrows(IllegalStateException.class, () -> new HospitalAiService(badModels, recorder).embed("test", "trace"));
        verify(recorder).finish(any(), eq("FAILED"), isNull(), isNull());
    }

    @Test
    void streamTimesOutAndDoesNotPublishLateCallback() throws Exception {
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(3500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            try {
                send(exchange, "{\"choices\":[]}", "application/json");
            } catch (java.io.IOException ignored) {
            }
        });
        assertThrows(CompletionException.class, () -> ai.streamChat("test", "trace", text -> fail("late delta")).join());
        Thread.sleep(100);
        verify(recorder).finish(any(), eq("TIMEOUT"), isNull(), isNull());
    }

    private void chat(HttpExchange exchange) throws java.io.IOException {
        var body = json.readTree(exchange.getRequestBody());
        requests.add(body);
        if (body.path("stream").asBoolean()) {
            send(exchange, "data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"连接\"}}]}\n\n"
                    + "data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"成功\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n", "text/event-stream");
        } else if (body.has("tools")) {
            send(exchange, "{\"id\":\"chat-test\",\"object\":\"chat.completion\",\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_test\",\"type\":\"function\",\"function\":{\"name\":\"capability_echo\",\"arguments\":\"{\\\"text\\\":\\\"ping\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}", "application/json");
        } else {
            send(exchange, "{\"id\":\"chat-test\",\"object\":\"chat.completion\",\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"连接成功 ping\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}", "application/json");
        }
    }

    private void embedding(HttpExchange exchange) throws java.io.IOException {
        requests.add(json.readTree(exchange.getRequestBody()));
        send(exchange, "{\"object\":\"list\",\"model\":\"text-embedding-v4\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1.0,0.0,0.0]}],\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}", "application/json");
    }

    private void send(HttpExchange exchange, String body, String contentType) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
