package kr.mujoong.contract.log;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 로그 attrs 허용 키(OD-2 표 · OD-1 값 형식). 키마다 값 형식이 하나이고 열거형 키는 등록된 값 집합을 가진다. 등록되지 않은 열거값은
 * {@code UNKNOWN}으로 바꿔 쓴다(OD-1). 사건 코드별로 같은 키의 열거 집합이 다르면 {@link EventCode}가 덮어쓴다(from · to).
 *
 * <p>전사 · 원음 · 토큰 · 쿠키 · 등록 코드 · 보호자 입력 · 비밀 · 이메일 · 자유 문자열을 담는 키는 없다(OD-3 5번 · NFR9.8 · BR8.7).
 * UUID는 {@link #EVENT_ID} 하나만 허용한다. 문자열 값 형식 {@link ValueType#LABEL} · {@link ValueType#NAME_LIST}는 형식이 고정된 짧은
 * 식별자만 받는다(U9 SD6).
 */
public enum AttrKey {
    // SESSION_TRANSITION
    FROM("from", ValueType.ENUM, Sets.SESSION_FROM),
    TO("to", ValueType.ENUM, Sets.SESSION_TO),
    STATUS_VERSION("statusVersion", ValueType.NUMBER, null),
    END_REASON("endReason", ValueType.ENUM, Set.of(
            "GUARDIAN_STOP", "CHILD_STOP", "SILENCE", "DISCONNECT", "CONSENT_WITHDRAWN", "UNCONFIRMED", "DECLINED", "FAILED")),
    FAILURE_CODE("failureCode", ValueType.ENUM, Set.of(
            "DEVICE_NO_ACK", "SCENARIO_GEN_FAILED", "PROVIDER_UNAVAILABLE", "SERVER_RESTART")),
    CONFIRMED("confirmed", ValueType.BOOLEAN, null),
    PHASE("phase", ValueType.ENUM, Set.of("START", "RUNNING")),
    ORIGIN("origin", ValueType.ENUM, Set.of("REALTIME", "CORE_LOCAL", "CORE_ABANDONED")),
    // TIMEOUT_FIRED
    TYPE("type", ValueType.ENUM, Set.of("PREPARING", "DEVICE_ACK", "CLOSING_CONFIRM", "DISCONNECT", "DEVICE_STATUS_STALE")),
    THRESHOLD_MS("thresholdMs", ValueType.NUMBER, null),
    ELAPSED_MS("elapsedMs", ValueType.NUMBER, null),
    // LATENCY_TURN · SESSION_ITEMS_SERVED · PLAYBACK_MARK_STOPPED (OD-8)
    TURN_SEQ("turnSeq", ValueType.NUMBER, null),
    T_SPEECH_END("tSpeechEnd", ValueType.TIMESTAMP, null),
    T_SPEECH_END_SOURCE("tSpeechEndSource", ValueType.ENUM, Set.of("PROVIDER_EVENT", "INPUT_TRANSCRIPT_FINAL", "LAST_INPUT_FRAME")),
    T_PROVIDER_FIRST_OUT("tProviderFirstOut", ValueType.TIMESTAMP, null),
    T_SEGMENT_CONFIRMED("tSegmentConfirmed", ValueType.TIMESTAMP, null),
    T_GATE_PASS("tGatePass", ValueType.TIMESTAMP, null),
    T_FIRST_FRAME_SENT("tFirstFrameSent", ValueType.TIMESTAMP, null),
    T_DEVICE_PLAY_START("tDevicePlayStart", ValueType.TIMESTAMP, null),
    T_DEVICE_PLAY_START_RECV("tDevicePlayStartRecv", ValueType.TIMESTAMP, null),
    CLOCK_OFFSET_MS("clockOffsetMs", ValueType.NUMBER, null),
    RTT_MS("rttMs", ValueType.NUMBER, null),
    T_TRANSCRIPT_FINAL("tTranscriptFinal", ValueType.TIMESTAMP, null),
    RELAY_IN_MS("relayInMs", ValueType.NUMBER, null),
    RELAY_OUT_MS("relayOutMs", ValueType.NUMBER, null),
    PROVIDER_RESPONSE_MS("providerResponseMs", ValueType.NUMBER, null),
    QUIET_WAIT_MS("quietWaitMs", ValueType.NUMBER, null),
    UNOBSERVED_REASON("unobservedReason", ValueType.ENUM, Set.of(
            "NO_OUTPUT", "GATE_DISCARD", "NO_PLAYBACK_MARK", "SESSION_ENDED_EARLY", "CLOCK_OFFSET_MISSING")),
    SEQ("seq", ValueType.NUMBER, null),
    SPEAKER("speaker", ValueType.ENUM, Set.of("CHILD", "AI")),
    T_VIEW_ITEM_READY("tViewItemReady", ValueType.TIMESTAMP, null),
    T_VIEW_ITEM_FIRST_SERVED("tViewItemFirstServed", ValueType.TIMESTAMP, null),
    STOP_CAUSE("stopCause", ValueType.ENUM, Set.of("LOCAL_VOICE_ACTIVITY", "STOP_OUTPUT", "CLOSE_TRANSPORT", "DISCONNECTED")),
    T_DEVICE_OBSERVED("tDeviceObserved", ValueType.TIMESTAMP, null),
    T_SERVER_RECV("tServerRecv", ValueType.TIMESTAMP, null),
    // Individual output discards: identifiers are the existing hashed owner + transcript seq + generation/time.
    DISCARD_REASON("discardReason", ValueType.ENUM, Set.of("SEGMENT_OVERSIZE", "GATE_EMPTY", "GATE_PATTERN",
            "NOT_ACTIVE", "RECORDER_DEGRADED", "OUTBOUND_FULL", "GUARDRAIL_BLOCK", "GUARDRAIL_REVIEW",
            "GUARDRAIL_TIMEOUT", "GUARDRAIL_ERROR", "GUARDRAIL_QUEUE_FULL", "VOIDED")),
    DISCARD_DETAIL("discardDetail", ValueType.ENUM, Set.of(
            "MISSING_SEGMENT", "INCOMPLETE_SEGMENT", "EMPTY_TRANSCRIPT",
            "MISSING_AUDIO", "INVALID_PCM_LENGTH", "FORBIDDEN_PATTERN",
            "INSPECTION_EXCEPTION", "SEGMENT_OVERSIZE", "OUTPUT_POLICY_MISMATCH",
            "APPROVED_TEXT_MISMATCH", "GUARDRAIL_DISABLED", "SESSION_INACTIVE",
            "STALE_GENERATION", "DEVICE_UNAVAILABLE", "RECORDER_DEGRADED",
            "AUDIO_QUEUE_FULL", "AUDIO_SEND_FAILED", "GUARDRAIL_BLOCK",
            "GUARDRAIL_REVIEW", "GUARDRAIL_TIMEOUT", "GUARDRAIL_ERROR",
            "GUARDRAIL_QUEUE_FULL", "GUARDRAIL_VOIDED", "INPUT_SUPERSEDED_UNPLAYED",
            "UNCLASSIFIED_GATE_EMPTY", "PROVIDER_NO_AUDIO", "PROVIDER_COLLECTION_TIMEOUT", "PROVIDER_ERROR", "GATE_PROCESSING_EXCEPTION")),
    OUTPUT_GENERATION("outputGeneration", ValueType.NUMBER, null),
    DEVICE_GENERATION("deviceGeneration", ValueType.NUMBER, null),
    CURRENT_DEVICE_GENERATION("currentDeviceGeneration", ValueType.NUMBER, null),
    SEGMENT_COMPLETE("segmentComplete", ValueType.BOOLEAN, null),
    AUDIO_BYTES("audioBytes", ValueType.NUMBER, null),
    AUDIO_STAGE("audioStage", ValueType.ENUM, Set.of("DRAINING", "DRAIN_TIMEOUT", "REQUEST_QUEUED", "REQUEST_SENT",
            "SEND_TIMEOUT", "FIRST_OUTPUT_TIMEOUT", "COLLECTING", "COLLECTION_TIMEOUT", "PLAYBACK_QUEUED", "PLAYING", "PLAYBACK_ENDED", "PLAYBACK_TIMEOUT")),
    /** 연습 출력 대기를 가장 오래 막은 사유(진단, JIRA-282 후속). NONE은 기다리지 않았다는 뜻이다. */
    DRAIN_BLOCKER("drainBlocker", ValueType.ENUM, Set.of("OUTPUT_FLOWING", "OUTPUT_PENDING", "CHILD_INPUT_OPEN", "NONE")),
    TRANSCRIPT_CHARS("transcriptChars", ValueType.NUMBER, null),
    EXPECTED_CHARS("expectedChars", ValueType.NUMBER, null),
    GENERATED_SPEECH("generatedSpeech", ValueType.BOOLEAN, null),
    PRACTICE_PHASE("practicePhase", ValueType.LABEL, null),
    PRACTICE_SUB("practiceSub", ValueType.LABEL, null),
    // DEVICE_CONNECTION · SECURITY_ORIGIN_DENIED
    CLOSE_CODE("closeCode", ValueType.NUMBER, null),
    // INTERNAL_HTTP
    TARGET("target", ValueType.ENUM, Set.of(
            "SESSION_START", "SESSION_END", "SESSION_VIEW", "VERIFY_TOKEN", "DEVICE_STATUS", "EVENT_PUBLISH", "PRACTICE_SCENARIO",
            "ADMIN_FACTS", "ADMIN_USAGE")),
    OUTCOME("outcome", ValueType.ENUM, Set.of("OK", "TIMEOUT", "CONN_ERROR", "HTTP_4XX", "HTTP_5XX", "BREAKER_OPEN")),
    STATUS_CODE("statusCode", ValueType.NUMBER, null),
    DURATION_MS("durationMs", ValueType.NUMBER, null),
    ATTEMPT("attempt", ValueType.NUMBER, null),
    REPEAT("repeat", ValueType.NUMBER, null),
    // EVENT_DELIVERY
    EVENT_ID("eventId", ValueType.UUID, null),
    EVENT_TYPE("eventType", ValueType.ENUM, Set.of(
            "DeviceReleased", "DeletionRequested", "SessionStateChanged", "SessionRecordCaptured", "DeviceStatusChanged",
            "DeletionCompleted")),
    STATUS("status", ValueType.ENUM, Set.of(
            "PUBLISHED", "RETRY", "FAILED", "REARMED", "PROCESSED", "HANDLER_RETRY", "DEAD", "IGNORED", "DUPLICATE",
            "EVENT_CONFLICT")),
    RETRY_COUNT("retryCount", ValueType.NUMBER, null),
    // STARTUP_RECOVERY
    RECOVERED_SESSIONS("recoveredSessions", ValueType.NUMBER, null),
    REOPENED_INBOX_ROWS("reopenedInboxRows", ValueType.NUMBER, null),
    RESET_CONNECTIONS("resetConnections", ValueType.NUMBER, null),
    REARMED_CONTROL_EVENTS("rearmedControlEvents", ValueType.NUMBER, null),
    // METRIC_SNAPSHOT (OD-6)
    OUTBOX_PENDING("outboxPending", ValueType.NUMBER, null),
    OUTBOX_OLDEST_PENDING_SEC("outboxOldestPendingSec", ValueType.NUMBER, null),
    OUTBOX_FAILED("outboxFailed", ValueType.NUMBER, null),
    INBOX_DEAD("inboxDead", ValueType.NUMBER, null),
    POOL_ACTIVE("poolActive", ValueType.NUMBER, null),
    POOL_WAIT_MAX_MS("poolWaitMaxMs", ValueType.NUMBER, null),
    POOL_WAIT_EXCEEDED("poolWaitExceeded", ValueType.NUMBER, null),
    OPEN_CONNECTIONS("openConnections", ValueType.NUMBER, null),
    REST_TOTAL("restTotal", ValueType.NUMBER, null),
    REST_LE_300_MS("restLe300Ms", ValueType.NUMBER, null),
    VIEW_TOTAL("viewTotal", ValueType.NUMBER, null),
    VIEW_LE_500_MS("viewLe500Ms", ValueType.NUMBER, null),
    VIEW_FALLBACK("viewFallback", ValueType.NUMBER, null),
    SYS_CPU_AVG_PCT("sysCpuAvgPct", ValueType.NUMBER, null),
    SYS_MEM_MAX_PCT("sysMemMaxPct", ValueType.NUMBER, null),
    HEAP_USED_PCT("heapUsedPct", ValueType.NUMBER, null),
    SESSIONS_ACTIVE("sessionsActive", ValueType.NUMBER, null),
    BREAKER_OPEN_SEC("breakerOpenSec", ValueType.NUMBER, null),
    // SECURITY_* (OD-13)
    // GOOGLE_*: U2 구글 로그인 실패 까닭(편차 (U2-j)). 응답은 모두 401 GOOGLE_AUTH_FAILED이고 로그만 까닭을 가른다
    KIND("kind", ValueType.ENUM, Set.of("GUARDIAN_SESSION", "DEVICE_TOKEN", "REGISTER_CODE",
            "GOOGLE_NOT_CONFIGURED", "GOOGLE_BAD_REQUEST", "GOOGLE_REDIRECT_MISMATCH", "GOOGLE_EXCHANGE_FAILED",
            "GOOGLE_KEYS_UNAVAILABLE", "GOOGLE_TOKEN_INVALID")),
    LIMITER("limiter", ValueType.ENUM, Set.of("BOOTSTRAP_IP", "REGISTER_FAILURE")),
    CHANNEL("channel", ValueType.ENUM, Set.of("WSS", "REST")),
    PROFILE("profile", ValueType.ENUM, Set.of("DEV", "DEMO", "PROD")),
    DIRECTION("direction", ValueType.ENUM, Set.of("CORE_TO_REALTIME", "REALTIME_TO_CORE")),
    // APP_ERROR
    ERROR_KIND("errorKind", ValueType.IDENTIFIER, null),
    // QUEUE_PRESSURE
    QUEUE("queue", ValueType.ENUM, Set.of("INPUT_FRAMES", "GENERAL_MAILBOX", "OUTPUT_AUDIO", "OUTPUT_CONTROL", "RECORDER")),
    DROPPED("dropped", ValueType.NUMBER, null),
    // LUNA_CALL · LUNA_STARTUP (U9 SD6 · BR6.1 · NFR3.5). 본문 · 전사 · 이름 · 키를 담는 키는 없다
    LUNA_PURPOSE("lunaPurpose", ValueType.ENUM, Set.of(
            "JUDGMENT_LIVE", "SCENARIO_GENERATION", "SCENARIO_REVIEW", "SEED_GENERATION", "RESULT_TEXT", "GOAL_CANDIDATES",
            "REJUDGMENT")),
    LUNA_OUTCOME("lunaOutcome", ValueType.ENUM, Set.of(
            "OK", "AUTH_FAILED", "RATE_LIMITED", "SERVER_ERROR", "REQUEST_REJECTED", "TRANSPORT_ERROR", "TIMEOUT",
            "EMPTY_OUTPUT", "LENGTH_EXCEEDED", "REFUSED", "SCHEMA_INVALID", "FORBIDDEN_INPUT", "MODEL_MISSING")),
    MODEL("model", ValueType.LABEL, null),
    RESPONSE_MODEL("responseModel", ValueType.LABEL, null),
    PROMPT_VERSION("promptVersion", ValueType.LABEL, null),
    SENT_FIELDS("sentFields", ValueType.NAME_LIST, null),
    LATENCY_MS("latencyMs", ValueType.NUMBER, null),
    LUNA_STATE("lunaState", ValueType.ENUM, Set.of("ENABLED", "DISABLED_NO_KEY", "DISABLED_SWITCH_OFF")),
    // GOAL_CANDIDATE_DROPPED (FT-1027-g 후속). 후보 이름 · 인용 · 사유 글은 담지 않고 몇 번째 후보가 어느 검사의 어느 칸에서 빠졌는지만 남긴다
    CANDIDATE_INDEX("candidateIndex", ValueType.NUMBER, null),
    CANDIDATES_PROPOSED("candidatesProposed", ValueType.NUMBER, null),
    DROP_REASON("dropReason", ValueType.ENUM, Set.of("LENGTH", "SKILL_REF_UNKNOWN", "NO_EVIDENCE", "QUOTE_MISMATCH",
            "FORBIDDEN_DICTIONARY", "NUMBER_GUARD", "DUPLICATE", "OVER_LIMIT", "CHECK_EMPTY", "CHECK_QUOTE", "CHECK_NUMBER",
            "CHECK_DICTIONARY", "CHECK_CLASSIFIER")),
    DROP_FIELD("dropField", ValueType.ENUM, Set.of("DISPLAY_NAME", "DISPLAY_EVIDENCE", "TARGET_EXPRESSION", "REASON_TEXT",
            "SKILL_REF", "SITUATION", "PARTNER", "INTENT", "EXPRESSION", "EVIDENCE", "NONE")),
    // ADMIN_RECORD_VIEWED(JIRA-280): 열람한 관리자의 ownerHash(OwnerHasher 12자 16진). 관리자 id 원문은 싣지 않는다
    VIEWER_HASH("viewerHash", ValueType.LABEL, null);

    /** 값 형식(OD-1 표). */
    public enum ValueType {
        /** 정수(건수 · 밀리초 · 코드 · 퍼센트). 소수는 쓰지 않는다. */
        NUMBER,
        /** 등록된 열거값(대문자 영문 · 숫자 · 밑줄, eventType은 등록된 이름). 미등록은 UNKNOWN. */
        ENUM,
        /** 불리언(열거값의 하나로 본다). */
        BOOLEAN,
        /** UTC 시각(밀리초, 끝 Z). */
        TIMESTAMP,
        /** UUID(EVENT_DELIVERY.eventId만). */
        UUID,
        /** 예외 클래스 단순 이름 꼴 {@code [A-Za-z][A-Za-z0-9_.$]{0,99}}(errorKind만). */
        IDENTIFIER,
        /** 모델 이름 · 프롬프트 판 꼴 {@code [A-Za-z0-9][A-Za-z0-9._-]{0,63}}(U9 SD6). 어긋나면 UNKNOWN. */
        LABEL,
        /**
         * 쉼표로 이은 필드 이름 목록. 이름마다 {@code [A-Za-z][A-Za-z0-9_]{0,63}}, 최대 32개(U9 SD6, 빈 목록 허용). 하나라도 어긋나면
         * 전체가 UNKNOWN.
         */
        NAME_LIST
    }

    /** 등록되지 않은 열거값을 대신하는 값(OD-1). */
    public static final String UNKNOWN = "UNKNOWN";

    private final String key;
    private final ValueType valueType;
    private final Set<String> enumValues;

    AttrKey(String key, ValueType valueType, Set<String> enumValues) {
        this.key = key;
        this.valueType = valueType;
        this.enumValues = enumValues == null ? Set.of() : Set.copyOf(enumValues);
    }

    /** JSON 키 이름. */
    public String key() {
        return key;
    }

    /** 값 형식. */
    public ValueType valueType() {
        return valueType;
    }

    /** 기본 열거 집합(열거형 키만, 그 밖은 빈 집합). */
    public Set<String> enumValues() {
        return enumValues;
    }

    /** JSON 키 이름으로 찾는다. */
    public static Optional<AttrKey> fromKey(String key) {
        return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
    }

    /** 키 사이에 공유하는 열거 집합. */
    static final class Sets {
        /** 세션 전이 출발(Core가 REQUESTED를 만들 때 NONE). */
        static final Set<String> SESSION_FROM = Set.of(
                "NONE", "REQUESTED", "PREPARING", "DEVICE_ACK", "WAITING_CHILD", "ACTIVE", "CLOSING", "ENDED",
                "ENDED_UNCONFIRMED", "ABORTED", "DECLINED", "FAILED");
        /** 세션 전이 도착. */
        static final Set<String> SESSION_TO = Set.of(
                "REQUESTED", "PREPARING", "DEVICE_ACK", "WAITING_CHILD", "ACTIVE", "CLOSING", "ENDED", "ENDED_UNCONFIRMED",
                "ABORTED", "DECLINED", "FAILED");
        /** 기기 연결 상태. */
        static final Set<String> CONNECTION = Set.of("OFFLINE", "CONNECTED", "UNSTABLE", "DISCONNECTED", "UNRESPONSIVE");

        private Sets() {
        }
    }
}
