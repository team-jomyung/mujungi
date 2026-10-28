package kr.mujoong.contract.log;

import static kr.mujoong.contract.log.AttrKey.DISCARD_REASON;
import static kr.mujoong.contract.log.AttrKey.DISCARD_DETAIL;
import static kr.mujoong.contract.log.AttrKey.OUTPUT_GENERATION;
import static kr.mujoong.contract.log.AttrKey.DEVICE_GENERATION;
import static kr.mujoong.contract.log.AttrKey.CURRENT_DEVICE_GENERATION;
import static kr.mujoong.contract.log.AttrKey.SEGMENT_COMPLETE;
import static kr.mujoong.contract.log.AttrKey.AUDIO_BYTES;
import static kr.mujoong.contract.log.AttrKey.TRANSCRIPT_CHARS;
import static kr.mujoong.contract.log.AttrKey.EXPECTED_CHARS;
import static kr.mujoong.contract.log.AttrKey.GENERATED_SPEECH;
import static kr.mujoong.contract.log.AttrKey.PRACTICE_PHASE;
import static kr.mujoong.contract.log.AttrKey.PRACTICE_SUB;
import static kr.mujoong.contract.log.AttrKey.ATTEMPT;
import static kr.mujoong.contract.log.AttrKey.BREAKER_OPEN_SEC;
import static kr.mujoong.contract.log.AttrKey.CHANNEL;
import static kr.mujoong.contract.log.AttrKey.CLOCK_OFFSET_MS;
import static kr.mujoong.contract.log.AttrKey.CLOSE_CODE;
import static kr.mujoong.contract.log.AttrKey.CONFIRMED;
import static kr.mujoong.contract.log.AttrKey.DIRECTION;
import static kr.mujoong.contract.log.AttrKey.DROPPED;
import static kr.mujoong.contract.log.AttrKey.DURATION_MS;
import static kr.mujoong.contract.log.AttrKey.ELAPSED_MS;
import static kr.mujoong.contract.log.AttrKey.END_REASON;
import static kr.mujoong.contract.log.AttrKey.ERROR_KIND;
import static kr.mujoong.contract.log.AttrKey.EVENT_ID;
import static kr.mujoong.contract.log.AttrKey.EVENT_TYPE;
import static kr.mujoong.contract.log.AttrKey.FAILURE_CODE;
import static kr.mujoong.contract.log.AttrKey.FROM;
import static kr.mujoong.contract.log.AttrKey.HEAP_USED_PCT;
import static kr.mujoong.contract.log.AttrKey.INBOX_DEAD;
import static kr.mujoong.contract.log.AttrKey.KIND;
import static kr.mujoong.contract.log.AttrKey.LATENCY_MS;
import static kr.mujoong.contract.log.AttrKey.LIMITER;
import static kr.mujoong.contract.log.AttrKey.LUNA_OUTCOME;
import static kr.mujoong.contract.log.AttrKey.LUNA_PURPOSE;
import static kr.mujoong.contract.log.AttrKey.LUNA_STATE;
import static kr.mujoong.contract.log.AttrKey.MODEL;
import static kr.mujoong.contract.log.AttrKey.OPEN_CONNECTIONS;
import static kr.mujoong.contract.log.AttrKey.ORIGIN;
import static kr.mujoong.contract.log.AttrKey.OUTBOX_FAILED;
import static kr.mujoong.contract.log.AttrKey.OUTBOX_OLDEST_PENDING_SEC;
import static kr.mujoong.contract.log.AttrKey.OUTBOX_PENDING;
import static kr.mujoong.contract.log.AttrKey.OUTCOME;
import static kr.mujoong.contract.log.AttrKey.PHASE;
import static kr.mujoong.contract.log.AttrKey.POOL_ACTIVE;
import static kr.mujoong.contract.log.AttrKey.POOL_WAIT_EXCEEDED;
import static kr.mujoong.contract.log.AttrKey.POOL_WAIT_MAX_MS;
import static kr.mujoong.contract.log.AttrKey.PROFILE;
import static kr.mujoong.contract.log.AttrKey.PROMPT_VERSION;
import static kr.mujoong.contract.log.AttrKey.PROVIDER_RESPONSE_MS;
import static kr.mujoong.contract.log.AttrKey.QUEUE;
import static kr.mujoong.contract.log.AttrKey.QUIET_WAIT_MS;
import static kr.mujoong.contract.log.AttrKey.REARMED_CONTROL_EVENTS;
import static kr.mujoong.contract.log.AttrKey.RECOVERED_SESSIONS;
import static kr.mujoong.contract.log.AttrKey.RELAY_IN_MS;
import static kr.mujoong.contract.log.AttrKey.RELAY_OUT_MS;
import static kr.mujoong.contract.log.AttrKey.REOPENED_INBOX_ROWS;
import static kr.mujoong.contract.log.AttrKey.REPEAT;
import static kr.mujoong.contract.log.AttrKey.RESET_CONNECTIONS;
import static kr.mujoong.contract.log.AttrKey.RESPONSE_MODEL;
import static kr.mujoong.contract.log.AttrKey.REST_LE_300_MS;
import static kr.mujoong.contract.log.AttrKey.REST_TOTAL;
import static kr.mujoong.contract.log.AttrKey.RETRY_COUNT;
import static kr.mujoong.contract.log.AttrKey.RTT_MS;
import static kr.mujoong.contract.log.AttrKey.SENT_FIELDS;
import static kr.mujoong.contract.log.AttrKey.SEQ;
import static kr.mujoong.contract.log.AttrKey.SESSIONS_ACTIVE;
import static kr.mujoong.contract.log.AttrKey.SPEAKER;
import static kr.mujoong.contract.log.AttrKey.STATUS;
import static kr.mujoong.contract.log.AttrKey.STATUS_CODE;
import static kr.mujoong.contract.log.AttrKey.STATUS_VERSION;
import static kr.mujoong.contract.log.AttrKey.STOP_CAUSE;
import static kr.mujoong.contract.log.AttrKey.SYS_CPU_AVG_PCT;
import static kr.mujoong.contract.log.AttrKey.SYS_MEM_MAX_PCT;
import static kr.mujoong.contract.log.AttrKey.TARGET;
import static kr.mujoong.contract.log.AttrKey.THRESHOLD_MS;
import static kr.mujoong.contract.log.AttrKey.TO;
import static kr.mujoong.contract.log.AttrKey.TURN_SEQ;
import static kr.mujoong.contract.log.AttrKey.TYPE;
import static kr.mujoong.contract.log.AttrKey.T_DEVICE_OBSERVED;
import static kr.mujoong.contract.log.AttrKey.T_DEVICE_PLAY_START;
import static kr.mujoong.contract.log.AttrKey.T_DEVICE_PLAY_START_RECV;
import static kr.mujoong.contract.log.AttrKey.T_FIRST_FRAME_SENT;
import static kr.mujoong.contract.log.AttrKey.T_GATE_PASS;
import static kr.mujoong.contract.log.AttrKey.T_PROVIDER_FIRST_OUT;
import static kr.mujoong.contract.log.AttrKey.T_SEGMENT_CONFIRMED;
import static kr.mujoong.contract.log.AttrKey.T_SERVER_RECV;
import static kr.mujoong.contract.log.AttrKey.T_SPEECH_END;
import static kr.mujoong.contract.log.AttrKey.T_SPEECH_END_SOURCE;
import static kr.mujoong.contract.log.AttrKey.T_TRANSCRIPT_FINAL;
import static kr.mujoong.contract.log.AttrKey.T_VIEW_ITEM_FIRST_SERVED;
import static kr.mujoong.contract.log.AttrKey.T_VIEW_ITEM_READY;
import static kr.mujoong.contract.log.AttrKey.UNOBSERVED_REASON;
import static kr.mujoong.contract.log.AttrKey.VIEWER_HASH;
import static kr.mujoong.contract.log.AttrKey.VIEW_FALLBACK;
import static kr.mujoong.contract.log.AttrKey.VIEW_LE_500_MS;
import static kr.mujoong.contract.log.AttrKey.VIEW_TOTAL;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 사건 코드 23종(OD-2: 요구 문서 15종 + APP_ERROR · QUEUE_PRESSURE, U9 SD6: LUNA_CALL · LUNA_STARTUP, 연습 진단 2종, 목표 후보 진단 GOAL_CANDIDATE_DROPPED, JIRA-280: ADMIN_RECORD_VIEWED). 코드마다 기본 수준 · 허용 attrs
 * 키 · 병합 대상 여부를 정한다. 허용 표에 없는 키는 쓸 수 없다({@link EventLog}가 막는다). LUNA_CALL은 시도마다 한 줄이어야 해서(BR6.1)
 * 병합하지 않는다.
 */
public enum EventCode {
    SESSION_TRANSITION(Level.INFO, false, false,
            EnumSet.of(FROM, TO, STATUS_VERSION, END_REASON, FAILURE_CODE, CONFIRMED, PHASE, ORIGIN)),
    TIMEOUT_FIRED(Level.WARN, false, false, EnumSet.of(TYPE, THRESHOLD_MS, ELAPSED_MS)),
    LATENCY_TURN(Level.INFO, false, false, EnumSet.of(
            TURN_SEQ, T_SPEECH_END, T_SPEECH_END_SOURCE, T_PROVIDER_FIRST_OUT, T_SEGMENT_CONFIRMED, T_GATE_PASS,
            T_FIRST_FRAME_SENT, T_DEVICE_PLAY_START, T_DEVICE_PLAY_START_RECV, CLOCK_OFFSET_MS, RTT_MS, T_TRANSCRIPT_FINAL,
            RELAY_IN_MS, RELAY_OUT_MS, PROVIDER_RESPONSE_MS, QUIET_WAIT_MS, UNOBSERVED_REASON)),
    SESSION_ITEMS_SERVED(Level.INFO, false, false,
            EnumSet.of(SEQ, SPEAKER, T_TRANSCRIPT_FINAL, T_VIEW_ITEM_READY, T_VIEW_ITEM_FIRST_SERVED)),
    PLAYBACK_MARK_STOPPED(Level.INFO, false, false,
            EnumSet.of(STOP_CAUSE, T_DEVICE_OBSERVED, T_SERVER_RECV, CLOCK_OFFSET_MS, RTT_MS)),
    PRACTICE_AUDIO_STAGE(Level.INFO, false, false, EnumSet.of(AttrKey.AUDIO_STAGE, OUTPUT_GENERATION, PRACTICE_PHASE, PRACTICE_SUB,
            AttrKey.DURATION_MS, AttrKey.DRAIN_BLOCKER)),
    OUTPUT_DISCARDED(Level.INFO, false, false, EnumSet.of(SEQ, DISCARD_REASON, DISCARD_DETAIL,
            OUTPUT_GENERATION, DEVICE_GENERATION, CURRENT_DEVICE_GENERATION, SEGMENT_COMPLETE, AUDIO_BYTES,
            TRANSCRIPT_CHARS, EXPECTED_CHARS, GENERATED_SPEECH, PRACTICE_PHASE, PRACTICE_SUB,
            T_PROVIDER_FIRST_OUT, T_TRANSCRIPT_FINAL)),
    DEVICE_CONNECTION(Level.INFO, false, false, EnumSet.of(FROM, TO, CLOSE_CODE)),
    INTERNAL_HTTP(Level.INFO, true, false,
            EnumSet.of(TARGET, OUTCOME, STATUS_CODE, DURATION_MS, ATTEMPT, REPEAT)),
    EVENT_DELIVERY(Level.INFO, false, false, EnumSet.of(EVENT_ID, EVENT_TYPE, STATUS, RETRY_COUNT, DURATION_MS)),
    STARTUP_RECOVERY(Level.INFO, false, false,
            EnumSet.of(RECOVERED_SESSIONS, REOPENED_INBOX_ROWS, RESET_CONNECTIONS, REARMED_CONTROL_EVENTS)),
    METRIC_SNAPSHOT(Level.INFO, false, false, EnumSet.of(
            OUTBOX_PENDING, OUTBOX_OLDEST_PENDING_SEC, OUTBOX_FAILED, INBOX_DEAD, POOL_ACTIVE, POOL_WAIT_MAX_MS,
            POOL_WAIT_EXCEEDED, OPEN_CONNECTIONS, REST_TOTAL, REST_LE_300_MS, VIEW_TOTAL, VIEW_LE_500_MS, VIEW_FALLBACK,
            SYS_CPU_AVG_PCT, SYS_MEM_MAX_PCT, HEAP_USED_PCT, SESSIONS_ACTIVE, BREAKER_OPEN_SEC)),
    SECURITY_AUTH_FAILED(Level.WARN, true, true, EnumSet.of(KIND, STATUS_CODE, REPEAT)),
    SECURITY_RATE_LIMITED(Level.WARN, true, true, EnumSet.of(LIMITER, STATUS_CODE, REPEAT)),
    SECURITY_ORIGIN_DENIED(Level.WARN, true, true, EnumSet.of(CHANNEL, STATUS_CODE, CLOSE_CODE, REPEAT)),
    SECURITY_DEV_LOGIN_BLOCKED(Level.WARN, true, true, EnumSet.of(PROFILE, STATUS_CODE, REPEAT)),
    SECURITY_INTERNAL_TOKEN_FAILED(Level.WARN, true, true, EnumSet.of(DIRECTION, STATUS_CODE, REPEAT)),
    APP_ERROR(Level.ERROR, true, false, EnumSet.of(ERROR_KIND, REPEAT)),
    QUEUE_PRESSURE(Level.WARN, false, false, EnumSet.of(QUEUE, DROPPED, REPEAT)),
    LUNA_CALL(Level.INFO, false, false, EnumSet.of(
            LUNA_PURPOSE, LUNA_OUTCOME, MODEL, RESPONSE_MODEL, PROMPT_VERSION, SENT_FIELDS, LATENCY_MS, ATTEMPT)),
    LUNA_STARTUP(Level.INFO, false, false, EnumSet.of(LUNA_STATE)),
    /** 목표 후보가 서버 검사에서 빠진 이유(진단, FT-1027-g 후속). 후보마다 한 줄이며 원문은 없다. */
    GOAL_CANDIDATE_DROPPED(Level.INFO, false, false, EnumSet.of(PROMPT_VERSION, AttrKey.CANDIDATE_INDEX,
            AttrKey.CANDIDATES_PROPOSED, AttrKey.DROP_REASON, AttrKey.DROP_FIELD)),
    /**
     * JIRA-280: 관리자가 활동 상세(대화 원문)를 열람했다. ownerHash는 활동, viewerHash는 열람한 관리자의 해시다. 열람마다 한 줄이어야 해서
     * 병합하지 않고 보안 사건 상한도 받지 않는다. 원문 · 아이 이름은 싣지 않는다.
     */
    ADMIN_RECORD_VIEWED(Level.INFO, false, false, EnumSet.of(VIEWER_HASH));

    /** 로그 수준(OD-1). demo는 INFO 이상만 출력한다. */
    public enum Level {
        ERROR,
        WARN,
        INFO,
        DEBUG
    }

    private static final Set<String> SESSION_WARN_TARGETS = Set.of("FAILED", "ABORTED", "ENDED_UNCONFIRMED");
    private static final Set<String> CONNECTION_WARN_TARGETS = Set.of("DISCONNECTED", "UNRESPONSIVE");
    private static final Set<String> DELIVERY_WARN = Set.of("FAILED", "DEAD", "EVENT_CONFLICT");
    private static final Set<String> DELIVERY_DEBUG = Set.of("DUPLICATE", "HANDLER_RETRY");

    private final Level defaultLevel;
    private final boolean mergeable;
    private final boolean securityEvent;
    private final Set<AttrKey> allowedKeys;

    EventCode(Level defaultLevel, boolean mergeable, boolean securityEvent, Set<AttrKey> allowedKeys) {
        this.defaultLevel = defaultLevel;
        this.mergeable = mergeable;
        this.securityEvent = securityEvent;
        this.allowedKeys = Set.copyOf(allowedKeys);
    }

    /** 기본 수준(예외 규칙은 {@link #resolveLevel(Map)}). */
    public Level defaultLevel() {
        return defaultLevel;
    }

    /** 반복 병합 대상인가(INTERNAL_HTTP 실패 · SECURITY_* 5종 · APP_ERROR, OD-3 4번). */
    public boolean isMergeable() {
        return mergeable;
    }

    /** 보안 사건 5종인가(OD-13, 분당 상한 대상). */
    public boolean isSecurityEvent() {
        return securityEvent;
    }

    /** 허용 attrs 키. */
    public Set<AttrKey> allowedKeys() {
        return allowedKeys;
    }

    /** 이 사건에서 열거형 키가 받을 수 있는 값 집합. 연결 사건의 from · to는 연결 상태 집합이다. */
    public Set<String> enumValues(AttrKey key) {
        if (this == DEVICE_CONNECTION && (key == FROM || key == TO)) {
            return AttrKey.Sets.CONNECTION;
        }
        return key.enumValues();
    }

    /**
     * 열거형 attrs 값으로 수준을 정한다(OD-2 수준 열의 예외 규칙). SESSION_TRANSITION은 FAILED · ABORTED · ENDED_UNCONFIRMED로 가면
     * WARN, DEVICE_CONNECTION은 DISCONNECTED · UNRESPONSIVE로 가면 WARN, INTERNAL_HTTP는 OK가 아니면 WARN, EVENT_DELIVERY는
     * FAILED · DEAD · EVENT_CONFLICT가 WARN이고 DUPLICATE · HANDLER_RETRY는 DEBUG, QUEUE_PRESSURE는 RECORDER면 ERROR, LUNA_CALL은
     * lunaOutcome이 OK가 아니면 WARN(U9 SD6).
     */
    public Level resolveLevel(Map<AttrKey, String> enumAttrs) {
        return switch (this) {
            case SESSION_TRANSITION -> contains(SESSION_WARN_TARGETS, enumAttrs.get(TO)) ? Level.WARN : defaultLevel;
            case DEVICE_CONNECTION -> contains(CONNECTION_WARN_TARGETS, enumAttrs.get(TO)) ? Level.WARN : defaultLevel;
            case INTERNAL_HTTP -> {
                String outcome = enumAttrs.get(OUTCOME);
                yield outcome != null && !"OK".equals(outcome) ? Level.WARN : defaultLevel;
            }
            case EVENT_DELIVERY -> {
                String status = enumAttrs.get(STATUS);
                if (contains(DELIVERY_WARN, status)) {
                    yield Level.WARN;
                }
                yield contains(DELIVERY_DEBUG, status) ? Level.DEBUG : defaultLevel;
            }
            case QUEUE_PRESSURE -> "RECORDER".equals(enumAttrs.get(QUEUE)) ? Level.ERROR : defaultLevel;
            case LUNA_CALL -> {
                String outcome = enumAttrs.get(LUNA_OUTCOME);
                yield outcome != null && !"OK".equals(outcome) ? Level.WARN : defaultLevel;
            }
            default -> defaultLevel;
        };
    }

    /** {@code Set.of}의 contains(null)은 NPE이므로 값이 없으면 거짓으로 본다. */
    private static boolean contains(Set<String> values, String value) {
        return value != null && values.contains(value);
    }
}
