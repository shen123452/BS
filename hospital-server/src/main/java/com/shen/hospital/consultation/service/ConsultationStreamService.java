package com.shen.hospital.consultation.service;

import com.shen.hospital.ai.HospitalAiService;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.consultation.dto.SendMessageRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ConsultationStreamService {
    private final ConsultationService consultations;
    private final HospitalAiService ai;
    private final Semaphore gate=new Semaphore(4);
    private final Map<String,LiveStream> active=new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"consultation-heartbeat");t.setDaemon(true);return t;});

    public ConsultationStreamService(ConsultationService consultations,HospitalAiService ai) { this.consultations=consultations;this.ai=ai; }

    public SseEmitter open(Long userId,Long id,SendMessageRequest request,String traceId) {
        if(!gate.tryAcquire()) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"AI_BUSY","问诊服务繁忙，请稍后再发送");
        ConsultationService.Prepared prepared;
        try { prepared=consultations.begin(userId,id,request); }
        catch(RuntimeException ex) { gate.release();throw ex; }
        var stream=new LiveStream(prepared,traceId);
        stream.emitter.onTimeout(()->stream.fail(new TimeoutException(),false));
        stream.emitter.onError(error->stream.fail(error,true));
        stream.emitter.onCompletion(()->stream.fail(new CancellationException(),true));
        try {
            stream.event("meta",Map.of("sessionId",prepared.sessionId(),"status",prepared.state()));
            if(prepared.replay()) { stream.replay();return stream.emitter; }
            active.put(prepared.operationId(),stream);
            stream.beat=heartbeat.scheduleAtFixedRate(stream::beat,15,15,TimeUnit.SECONDS);
            stream.future=ai.streamChat(prepared.request(),traceId,stream::delta);
            stream.future.whenComplete(stream::complete);
            if(stream.ended) stream.future.cancel(false);
        } catch(Exception ex) { stream.fail(ex,false); }
        return stream.emitter;
    }

    public boolean cancel(Long userId,Long id,String operation) {
        boolean changed=consultations.cancel(userId,id,operation);
        if(changed) { var stream=active.get(operation); if(stream!=null) stream.cancel(); }
        return changed;
    }

    private class LiveStream {
        final ConsultationService.Prepared prepared;
        final String traceId;
        final SseEmitter emitter=new SseEmitter(120_000L);
        volatile CompletableFuture<ChatResponse> future;
        ScheduledFuture<?> beat;
        volatile boolean ended;
        int sequence;
        LiveStream(ConsultationService.Prepared prepared,String traceId) { this.prepared=prepared;this.traceId=traceId; }

        synchronized void event(String name,Map<String,Object> values) throws IOException {
            var data=new LinkedHashMap<String,Object>(values);
            data.put("requestId",prepared.operationId());data.put("messageId",prepared.assistantMessageId());data.put("seq",sequence++);
            if(traceId!=null) data.put("traceId",traceId);
            emitter.send(SseEmitter.event().name(name).data(data));
        }
        synchronized void delta(String text) {
            if(ended || text==null || text.isEmpty()) return;
            try {
                if(!consultations.append(prepared.operationId(),prepared.assistantMessageId(),text)) { cancel();return; }
                event("delta",Map.of("text",text));
            } catch(Exception ex) { fail(ex,false); }
        }
        synchronized void beat() {
            if(ended) return;
            try { emitter.send(SseEmitter.event().comment("heartbeat")); } catch(Exception ex) { fail(ex,true); }
        }
        synchronized void replay() {
            if(ended) return;
            try { event("done",Map.of("content",prepared.replayText(),"status",prepared.state())); }
            catch(Exception ignored) {} finally { end(); }
        }
        synchronized void complete(ChatResponse response,Throwable error) {
            if(ended) return;
            if(error!=null) { fail(error,false);return; }
            try {
                String content=consultations.finishSuccess(prepared,response.aiMessage().text());
                event("done",Map.of("content",content==null?"":content,"status",content==null?"CANCELLED":"COMPLETED"));
                end();
            } catch(Exception ex) { fail(ex,false); }
        }
        synchronized void cancel() {
            if(ended) return;
            try { event("done",Map.of("status","CANCELLED","content","")); } catch(Exception ignored) {}
            end();
            if(future!=null) future.cancel(false);
        }
        synchronized void fail(Throwable error,boolean disconnected) {
            if(ended) return;
            boolean timeout=isTimeout(error);
            try { consultations.finishFailure(prepared,timeout); } catch(RuntimeException ignored) {}
            if(!disconnected) {
                try { event("error",Map.of("code",timeout?"AI_TIMEOUT":"AI_CALL_FAILED","message",timeout?"模型响应超时，请稍后再试":"回答中断，请刷新问诊记录后重试")); }
                catch(Exception ignored) {}
            }
            end();
            if(future!=null) future.cancel(false);
        }
        void end() {
            if(ended) return;
            ended=true;active.remove(prepared.operationId(),this);
            if(beat!=null) beat.cancel(false);
            gate.release();emitter.complete();
        }
    }
    static boolean isTimeout(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }
    @PreDestroy void shutdown() { active.values().forEach(stream->stream.fail(new CancellationException(),true));heartbeat.shutdownNow(); }
}
