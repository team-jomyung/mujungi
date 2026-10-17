package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.StartSession;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentRegistry;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.gateway.DeviceConnectionRow;
import kr.mujoong.realtime.gateway.SocketRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 세션 시작 명령 처리 (U1 3.5 Step 23 · BR6.6 · BR6.5 · SCD-1 · R3-1 · R3-9 · J111 (v) · PD-1 생명주기 0 ~ 2).
 *
 * <ol>
 *   <li>0단계: 묘비가 있으면 레지스트리를 만지기 전에 409 SESSION_NOT_ACTIVE.
 *   <li>INIT 선등록. 같은 sessionId가 이미 있으면 첫 시작의 결과를 기다려 그 세션의 지금 상태로 202(BR6.6 ①).
 *   <li>행이 이미 있으면(실행기가 끝난 같은 id) 지금 상태로 202. 기기 연결이 CONNECTED가 아니거나 그 connection_id의 소켓이 살아 있지 않으면
 *       423 DEVICE_NOT_CONNECTED(②). 보호자 · 기기에 진행 중 세션이 있으면 409 SESSION_IN_PROGRESS(③).
 *   <li>한 트랜잭션: 세션 행(PREPARING, 패키지 고정) + 종료 표현 + SessionStateChanged(PREPARING)(④). 충돌 · 부분 유일 색인 위반이면
 *       id로 다시 읽어 있으면 그 상태로 202, 없으면 409.
 *   <li>커밋 뒤 실행기를 띄운다 — 실행기가 START_SESSION을 먼저 보내고 ②를 무장한다(⑤ · R3-1). 실패면 등록을 지운다.
 *   <li>U8: 실행기를 만들기 직전 판 번호 · 승인 문구 판을 {@link LiveContentRegistry}에 고정하고, 실행기 종료 정리(정산 뒤) 또는 시작 실패에서
 *       놓는다(BR2.2 · SD4). 선택 속성 approvedSpeech가 없는 옛 패키지도 그대로 받는다(NFR6.1).
 * </ol>
 */
@Component
public class StartSessionHandler {

    /** 시작 명령(POST /internal/sessions 본문, 계약 internal-session-start-request). */
    public record StartCommand(
            UUID sessionId,
            UUID idempotencyKey,
            UUID guardianId,
            UUID childId,
            UUID deviceId,
            ActivityType activityType,
            ConsentSnapshot consentSnapshot,
            ChildContext childContext,
            List<StopExpressionItem> stopExpressions,
            Map<String, ApprovedContentItem> approvedContent,
            Map<String, ApprovedSpeechItem> approvedSpeech,
            String practiceJson) {

        public StartCommand {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(guardianId, "guardianId");
            Objects.requireNonNull(childId, "childId");
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(consentSnapshot, "consentSnapshot");
            Objects.requireNonNull(childContext, "childContext");
            stopExpressions = stopExpressions == null ? List.of() : List.copyOf(stopExpressions);
            approvedContent = approvedContent == null ? Map.of() : new LinkedHashMap<>(approvedContent);
            approvedSpeech = approvedSpeech == null ? Map.of() : new LinkedHashMap<>(approvedSpeech);
        }

        /** U8 ~ U11 모양(연습 조각 없음 — practice 원문을 싣지 않는다, U12 이전). */
        public StartCommand(UUID sessionId, UUID idempotencyKey, UUID guardianId, UUID childId, UUID deviceId,
                ActivityType activityType, ConsentSnapshot consentSnapshot, ChildContext childContext,
                List<StopExpressionItem> stopExpressions, Map<String, ApprovedContentItem> approvedContent,
                Map<String, ApprovedSpeechItem> approvedSpeech) {
            this(sessionId, idempotencyKey, guardianId, childId, deviceId, activityType, consentSnapshot, childContext,
                    stopExpressions, approvedContent, approvedSpeech, null);
        }

        /** U1 ~ U5b 모양(approvedSpeech 없음 — 옛 패키지, U8 NFR6.1). */
        public StartCommand(UUID sessionId, UUID idempotencyKey, UUID guardianId, UUID childId, UUID deviceId,
                ActivityType activityType, ConsentSnapshot consentSnapshot, ChildContext childContext,
                List<StopExpressionItem> stopExpressions, Map<String, ApprovedContentItem> approvedContent) {
            this(sessionId, idempotencyKey, guardianId, childId, deviceId, activityType, consentSnapshot, childContext,
                    stopExpressions, approvedContent, Map.of(), null);
        }

        /** 아이 맥락 · 동의 · 문구 · 연습 조각 원문은 문자열 표현에 넣지 않는다(BR8.7). */
        @Override
        public String toString() {
            return "StartCommand[sessionId=" + sessionId + ", activityType=" + activityType + "]";
        }
    }

    /** 종료 표현 한 건. */
    public record StopExpressionItem(String text, boolean isDefault) {
    }

    /** 고정한 승인 문구 한 건. */
    public record ApprovedContentItem(UUID contentVersionId, String text, String audioUrl) {
    }

    /** 고정한 음성 없는 승인 발화 한 건(시작 패키지 approvedSpeech 값, U8 편차 U8-b). 글은 문자열 표현에 내지 않는다. */
    public record ApprovedSpeechItem(UUID contentVersionId, String text) {

        @Override
        public String toString() {
            return "ApprovedSpeechItem[contentVersionId=" + contentVersionId + "]";
        }
    }

    /** approvedContent와 같은 code가 approvedSpeech에도 있을 때 남기는 APP_ERROR errorKind(U8 SD4, 문구 원문 없음). */
    public static final String APPROVED_SPEECH_DUPLICATE_CODE = "APPROVED_SPEECH_DUPLICATE_CODE";

    /** 처리 결과. */
    public sealed interface StartResult {
    }

    /** 202 {status}. */
    public record Accepted(SessionStatus status) implements StartResult {
    }

    /** 거절. */
    public record Rejected(Reason reason) implements StartResult {
    }

    /** 거절 사유(오류 코드와 같은 이름). */
    public enum Reason {
        /** 409: 묘비(Core가 취소한 id의 늦은 시작). */
        SESSION_NOT_ACTIVE,
        /** 423. */
        DEVICE_NOT_CONNECTED,
        /** 409. */
        SESSION_IN_PROGRESS
    }

    private final SessionRegistry registry;
    private final TombstoneMap tombstones;
    private final ActivitySessionRepository sessions;
    private final SessionStopExpressionRepository stopExpressions;
    private final DeviceConnectionRepository connections;
    private final SocketRegistry sockets;
    private final SessionStateEmitter emitter;
    private final SessionExecutor.Services services;
    private final TransactionTemplate transactions;
    private final DbRetry dbRetry;
    private final MujoongClock clock;
    private final Duration joinWait;
    private final LiveContentRegistry liveContent;

    @Autowired
    public StartSessionHandler(
            SessionRegistry registry,
            TombstoneMap tombstones,
            ActivitySessionRepository sessions,
            SessionStopExpressionRepository stopExpressions,
            DeviceConnectionRepository connections,
            SocketRegistry sockets,
            SessionStateEmitter emitter,
            SessionExecutor.Services services,
            PlatformTransactionManager transactionManager,
            DbRetry dbRetry,
            MujoongClock clock,
            RealtimeProperties properties,
            LiveContentRegistry liveContent) {
        this.liveContent = Objects.requireNonNull(liveContent, "liveContent");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tombstones = Objects.requireNonNull(tombstones, "tombstones");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.stopExpressions = Objects.requireNonNull(stopExpressions, "stopExpressions");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.services = Objects.requireNonNull(services, "services");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.dbRetry = Objects.requireNonNull(dbRetry, "dbRetry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.joinWait = properties.limits().dbLockTimeout();
    }

    /** 시작 명령을 처리한다. DB 장애는 예외로 올린다(내부 HTTP 503). */
    public StartResult start(StartCommand command, String traceId) {
        Objects.requireNonNull(command, "command");
        if (tombstones.contains(command.sessionId())) {
            return new Rejected(Reason.SESSION_NOT_ACTIVE);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            SessionRegistry.Registration registration = registry.register(command.sessionId(),
                    () -> new SessionMailbox(services.settings().mailboxCapacity()));
            if (!registration.created()) {
                Optional<StartResult> joined = join(registration.entry(), command.sessionId());
                if (joined.isPresent()) {
                    return joined.get();
                }
                continue;
            }
            return startNew(command, traceId, registration.entry());
        }
        return new Rejected(Reason.SESSION_IN_PROGRESS);
    }

    private Optional<StartResult> join(SessionRegistry.Entry entry, UUID sessionId) {
        try {
            entry.settled().get(joinWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("시작 합류 대기가 끊겼다", e);
        } catch (ExecutionException | TimeoutException e) {
            // 첫 시작이 아직 커밋 전이면 행이 없을 수 있다. 아래 조회로 판정한다
        }
        Optional<ActivitySessionRow> row = sessions.find(sessionId);
        if (row.isPresent()) {
            return Optional.of(new Accepted(row.get().status()));
        }
        return entry.settled().isDone() ? Optional.empty() : Optional.of(new Rejected(Reason.SESSION_IN_PROGRESS));
    }

    private StartResult startNew(StartCommand command, String traceId, SessionRegistry.Entry entry) {
        boolean activated = false;
        try {
            Optional<ActivitySessionRow> existing = sessions.find(command.sessionId());
            if (existing.isPresent()) {
                return new Accepted(existing.get().status());
            }
            if (!deviceConnected(command.deviceId())) {
                return new Rejected(Reason.DEVICE_NOT_CONNECTED);
            }
            if (!sessions.findInProgress(command.guardianId(), command.deviceId()).isEmpty()) {
                return new Rejected(Reason.SESSION_IN_PROGRESS);
            }
            Optional<ActivitySessionRow> created;
            try {
                created = dbRetry.execute(DbRetry.Idempotency.IDEMPOTENT,
                        () -> transactions.execute(status -> insert(command, traceId)));
            } catch (DataIntegrityViolationException e) {
                created = Optional.empty();
            }
            if (created == null || created.isEmpty()) {
                return sessions.find(command.sessionId()).<StartResult>map(row -> new Accepted(row.status()))
                        .orElseGet(() -> new Rejected(Reason.SESSION_IN_PROGRESS));
            }
            emitter.committed(null, created.get(), SessionTransitions.Origin.REALTIME, traceId);
            // U8(BR2.2 · W1): 실행 계획을 만들 때 판 번호와 승인 문구 판을 세션 콘텐츠 계획으로 고정하고, 실행기 종료 정리(정산 뒤)에서 놓는다
            pinContent(command);
            SessionExecutor[] holder = new SessionExecutor[1];
            holder[0] = new SessionExecutor(plan(command, traceId), services, entry.mailbox(), () -> {
                try {
                    registry.remove(command.sessionId(), holder[0]);
                } finally {
                    liveContent.release(command.sessionId());
                }
            });
            SessionExecutor executor = holder[0];
            registry.activate(entry, executor);
            activated = true;
            // U4b BR4.7: 같은 기기에서 DECLINED 인사를 마치지 못한 앞 세션은 남은 인사를 버리고 바로 정리한다
            for (SessionExecutor previous : registry.executors()) {
                if (previous != executor && previous.deviceId().equals(command.deviceId())
                        && previous.status() == SessionStatus.DECLINED && !previous.isTerminated()) {
                    previous.mailbox().putSafety(new SessionMailbox.DeviceTakenOver(command.sessionId()));
                }
            }
            executor.start();
            return new Accepted(SessionStatus.PREPARING);
        } finally {
            if (!activated) {
                registry.abandon(entry);
                liveContent.release(command.sessionId());
            }
        }
    }

    /**
     * 세션 콘텐츠 계획을 고정한다(U8 W1 · BR1.2 · SD4). 같은 code가 approvedContent에도 있으면 approvedSpeech 쪽을 버리고 APP_ERROR
     * {@value #APPROVED_SPEECH_DUPLICATE_CODE}만 남긴다(문구 텍스트 · code 값은 남기지 않는다).
     */
    void pinContent(StartCommand command) {
        Map<String, UUID> contentVersions = new LinkedHashMap<>();
        command.approvedContent().forEach((code, item) -> contentVersions.put(code, item.contentVersionId()));
        Map<String, LiveContentRegistry.SpeechText> speech = new LinkedHashMap<>();
        boolean duplicate = false;
        for (Map.Entry<String, ApprovedSpeechItem> item : command.approvedSpeech().entrySet()) {
            if (command.approvedContent().containsKey(item.getKey())) {
                duplicate = true;
                continue;
            }
            speech.put(item.getKey(),
                    new LiveContentRegistry.SpeechText(item.getValue().contentVersionId(), item.getValue().text()));
        }
        if (duplicate) {
            services.events().appError(EventLog.Component.SESSION_LIFECYCLE, APPROVED_SPEECH_DUPLICATE_CODE);
        }
        liveContent.pin(command.sessionId(), contentVersions, speech);
    }

    /** ④ 한 트랜잭션. 충돌(같은 id)이면 빈 값. */
    private Optional<ActivitySessionRow> insert(StartCommand command, String traceId) {
        Instant now = clock.now();
        ApprovedContentItem closing = command.approvedContent().get(PlayApprovedContent.CLOSING);
        NewActivitySession session = new NewActivitySession(command.sessionId(), command.guardianId(),
                command.childId(), command.deviceId(), command.activityType(), command.idempotencyKey().toString(),
                command.consentSnapshot(), command.childContext(), closing == null ? null : closing.contentVersionId(),
                null);
        if (!sessions.insert(session, now)) {
            return Optional.empty();
        }
        List<SessionStopExpression> expressions = new ArrayList<>();
        int order = 0;
        for (StopExpressionItem item : command.stopExpressions()) {
            expressions.add(new SessionStopExpression(UUID.randomUUID(), command.sessionId(), item.text(),
                    item.isDefault(), order++, now));
        }
        stopExpressions.insertAll(expressions);
        ActivitySessionRow row = sessions.find(command.sessionId())
                .orElseThrow(() -> new IllegalStateException("방금 넣은 세션 행이 없다"));
        emitter.write(row, now, traceId);
        return Optional.of(row);
    }

    /** ②: 원장 행이 CONNECTED이고 그 connection_id의 소켓이 살아 있어야 한다(RD-4 · NFR4.1). */
    private boolean deviceConnected(UUID deviceId) {
        Optional<DeviceConnectionRow> row = connections.find(deviceId);
        return row.isPresent() && row.get().state() == ConnectionState.CONNECTED && row.get().connectionId() != null
                && sockets.isLive(deviceId, row.get().connectionId());
    }

    private static SessionExecutor.Plan plan(StartCommand command, String traceId) {
        List<StartSession.ApprovedAudio> audio = new ArrayList<>();
        for (Map.Entry<String, ApprovedContentItem> item : command.approvedContent().entrySet()) {
            audio.add(new StartSession.ApprovedAudio(item.getKey(), item.getValue().contentVersionId(),
                    item.getValue().audioUrl()));
        }
        // U3: 원음 기록 동의(U3-R9)와 종료 표현 스냅숏(종료 의도 판정, U3-R12)을 실행기에 넘긴다
        List<String> stops = command.stopExpressions().stream().map(StopExpressionItem::text).toList();
        // U4b: 활동 종류(시작 안내 선택 · 연습 확장점)와 승인 문구 전체(선택 코드 · 말풍선 원문)를 넘긴다
        Map<String, SessionExecutor.ApprovedItem> approved = new LinkedHashMap<>();
        for (Map.Entry<String, ApprovedContentItem> item : command.approvedContent().entrySet()) {
            approved.put(item.getKey(), new SessionExecutor.ApprovedItem(item.getKey(),
                    item.getValue().contentVersionId(), item.getValue().text()));
        }
        // U12(BR1.2 · D7): 연습이면 practice 원문을 계획에 싣고, 음성 없는 승인 발화의 문장 지도(approvedContent와 겹친 code는 제외, pinContent와 같다)를 함께 싣는다
        boolean practice = command.activityType() == ActivityType.PRACTICE;
        Map<String, String> speech = new LinkedHashMap<>();
        if (practice) {
            for (Map.Entry<String, ApprovedSpeechItem> item : command.approvedSpeech().entrySet()) {
                if (!command.approvedContent().containsKey(item.getKey())) {
                    speech.put(item.getKey(), item.getValue().text());
                }
            }
        }
        return new SessionExecutor.Plan(command.sessionId(), command.deviceId(), traceId, command.childContext(), audio,
                approved.get(PlayApprovedContent.CLOSING), approved.get(PlayApprovedContent.WAITING_FILLER),
                command.consentSnapshot().rawAudio(), stops, command.activityType(), approved,
                practice ? command.practiceJson() : null, command.guardianId(), speech);
    }
}
