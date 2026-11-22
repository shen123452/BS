package com.glucose;

import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.common.http.*;
import com.glucose.config.AppProperties;
import com.glucose.qa.service.LlmService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCallTest {
    @Test void configuredReadTimeoutActuallyStopsASlowHttpResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try { Thread.sleep(700); byte[] body = "{\"choices\":[{\"message\":{\"content\":\"test\"}}]}".getBytes(); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); }
            catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        try {
            var props = new AppProperties(); props.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); props.getLlm().setApiKey("test"); props.getLlm().setModel("test"); props.getLlm().setReadTimeoutMs(120);
            var llm = new LlmService(props, new ExternalCalls(props)); long start = System.nanoTime();
            assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, assertThrows(BusinessException.class, () -> llm.chat("test", "test")).getErrorCode());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        } finally { server.stop(0); }
    }
    @Test void deadlineAlsoCoversSlowBodyAfterHeaders() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try { exchange.sendResponseHeaders(200, 1000); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); Thread.sleep(700); }
            catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        try {
            var props = new AppProperties(); props.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); props.getLlm().setApiKey("test"); props.getLlm().setModel("test"); props.getLlm().setReadTimeoutMs(120);
            var llm = new LlmService(props, new ExternalCalls(props)); long start = System.nanoTime();
            assertThrows(BusinessException.class, () -> llm.chat("test", "test"));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 600);
        } finally { server.stop(0); }
    }
    @Test void concurrentCallsAreBoundedAndPermitIsReleasedAfterFailure() throws Exception {
        var props = new AppProperties(); props.getLlm().setMaxConcurrent(1); var calls = new ExternalCalls(props);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> calls.execute(() -> { entered.countDown(); try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return "done"; }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> calls.execute(() -> "overflow")).getErrorCode());
            release.countDown(); assertEquals("done", first.get(1, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> calls.execute(() -> { throw new IllegalStateException(); }));
            assertEquals("released", calls.execute(() -> "released"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void sequentialCallsShareDeadlineAndThreadBudgetIsCleaned() throws Exception {
        try (var ignored = CallBudget.start(Duration.ofMillis(30))) {
            assertTrue(CallBudget.timeout(8000) <= 30); Thread.sleep(45);
            assertThrows(BusinessException.class, () -> CallBudget.timeout(8000));
        }
        assertEquals(8000, CallBudget.timeout(8000));
    }
}
