package com.glucose.common.http;

import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared across chat, retrieval and indexing; reject excess work without a queue. */
@Component
@RequiredArgsConstructor
public class ExternalCalls {
    private final AppProperties props;
    private final AtomicInteger active = new AtomicInteger();

    public <T> T execute(Supplier<T> call) {
        int count = active.incrementAndGet();
        try {
            if (count > Math.max(1, props.getLlm().getMaxConcurrent())) throw new BusinessException(ErrorCode.RATE_LIMITED, "模型服务繁忙，请稍后重试");
            return call.get();
        } finally { active.decrementAndGet(); }
    }

    public Map<String, Object> postJson(String baseUrl, String endpoint, String apiKey, int connectMs, int readMs, Map<String, Object> body) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CallBudget.timeout(connectMs))).build();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> response = null;
        try {
            int timeout = CallBudget.timeout(readMs);
            var request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + endpoint))
                    .timeout(Duration.ofMillis(timeout)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build();
            // Wait for the entire body, including slow body streams after headers have arrived.
            response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            var result = response.get(timeout, TimeUnit.MILLISECONDS);
            if (result.statusCode() < 200 || result.statusCode() >= 300) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型服务返回异常状态");
            return new ObjectMapper().readValue(result.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型请求已中断");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型服务超时或响应无效");
        } finally {
            if (response != null && !response.isDone()) response.cancel(true);
        }
    }

    /** Stream an SSE (text/event-stream) response line by line within the remaining CallBudget. */
    public void postStream(String baseUrl, String endpoint, String apiKey, int connectMs, int readMs,
                           Map<String, Object> body, Consumer<String> onLine) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CallBudget.timeout(connectMs))).build();
        java.util.concurrent.CompletableFuture<HttpResponse<InputStream>> response = null;
        InputStream stream = null;
        try {
            int timeout = CallBudget.timeout(readMs);
            var request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + endpoint))
                    .timeout(Duration.ofMillis(timeout))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build();
            response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            var result = response.get(timeout, TimeUnit.MILLISECONDS);
            stream = result.body();
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型服务返回异常状态");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    CallBudget.timeout(readMs);
                    onLine.accept(line);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型请求已中断");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "模型流式响应超时或中断");
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) { }
            }
            if (response != null && !response.isDone()) response.cancel(true);
        }
    }
}
