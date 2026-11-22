package com.glucose.knowledge.service;

import com.glucose.config.AppProperties;
import com.glucose.common.http.ExternalCalls;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding 网关:优先外部 API(OpenAI 兼容 /v1/embeddings);
 * float32 小端写入 BLOB,入库校验 dim 与 BLOB 长度一致、数值有限、范数非零。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final AppProperties props;
    private final ExternalCalls externalCalls;

    public boolean available() {
        return props.getEmbedding().configured();
    }

    public String model() {
        return props.getEmbedding().getModel();
    }

    public int dim() {
        return props.getEmbedding().getDim();
    }

    /** 返回向量;调用方负责校验维度。 */
    public float[] embed(String text) {
        return externalCalls.execute(() -> doEmbed(text));
    }

    private float[] doEmbed(String text) {
        if (!available()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Embedding 服务未配置");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = externalCalls.postJson(props.getEmbedding().getBaseUrl(), "/embeddings", props.getEmbedding().getApiKey(),
                    props.getEmbedding().getConnectTimeoutMs(), props.getEmbedding().getReadTimeoutMs(),
                    Map.of("model", props.getEmbedding().getModel(), "input", text));
            if (resp == null || resp.get("data") == null) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Embedding 响应异常");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            @SuppressWarnings("unchecked")
            List<Number> vector = (List<Number>) data.get(0).get("embedding");
            float[] out = new float[vector.size()];
            if (out.length != dim()) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Embedding 维度与配置不一致");
            for (int i = 0; i < out.length; i++) {
                out[i] = vector.get(i).floatValue();
                if (!Float.isFinite(out[i])) {
                    throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Embedding 含非有限数值");
                }
            }
            double norm = 0;
            for (float v : out) {
                norm += v * v;
            }
            if (Math.sqrt(norm) < 1e-6) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Embedding 范数为零");
            }
            return out;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("embedding call failed ({})", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Embedding 服务不可用");
        }
    }

    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public static float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    public static float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0f;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }
}
