package com.america.backend.chat;

import com.america.ai.v1.*;
import com.america.backend.ai.AiDtos.SourceDto;
import com.america.backend.ai.AiLog;
import com.america.backend.ai.AiLogRepository;
import com.america.backend.ai.grpc.*;
import com.america.backend.store.StoreService;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Model calls run outside transactions; the answer is committed before sending SSE done. */
@Service
public class PolicyConversationStream {
    private final PolicyConversationService service;
    private final StoreService stores;
    private final AiAssistantClient client;
    private final AiLogRepository logs;
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();
    public PolicyConversationStream(PolicyConversationService service,StoreService stores,AiAssistantClient client,AiLogRepository logs) {
        this.service=service; this.stores=stores; this.client=client; this.logs=logs;
    }
    private String key(Long userId,Long id,UUID requestId) { return userId+":"+id+":"+requestId; }
    public SseEmitter ask(Long userId,Long id,UUID requestId,String question) {
        var turn=service.begin(userId,id,requestId,question);
        SseEmitter emitter=new SseEmitter(client.deadline().plusSeconds(5).toMillis());
        if (turn.replay()!=null) {
            var answer=turn.replay();
            try {
                emitter.send(SseEmitter.event().name("delta").data(Map.of("text",answer.content())));
                emitter.send(SseEmitter.event().name("sources").data(answer.sources()));
                emitter.send(SseEmitter.event().name(answer.status().equals("complete") ? "done" : "error")
                    .data(Map.of("code",answer.errorCode()==null ? "CHAT_INTERRUPTED" : answer.errorCode())));
                emitter.complete();
            } catch (Exception e) { emitter.completeWithError(e); }
            return emitter;
        }
        Job job=new Job(userId,requestId,turn,emitter);
        jobs.put(key(userId,id,requestId),job);
        emitter.onCompletion(job::stop);
        emitter.onTimeout(job::stop);
        emitter.onError(e -> job.stop());
        try {
            var store=stores.requireOwned(userId,turn.storeId());
            var context=StoreContext.newBuilder().setStoreId(store.getId().toString()).setName(store.getName())
                .setCategory(store.getCategory()).setRegion(store.getRegion()).setAddress(store.getAddress()==null?"":store.getAddress()).build();
            client.streamPolicy(PolicyQuestion.newBuilder().setStore(context).setQuestion(turn.request().contextualQuestion()).setTopK(3).build(),job);
        } catch (RuntimeException error) { job.onError(error); }
        return emitter;
    }
    public void stop(Long userId,Long id,UUID requestId) {
        service.verify(userId,id);
        Job job=jobs.get(key(userId,id,requestId));
        if (job!=null) job.stop();
        service.stop(userId,id,requestId);
    }
    private class Job implements ClientResponseObserver<PolicyQuestion,PolicyAnswerChunk> {
        final Long userId; final UUID requestId; final PolicyConversationService.Turn turn; final SseEmitter emitter;
        final StringBuilder text=new StringBuilder(); final long started=System.nanoTime();
        List<SourceDto> sources=List.of(); ClientCallStreamObserver<PolicyQuestion> call; boolean ended;
        Job(Long userId,UUID requestId,PolicyConversationService.Turn turn,SseEmitter emitter) { this.userId=userId;this.requestId=requestId;this.turn=turn;this.emitter=emitter; }
        @Override public synchronized void beforeStart(ClientCallStreamObserver<PolicyQuestion> stream) {
            call=stream; if (ended) call.cancel("대화 중지",null);
        }
        @Override public synchronized void onNext(PolicyAnswerChunk chunk) {
            if (ended) return;
            if (text.length()+chunk.getDelta().length()>100000) { end("error","AI_INVALID_REQUEST"); return; }
            text.append(chunk.getDelta());
            if (chunk.getSourcesCount()>0) sources=chunk.getSourcesList().stream().map(s -> new SourceDto(s.getTitle(),s.getUrl(),s.getSnippet(),s.getScore())).toList();
            try {
                if (!chunk.getDelta().isEmpty()) emitter.send(SseEmitter.event().name("delta").data(Map.of("text",chunk.getDelta())));
                if (chunk.getSourcesCount()>0) emitter.send(SseEmitter.event().name("sources").data(sources));
            } catch (Exception error) { stop(); }
        }
        @Override public synchronized void onError(Throwable error) { if (!ended) end("error",GrpcErrors.toApiException(AssistantType.POLICY,error).getCode()); }
        @Override public synchronized void onCompleted() { if (!ended) end(text.toString().isBlank()?"error":"complete",text.toString().isBlank()?"AI_EMPTY_RESULT":null); }
        synchronized void stop() { if (!ended) end("stopped","CLIENT_CANCELLED"); }
        private void end(String status,String error) {
            ended=true;
            try {
                boolean saved=service.finish(userId,turn.conversationId(),turn.assistantId(),text.toString(),sources,status,error);
                if (!saved) { status="stopped"; error="CHAT_NOT_FOUND"; }
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(PolicyConversationStream.class).error("정책 대화 저장 실패",failure);
                status="error"; error="CHAT_SAVE_FAILED";
            }
            jobs.remove(key(userId,turn.conversationId(),requestId),this);
            if (!status.equals("complete") && call!=null) call.cancel("대화 종료",null);
            try { logs.save(new AiLog(userId,turn.storeId(),AssistantType.POLICY,"chat",status.equals("complete"),error,(System.nanoTime()-started)/1000000)); }
            catch (RuntimeException ignored) { /* Chat persistence has already succeeded or reported an error. */ }
            try {
                emitter.send(SseEmitter.event().name(status.equals("complete")?"done":"error").data(Map.of("code",error==null?"":error)));
            } catch (Exception ignored) { /* Disconnected clients retrieve the saved answer on their next visit. */ }
            emitter.complete();
        }
    }
}
