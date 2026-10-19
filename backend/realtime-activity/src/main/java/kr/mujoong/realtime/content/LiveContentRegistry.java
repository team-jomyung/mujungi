package kr.mujoong.realtime.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.mujoong.realtime.voice.PracticeSpeech;

/**
 * 세션 콘텐츠 계획 · 원문 일치 확인 (U8 functional-spec W1 · W2 · 8.3 · SD4 · SD6).
 *
 * <ul>
 *   <li><b>고정</b>({@link #pin}): 세션 시작 패키지를 받을 때 판정 선택지 판 · 가드레일 정책 판 번호와 패키지의 승인 문구 판(approvedContent ·
 *       approvedSpeech)을 계획으로 고정한다. 같은 세션을 다시 고정해도 처음 계획이 남는다(세션 동안 바뀌지 않음).
 *   <li><b>대본 등록</b>({@link #registerScript}): 연습 흐름(U12)이 대본을 받은 직후 장면 문장을 scriptVersion과 함께 등록한다.
 *   <li><b>원문 일치</b>({@link #matchApproved}): 출력 전사에서 ASCII 공백을 뺀 문자열이 고정한 승인 발화 또는 등록된 대본 문장과 정확히
 *       같으면 {@link MatchKind#MATCHED}, 계획은 있으나 아무것과도 같지 않으면 {@link MatchKind#NOT_MATCHED}, 계획이 없으면(고정 전 · 놓은 뒤)
 *       {@link MatchKind#UNKNOWN}이다. 제브 검사 생략 근거는 MATCHED뿐이다(U13이 판단).
 *   <li><b>놓기</b>({@link #release}): 세션 정산이 끝난 뒤 실행기 종료 정리에서 지운다.
 * </ul>
 *
 * <p>계획은 세션 id를 키로 하는 동시성 맵 하나에만 두고 DB · 파일 · 캐시에 쓰지 않는다. 이 컴포넌트는 로그를 쓰지 않고, 판 번호와 결과 종류만
 * 반환값으로 낸다(SD4 — ArchUnit {@code LiveContentArchTest}가 로그 의존 0을 강제한다). 판정 · 검사 기록 행에 판 번호를 쓰는 일은
 * 받는 단위(U11 · U13)가 한다.
 */
public final class LiveContentRegistry {

    /** 원문 일치 결과 종류. */
    public enum MatchKind {
        MATCHED,
        NOT_MATCHED,
        UNKNOWN
    }

    /** 일치한 원문의 출처. */
    public enum MatchSource {
        /** 시작 패키지 approvedSpeech(음성 없는 승인 발화). */
        APPROVED_SPEECH,
        /** 등록된 세션 대본 문장(core 상황 생성 대본, C-DS1). */
        SESSION_SCRIPT
    }

    /**
     * 원문 일치 결과. MATCHED일 때만 출처와 판(승인 발화는 contentVersionId, 대본은 scriptVersion)이 있다. 출력 전사 · 원문은 담지 않는다.
     */
    public record MatchResult(MatchKind kind, MatchSource source, String version) {

        public static final MatchResult NOT_MATCHED = new MatchResult(MatchKind.NOT_MATCHED, null, null);
        public static final MatchResult UNKNOWN = new MatchResult(MatchKind.UNKNOWN, null, null);

        public MatchResult {
            Objects.requireNonNull(kind, "kind");
            if ((kind == MatchKind.MATCHED) != (source != null && version != null)) {
                throw new IllegalArgumentException("MATCHED만 출처와 판을 가진다");
            }
        }

        static MatchResult matched(MatchSource source, String version) {
            return new MatchResult(MatchKind.MATCHED, source, version);
        }

        /** 제브 검사를 생략할 근거가 되는가(MATCHED만). */
        public boolean allowsGuardrailSkip() {
            return kind == MatchKind.MATCHED;
        }
    }

    /** 고정한 음성 없는 승인 발화 한 칸(시작 패키지 approvedSpeech 값). 글은 문자열 표현에 내지 않는다. */
    public record SpeechText(UUID contentVersionId, String text) {

        public SpeechText {
            Objects.requireNonNull(contentVersionId, "contentVersionId");
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("승인 발화 text가 비었다");
            }
        }

        @Override
        public String toString() {
            return "SpeechText[contentVersionId=" + contentVersionId + "]";
        }
    }

    /** 등록한 대본 하나(목표마다). 문장은 문자열 표현에 내지 않는다. */
    public record SessionScript(UUID scenarioRef, String scriptVersion, Map<String, String> sentences) {

        public SessionScript {
            Objects.requireNonNull(scenarioRef, "scenarioRef");
            if (scriptVersion == null || scriptVersion.isBlank()) {
                throw new IllegalArgumentException("scriptVersion이 비었다");
            }
            sentences = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(sentences, "sentences")));
        }

        @Override
        public String toString() {
            return "SessionScript[scenarioRef=" + scenarioRef + ", scriptVersion=" + scriptVersion + "]";
        }
    }

    /**
     * 세션 콘텐츠 계획(entities.md SessionContentPlan). 판 번호는 고정 뒤 바뀌지 않고, 대본만 목표마다 더해진다.
     *
     * @param approvedContentVersionIds 시작 패키지 approvedContent · approvedSpeech의 code → contentVersionId
     * @param scriptVersion 마지막으로 등록한 대본 판(없으면 null)
     */
    public record SessionContentPlan(
            UUID sessionId,
            String judgmentSetVersion,
            String guardrailPolicyVersion,
            Map<String, UUID> approvedContentVersionIds,
            Map<String, SpeechText> approvedSpeech,
            List<SessionScript> scripts) {

        public SessionContentPlan {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(judgmentSetVersion, "judgmentSetVersion");
            Objects.requireNonNull(guardrailPolicyVersion, "guardrailPolicyVersion");
            approvedContentVersionIds = Collections.unmodifiableMap(new LinkedHashMap<>(approvedContentVersionIds));
            approvedSpeech = Collections.unmodifiableMap(new LinkedHashMap<>(approvedSpeech));
            scripts = List.copyOf(scripts);
        }

        /** 마지막으로 등록한 대본 판(없으면 null). */
        public String scriptVersion() {
            return scripts.isEmpty() ? null : scripts.get(scripts.size() - 1).scriptVersion();
        }

        SessionContentPlan withScript(SessionScript script) {
            List<SessionScript> next = new ArrayList<>(scripts);
            next.add(script);
            return new SessionContentPlan(sessionId, judgmentSetVersion, guardrailPolicyVersion, approvedContentVersionIds,
                    approvedSpeech, next);
        }

        @Override
        public String toString() {
            return "SessionContentPlan[sessionId=" + sessionId + ", judgmentSetVersion=" + judgmentSetVersion
                    + ", guardrailPolicyVersion=" + guardrailPolicyVersion + "]";
        }
    }

    private final LiveContentResources resources;
    private final ConcurrentHashMap<UUID, SessionContentPlan> plans = new ConcurrentHashMap<>();

    public LiveContentRegistry(LiveContentResources resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /** 기동 때 읽은 판(읽기 전용). */
    public LiveContentResources resources() {
        return resources;
    }

    /**
     * 세션 계획을 고정한다. 이미 고정한 세션이면 처음 계획을 그대로 돌려준다.
     *
     * @param approvedContentVersionIds 패키지 approvedContent의 code → contentVersionId(음성 있는 승인 문구)
     * @param approvedSpeech 패키지 approvedSpeech(두 맵의 code가 겹치면 받는 쪽이 미리 approvedSpeech 쪽을 버린다)
     */
    public SessionContentPlan pin(UUID sessionId, Map<String, UUID> approvedContentVersionIds,
            Map<String, SpeechText> approvedSpeech) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(approvedContentVersionIds, "approvedContentVersionIds");
        Objects.requireNonNull(approvedSpeech, "approvedSpeech");
        Map<String, UUID> versions = new LinkedHashMap<>(approvedContentVersionIds);
        approvedSpeech.forEach((code, speech) -> versions.putIfAbsent(code, speech.contentVersionId()));
        return plans.computeIfAbsent(sessionId, id -> new SessionContentPlan(id, resources.judgment().version(),
                resources.guardrail().version(), versions, approvedSpeech, List.of()));
    }

    /** 고정한 계획(없으면 빈 값). */
    public Optional<SessionContentPlan> plan(UUID sessionId) {
        return Optional.ofNullable(plans.get(Objects.requireNonNull(sessionId, "sessionId")));
    }

    /**
     * 대본 문장을 등록한다. 계획이 없는 세션(고정 전 · 놓은 뒤)이면 등록하지 않고 거짓이다.
     *
     * @param sentences 문장 키(장면 · 기회 문장 · 다시 설명 · 집중 요점 · 표현 예시) → 원문
     */
    public boolean registerScript(UUID sessionId, UUID scenarioRef, String scriptVersion, Map<String, String> sentences) {
        SessionScript script = new SessionScript(scenarioRef, scriptVersion, sentences);
        return plans.computeIfPresent(Objects.requireNonNull(sessionId, "sessionId"), (id, plan) -> plan.withScript(script))
                != null;
    }

    /**
     * 출력 전사가 고정한 승인 발화 또는 등록된 대본 문장과 원문 일치하는지 본다(SD6). 정규화는 ASCII 공백 제거뿐이다
     * ({@link PracticeSpeech#sameWithoutAsciiWhitespace}). 입력 전사는 저장 · 기록하지 않는다.
     */
    public MatchResult matchApproved(UUID sessionId, String outputText) {
        SessionContentPlan plan = plans.get(Objects.requireNonNull(sessionId, "sessionId"));
        if (plan == null) {
            return MatchResult.UNKNOWN;
        }
        if (outputText == null || outputText.isBlank()) {
            return MatchResult.NOT_MATCHED;
        }
        for (SpeechText speech : plan.approvedSpeech().values()) {
            if (PracticeSpeech.sameWithoutAsciiWhitespace(speech.text(), outputText)) {
                return MatchResult.matched(MatchSource.APPROVED_SPEECH, speech.contentVersionId().toString());
            }
        }
        for (SessionScript script : plan.scripts()) {
            for (String sentence : script.sentences().values()) {
                if (PracticeSpeech.sameWithoutAsciiWhitespace(sentence, outputText)) {
                    return MatchResult.matched(MatchSource.SESSION_SCRIPT, script.scriptVersion());
                }
            }
        }
        return MatchResult.NOT_MATCHED;
    }

    /** 세션 계획을 놓는다(세션 정산 뒤 실행기 종료 정리). 없으면 아무것도 하지 않는다. */
    public void release(UUID sessionId) {
        plans.remove(Objects.requireNonNull(sessionId, "sessionId"));
    }

    /** 고정된 계획 수(메모리 누적 점검 · 시험용, 원문 없음). */
    public int pinnedCount() {
        return plans.size();
    }
}
