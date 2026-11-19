package com.america.backend.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.america.ai.v1.*;
import com.america.backend.ai.AiDtos.SourceDto;
import com.america.backend.ai.AiLogRepository;
import com.america.backend.ai.grpc.AiAssistantClient;
import com.america.backend.common.ApiException;
import com.america.backend.notification.PolicyReminderService;
import com.america.backend.store.*;
import com.america.backend.user.*;
import io.grpc.stub.StreamObserver;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:policy-chats;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop", "app.jwt.secret=test-secret-test-secret-test-secret-1234",
    "ai.grpc.internal-key=test-internal-key", "ai.mock.enabled=false", "app.policy-reminder.enabled=false"
})
@ActiveProfiles("test") @AutoConfigureMockMvc
class PolicyConversationIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean PolicyConversationService service;
    @MockitoBean AiAssistantClient client;
    @MockitoBean PolicyReminderService reminders;
    @Autowired ChatConversationRepository rooms;
    @Autowired ChatMessageRepository messages;
    @Autowired StoreRepository stores;
    @Autowired UserRepository users;
    @Autowired AiLogRepository logs;
    Long userId, otherId, storeId, otherStoreId;
    @BeforeEach void setup() {
        messages.deleteAll(); rooms.deleteAll(); logs.deleteAll(); stores.deleteAll(); users.deleteAll();
        var user=users.save(new UserAccount("chat@example.com",null,"첫 사장님"));
        var other=users.save(new UserAccount("other@example.com",null,"다른 사장님"));
        userId=user.getId(); otherId=other.getId();
        storeId=stores.save(new Store(user,"커피","카페","경기 안산시","한양대학로 55")).getId();
        otherStoreId=stores.save(new Store(other,"다른 가게","카페","서울",null)).getId();
        when(client.deadline()).thenReturn(Duration.ofSeconds(90));
    }
    private Long create() { return service.create(userId,storeId).id(); }
    private void answer(Long id,String question) {
        var turn=service.begin(userId,id,UUID.randomUUID(),question);
        assertThat(service.finish(userId,id,turn.assistantId(),"저장한 답변",List.of(new SourceDto("공고","https://example.com/policy","안내",1)),"complete",null)).isTrue();
    }
    @Test void allMessagesAndSourcesSurviveFreshReadsWithNoFortyMessageTruncation() {
        Long id=create();
        for (int i=0;i<23;i++) answer(id,"질문 "+i);
        var detail=service.get(userId,id);
        assertThat(detail.messages()).hasSize(46);
        assertThat(detail.messages().get(45).sources().getFirst().url()).isEqualTo("https://example.com/policy");
        assertThat(detail.conversation().title()).isEqualTo("질문 0");
        var turn=service.begin(userId,id,UUID.randomUUID(),"후속 질문");
        assertThat(turn.request().history()).hasSize(8);
        assertThat(turn.request().contextualQuestion()).contains("질문 22","https://example.com/policy","후속 질문").doesNotContain("질문 0\n");
    }
    @Test void sixthRoomDeletesLeastRecentlyUsedRoomAndItsMessagesOnlyForThisAccount() {
        List<Long> ids=new ArrayList<>();
        for (int i=0;i<5;i++) { Long id=create(); ids.add(id); answer(id,"질문 "+i); }
        var other=service.create(otherId,otherStoreId);
        answer(ids.getFirst(),"다시 이어서 질문");
        Long sixth=create();
        assertThat(service.list(userId)).hasSize(5).extracting(PolicyConversationService.Summary::id)
            .contains(ids.getFirst(),sixth).doesNotContain(ids.get(1));
        assertThat(messages.findByConversationIdOrderById(ids.get(1))).isEmpty();
        assertThat(service.list(otherId)).extracting(PolicyConversationService.Summary::id).containsExactly(other.id());
        assertThatThrownBy(() -> service.get(userId,ids.get(1))).isInstanceOf(ApiException.class);
    }
    @Test void concurrentCreationNeverExceedsFiveAndFinishingPrunedRoomCannotRecreateIt() {
        Long original=create(); var turn=service.begin(userId,original,UUID.randomUUID(),"기존 질문");
        CompletableFuture.allOf(java.util.stream.IntStream.range(0,8).mapToObj(i -> CompletableFuture.runAsync(this::create)).toArray(CompletableFuture[]::new)).join();
        assertThat(rooms.count()).isEqualTo(5);
        assertThat(service.finish(userId,original,turn.assistantId(),"늦은 답변",List.of(),"complete",null)).isFalse();
        assertThat(rooms.count()).isEqualTo(5);
    }
    @Test void authenticationAndOwnershipCoverEveryEndpoint() throws Exception {
        Long id=create();
        mvc.perform(get("/api/me/policy/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/policy/conversations").with(jwt().jwt(j -> j.subject(otherId.toString()))))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/me/policy/conversations/"+id).with(jwt().jwt(j -> j.subject(otherId.toString())))).andExpect(status().isNotFound());
        mvc.perform(post("/api/me/policy/conversations").with(jwt().jwt(j -> j.subject(otherId.toString())))
            .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":"+storeId+"}")).andExpect(status().isNotFound());
        for (String suffix:List.of("/messages","/email")) mvc.perform(post("/api/me/policy/conversations/"+id+suffix)
            .with(jwt().jwt(j -> j.subject(otherId.toString()))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"question\":\"질문\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/me/policy/conversations/"+id+"/messages/"+UUID.randomUUID()+"/stop")
            .with(jwt().jwt(j -> j.subject(otherId.toString())))).andExpect(status().isNotFound());
        verify(client,never()).streamPolicy(any(),any()); verifyNoInteractions(reminders);
    }
    @Test void duplicateRequestReplaysButPendingDuplicateCannotCallModelAgain() {
        Long id=create(); UUID request=UUID.randomUUID();
        var turn=service.begin(userId,id,request,"질문");
        assertThatThrownBy(() -> service.begin(userId,id,request,"질문")).isInstanceOf(ApiException.class);
        service.finish(userId,id,turn.assistantId(),"답변",List.of(),"complete",null);
        assertThat(service.begin(userId,id,request,"질문").replay().content()).isEqualTo("답변");
        assertThatThrownBy(() -> service.begin(userId,id,request,"다른 내용")).isInstanceOf(ApiException.class);
        assertThat(service.get(userId,id).messages()).hasSize(2);
    }
    @Test void interruptedAndStoppedTurnsDoNotEnterModelHistoryOrGetOverwritten() {
        Long id=create(); UUID request=UUID.randomUUID(); var turn=service.begin(userId,id,request,"중지");
        service.stop(userId,id,request);
        assertThat(service.finish(userId,id,turn.assistantId(),"늦은 결과",List.of(),"complete",null)).isFalse();
        var next=service.begin(userId,id,UUID.randomUUID(),"새 질문");
        assertThat(next.request().history()).isEmpty();
        var pending=messages.findByConversationIdAndClientMessageId(id,next.assistantId()).orElseThrow();
        pending.updatedAt=Instant.now().minusSeconds(400); messages.saveAndFlush(pending);
        assertThat(service.get(userId,id).messages().getLast().errorCode()).isEqualTo("CHAT_INTERRUPTED");
        assertThat(service.begin(userId,id,UUID.randomUUID(),"재시작 이후").request().history()).isEmpty();
    }
    private String ask(Long id,UUID request) throws Exception {
        var result=mvc.perform(post("/api/me/policy/conversations/"+id+"/messages")
            .with(jwt().jwt(j -> j.subject(userId.toString()))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"clientMessageId\":\""+request+"\",\"question\":\"정책 알려줘\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test void streamCommitsAnswerBeforeDoneAndReplayDoesNotInvokeModel() throws Exception {
        doAnswer(invocation -> {
            PolicyQuestion question=invocation.getArgument(0);
            assertThat(question.getStore().getAddress()).isEqualTo("한양대학로 55");
            StreamObserver<PolicyAnswerChunk> observer=invocation.getArgument(1);
            observer.onNext(PolicyAnswerChunk.newBuilder().setDelta("실제 저장 답변").addSources(Source.newBuilder().setTitle("공식").setUrl("https://example.com/policy")).build());
            observer.onCompleted(); return null;
        }).when(client).streamPolicy(any(),any());
        Long id=create(); UUID request=UUID.randomUUID();
        assertThat(ask(id,request)).contains("event:done","실제 저장 답변");
        assertThat(service.get(userId,id).messages().getLast().status()).isEqualTo("complete");
        assertThat(ask(id,request)).contains("event:done","실제 저장 답변");
        verify(client,times(1)).streamPolicy(any(),any());
    }
    @Test void savingFailureMustNotEmitDone() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(service).finish(anyLong(),anyLong(),any(),anyString(),anyList(),anyString(),nullable(String.class));
        doAnswer(invocation -> {
            StreamObserver<PolicyAnswerChunk> observer=invocation.getArgument(1);
            observer.onNext(PolicyAnswerChunk.newBuilder().setDelta("저장 실패 답변").build()); observer.onCompleted(); return null;
        }).when(client).streamPolicy(any(),any());
        assertThat(ask(create(),UUID.randomUUID())).contains("CHAT_SAVE_FAILED","event:error").doesNotContain("event:done");
    }
    @Test void explicitStopPersistsPartialTextAndLateChunksCannotOverwriteIt() throws Exception {
        AtomicReference<StreamObserver<PolicyAnswerChunk>> observer=new AtomicReference<>();
        doAnswer(invocation -> { observer.set(invocation.getArgument(1)); return null; }).when(client).streamPolicy(any(),any());
        Long id=create(); UUID request=UUID.randomUUID();
        var pending=mvc.perform(post("/api/me/policy/conversations/"+id+"/messages").with(jwt().jwt(j -> j.subject(userId.toString())))
            .contentType(MediaType.APPLICATION_JSON).content("{\"clientMessageId\":\""+request+"\",\"question\":\"질문\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        observer.get().onNext(PolicyAnswerChunk.newBuilder().setDelta("부분 답변").build());
        mvc.perform(post("/api/me/policy/conversations/"+id+"/messages/"+request+"/stop").with(jwt().jwt(j -> j.subject(userId.toString())))).andExpect(status().isOk());
        observer.get().onNext(PolicyAnswerChunk.newBuilder().setDelta("덮어쓰면 안 됨").build()); observer.get().onCompleted();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        var saved=service.get(userId,id).messages().getLast();
        assertThat(saved.status()).isEqualTo("stopped"); assertThat(saved.content()).isEqualTo("부분 답변");
    }
    @Test void manualEmailResultIsSavedWithoutSendingTwiceForSameRequest() throws Exception {
        when(reminders.sendNow(userId,storeId)).thenReturn(new PolicyReminderService.DeliveryResult("chat@example.com","메일 검색 결과"));
        when(reminders.get(userId)).thenReturn(new PolicyReminderService.Settings(false,"chat@example.com",storeId,"커피",true,null,true));
        Long id=create(); UUID request=UUID.randomUUID();
        for (int i=0;i<2;i++) mvc.perform(post("/api/me/policy/conversations/"+id+"/email").with(jwt().jwt(j -> j.subject(userId.toString())))
            .contentType(MediaType.APPLICATION_JSON).content("{\"clientMessageId\":\""+request+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("메일 검색 결과"));
        verify(reminders,times(1)).sendNow(userId,storeId);
        assertThat(service.get(userId,id).messages()).hasSize(2);
        assertThat(service.get(userId,id).messages().getLast().content()).isEqualTo("메일 검색 결과");
    }
}
