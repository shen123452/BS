package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shen.hospital.ai.config.*;
import com.shen.hospital.common.BusinessException;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 完整版阶段二：模型配置修订的创建/测试/激活与生效切换。真实 MySQL，供应商用本地 HTTP 替身。 */
@SpringBootTest(properties={"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long",
        "hospital.bootstrap.enabled=false","hospital.ai.api-key=test-only-env-key"})
@EnabledIfEnvironmentVariable(named="MYSQL_PASSWORD",matches=".+")
class AiModelConfigIntegrationTest {
    @Autowired AiModelConfigService service;
    @Autowired AiModelConfigMapper configs;
    @Autowired AiModelConfigRevisionMapper revisions;
    @Autowired ModelFactory models;
    @Autowired @Qualifier("mysqlDataSource") DataSource datasource;
    ObjectMapper json = new ObjectMapper();
    HttpServer server;
    ExecutorService executor;
    JdbcTemplate jdbc;
    String baseUrl;
    List<Long> created = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        jdbc = new JdbcTemplate(datasource);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", this::chat);
        server.createContext("/v1/embeddings", this::embedding);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
    @AfterEach void stop() {
        for (Long id : created) {
            jdbc.update("DELETE FROM ai_model_config_revision WHERE config_id=?", id);
            jdbc.update("DELETE FROM ai_model_config WHERE id=?", id);
        }
        created.clear();
        server.stop(0); executor.shutdownNow();
    }

    Long createChat(String name) {
        var config = service.create(AiModelConfigService.CHAT, new AiModelConfigService.SaveRequest(
                name, baseUrl, "stub-chat-model", "stub-key-123456", 5000, null, 256, null), 1L);
        created.add(config.getId());
        return config.getId();
    }
    Long createEmbedding(String name) {
        var config = service.create(AiModelConfigService.EMBEDDING, new AiModelConfigService.SaveRequest(
                name, baseUrl, "stub-embed-model", "stub-key-123456", 5000, null, null, 3), 1L);
        created.add(config.getId());
        return config.getId();
    }
    int latestRevision(Long configId) {
        return revisions.byConfig(configId).getFirst().getRevision();
    }

    @Test void testActivateSwitchesEffectiveConfigAndFallsBackOnDisable() {
        assertEquals("ENV", models.config().source());
        Long id = createChat("聊天配置阶段二测试");
        // 未测试的修订不能激活
        var untested = assertThrows(BusinessException.class,
                () -> service.activate(id, revisions.byConfig(id).getFirst().getId()));
        assertEquals("REVISION_NOT_TESTED", untested.code());
        var revision = service.test(id, null, "phase2-test");
        assertEquals("PASSED", revision.testStatus());
        service.activate(id, revision.id());
        var active = service.resolveActive(AiModelConfigService.CHAT);
        assertEquals(baseUrl, active.baseUrl());
        assertEquals("stub-chat-model", active.modelName());
        var effective = models.config();
        assertEquals("DATABASE", effective.source());
        assertEquals(baseUrl, effective.baseUrl());
        assertEquals("stub-chat-model", effective.chatModel());
        // 修订后未测试修订仍为当前生效；新修订保持旧密钥
        var next = service.newRevision(id, new AiModelConfigService.SaveRequest("修订二", baseUrl,
                "stub-chat-model-2", "", 6000, null, 128, null), 1L);
        assertEquals(2, next.revision());
        assertTrue(next.apiKeySet());
        assertEquals("UNTESTED", next.testStatus());
        assertEquals("stub-chat-model", service.resolveActive(AiModelConfigService.CHAT).modelName());
        // 停用后回退环境配置
        service.disable(id);
        assertEquals("ENV", models.config().source());
    }

    @Test void sameTypeActivationsAreMutuallyExclusive() {
        Long first = createChat("互斥测试一");
        service.activate(first, service.test(first, null, "phase2-test").id());
        Long second = createChat("互斥测试二");
        service.activate(second, service.test(second, null, "phase2-test").id());
        assertEquals("DISABLED", configs.selectById(first).getStatus());
        assertEquals("ENABLED", configs.selectById(second).getStatus());
        assertEquals(second, service.resolveActive(AiModelConfigService.CHAT).configId());
    }

    @Test void failedTestMarksRevisionAndNeverActivates() {
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            send(exchange, "{\"error\":{\"message\":\"stub rejected\"}}", "application/json");
        });
        Long id = createChat("失败测试配置");
        int revisionNo = latestRevision(id);
        assertThrows(BusinessException.class, () -> service.test(id, null, "phase2-test"));
        assertEquals("FAILED", revisions.byNumber(id, revisionNo).getTestStatus());
        var denied = assertThrows(BusinessException.class,
                () -> service.activate(id, revisions.byNumber(id, revisionNo).getId()));
        assertEquals("REVISION_NOT_TESTED", denied.code());
        assertNull(service.resolveActive(AiModelConfigService.CHAT));
    }

    @Test void embeddingRevisionUsesDeclaredDimensionAndChangesSpaceInputs() {
        Long id = createEmbedding("向量配置阶段二测试");
        var revision = service.test(id, null, "phase2-test");
        assertEquals("PASSED", revision.testStatus());
        service.activate(id, revision.id());
        var active = service.resolveActive(AiModelConfigService.EMBEDDING);
        assertEquals(3, active.embeddingDimension());
        assertEquals(3, models.config().embeddingDimension());
        // 生效向量模型名进入指纹，空间标识输入随之变化
        String spaceBefore = models.fingerprint();
        var next = service.newRevision(id, new AiModelConfigService.SaveRequest("向量修订二", baseUrl,
                "stub-embed-model-2", "", 5000, null, null, 3), 1L);
        service.activate(id, service.test(id, next.id(), "phase2-test").id());
        assertNotEquals(spaceBefore, models.fingerprint());
        assertEquals("stub-embed-model-2", models.config().embeddingModel());
    }

    private void chat(HttpExchange exchange) throws java.io.IOException {
        var body = json.readTree(exchange.getRequestBody());
        if (body.path("stream").asBoolean()) {
            send(exchange, "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"model\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"连接\"}}]}\n\n"
                    + "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"model\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"成功\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n", "text/event-stream");
        } else if (body.has("tools")) {
            send(exchange, "{\"id\":\"c\",\"object\":\"chat.completion\",\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"capability_echo\",\"arguments\":\"{\\\"text\\\":\\\"ping\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}", "application/json");
        } else {
            send(exchange, "{\"id\":\"c\",\"object\":\"chat.completion\",\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"连接成功\"},\"finish_reason\":\"stop\"}]}", "application/json");
        }
    }
    private void embedding(HttpExchange exchange) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        send(exchange, "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1.0,0.0,0.0]}]}", "application/json");
    }
    private void send(HttpExchange exchange, String body, String contentType) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
