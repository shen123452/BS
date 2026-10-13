package com.shen.hospital.ai;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

@Service
public class HospitalAiService {
    private final ModelFactory models;
    private final AiCallRecorder recorder;

    public HospitalAiService(ModelFactory models, AiCallRecorder recorder) {
        this.models = models;
        this.recorder = recorder;
    }

    public void requireConfigured() {
        models.requireConfigured();
    }

    public ChatResponse chat(String input, String traceId) {
        return chatWithTools(ChatRequest.builder().messages(UserMessage.from(input)).build(), "PRECONSULT", traceId);
    }

    public ChatResponse chatWithTools(ChatRequest request, String purpose, String traceId) {
        if (!"PRECONSULT".equals(purpose) && !"AGENT".equals(purpose))
            throw new IllegalArgumentException("Invalid chat purpose");
        var model = models.chat();
        var call = recorder.start(purpose, models.config().chatModel(), traceId);
        try {
            var response = model.chat(request);
            recorder.finish(call, "SUCCEEDED", response.tokenUsage(), null);
            return response;
        } catch (RuntimeException ex) {
            recorder.finish(call, "FAILED", null, null);
            throw ex;
        }
    }

    public float[] embed(String input, String traceId) {
        return embed(input, traceId, "EMBED_QUERY");
    }

    public float[] embed(String input, String traceId, String purpose) {
        if (!"EMBED_QUERY".equals(purpose) && !"EMBED_DOCUMENT".equals(purpose)) throw new IllegalArgumentException("Invalid embedding purpose");
        var model = models.embedding();
        var call = recorder.start(purpose, models.config().embeddingModel(), traceId);
        try {
            var response = model.embed(input);
            float[] vector = response.content().vector();
            if (vector.length != models.config().embeddingDimension())
                throw new IllegalStateException("Embedding dimension mismatch");
            double norm = 0;
            for (float value : vector) {
                if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite embedding");
                norm += (double) value * value;
            }
            if (norm == 0) throw new IllegalStateException("Zero embedding");
            recorder.finish(call, "SUCCEEDED", response.tokenUsage(), null);
            return vector;
        } catch (RuntimeException ex) {
            recorder.finish(call, "FAILED", null, null);
            throw ex;
        }
    }

    public java.util.List<float[]> embedDocuments(java.util.List<String> inputs, String traceId) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 32) throw new IllegalArgumentException("Embedding batch must contain 1..32 items");
        var model = models.embedding();
        var call = recorder.start("EMBED_DOCUMENT", models.config().embeddingModel(), traceId);
        try {
            var response = model.embedAll(inputs.stream().map(TextSegment::from).toList());
            var vectors = response.content().stream().map(embedding -> embedding.vector()).toList();
            if (vectors.size() != inputs.size()) throw new IllegalStateException("Embedding batch size mismatch");
            for (float[] vector : vectors) validateEmbedding(vector);
            recorder.finish(call, "SUCCEEDED", response.tokenUsage(), null);
            return vectors;
        } catch (RuntimeException ex) {
            recorder.finish(call, "FAILED", null, null);
            throw ex;
        }
    }

    private void validateEmbedding(float[] vector) {
        if (vector == null || vector.length != models.config().embeddingDimension()) throw new IllegalStateException("Embedding dimension mismatch");
        double norm = 0;
        for (float value : vector) { if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite embedding"); norm += (double) value * value; }
        if (norm == 0 || !Double.isFinite(norm)) throw new IllegalStateException("Zero or invalid embedding");
    }

    public CompletableFuture<ChatResponse> streamChat(String input, String traceId, Consumer<String> onDelta) {
        return streamChat(ChatRequest.builder().messages(UserMessage.from(input)).build(), traceId, onDelta);
    }

    public CompletableFuture<ChatResponse> streamChat(ChatRequest request, String traceId, Consumer<String> onDelta) {
        var model = models.stream();
        var call = recorder.start("PRECONSULT", models.config().chatModel(), traceId);
        var result = new CompletableFuture<ChatResponse>();
        var ended = new AtomicBoolean(false);
        var firstToken = new AtomicLong(-1);
        var lock = new Object();
        var handler = new StreamingChatResponseHandler() {
            public void onPartialResponse(String text) {
                synchronized (lock) {
                    if (ended.get()) return;
                    firstToken.compareAndSet(-1, (System.nanoTime() - call.startedNanos()) / 1_000_000);
                    try {
                        onDelta.accept(text);
                    } catch (RuntimeException ex) {
                        onError(ex);
                    }
                }
            }

            public void onCompleteResponse(ChatResponse response) {
                synchronized (lock) {
                    if (!ended.compareAndSet(false, true)) return;
                    try {
                        recorder.finish(call, "SUCCEEDED", response.tokenUsage(), latency());
                        result.complete(response);
                    } catch (RuntimeException ex) {
                        result.completeExceptionally(ex);
                    }
                }
            }

            public void onError(Throwable error) {
                synchronized (lock) {
                    if (!ended.compareAndSet(false, true)) return;
                    try {
                        recorder.finish(call, isTimeout(error) ? "TIMEOUT" : "FAILED", null, latency());
                    } catch (RuntimeException ignored) {/* 结果仍必须结束；上层按traceId排查。 */}
                    result.completeExceptionally(error);
                }
            }

            private Long latency() {
                return firstToken.get() < 0 ? null : firstToken.get();
            }
        };
        try {
            model.chat(request, handler);
        } catch (RuntimeException ex) {
            handler.onError(ex);
        }
        result.orTimeout(models.config().timeoutSeconds(), TimeUnit.SECONDS).whenComplete((response, error) -> {
            synchronized (lock) {
                if (error != null && ended.compareAndSet(false, true)) {
                    try {
                        recorder.finish(call, result.isCancelled() ? "CANCELLED" : isTimeout(error) ? "TIMEOUT" : "FAILED", null, firstToken.get() < 0 ? null : firstToken.get());
                    } catch (RuntimeException ignored) {/* 不让日志故障复活已终结流。 */}
                }
            }
        });
        return result;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }
}
