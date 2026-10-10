package kr.mujoong.core.activity;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.identity.Child;
import org.springframework.stereotype.Component;

/**
 * 세션 패키지 조립 (U1 3.5 Step 22 · BR6.5 · NFR3.1 · SD-20 · J11 · J117).
 *
 * <p>Realtime {@code POST /internal/sessions} 본문(internal-session-start-request.schema.json)을 만든다. 담는 것은 sessionId · 멱등 키 ·
 * 보호자 · 아이 · 기기 id, activityType FREE_TALK, consentSnapshot(정책 버전 + 4항목), childContext(displayName · age · interests 세 필드뿐),
 * stopExpressions(기본 종료 표현 '무중아 그만할래' 한 건, 코드 상수 · isDefault true), approvedContent(CLOSING · WAITING_FILLER). 이
 * 빌더는 08 입력(context 네 칸)과 보호자 연락처를 인자로 받지 않는다 — 넣을 길이 없다(NFR3.1 · SD-20). practice는 U5 몫이라 넣지 않는다.
 */
@Component
public class SessionPackageBuilder {

    /** 기본 종료 표현(BR6.5 코드 상수). core_stop_expression은 U1이 비워 둔다. */
    public static final String DEFAULT_STOP_EXPRESSION = "무중아 그만할래";

    /** childContext.displayName 최대 길이(계약 스키마). */
    static final int DISPLAY_NAME_MAX = 10;
    /** childContext.age 범위(계약 스키마). */
    static final int AGE_MIN = 6;
    static final int AGE_MAX = 12;
    /** interests 항목 수 · 길이 상한(계약 스키마). */
    static final int INTERESTS_MAX = 10;
    static final int INTEREST_LENGTH_MAX = 20;

    private final JsonCodec json;

    public SessionPackageBuilder(JsonCodec json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 패키지를 만든다.
     *
     * @param policyVersion 동의 확인을 통과한 현재 정책 버전(4항목 모두 GIVEN)
     * @throws IllegalStateException 아이 정보가 계약 형식에 맞지 않을 때(값은 메시지에 넣지 않는다)
     */
    public SessionPackage build(
            UUID sessionId,
            UUID idempotencyKey,
            UUID guardianId,
            Child child,
            UUID deviceId,
            String policyVersion,
            ApprovedItem closing,
            ApprovedItem waitingFiller) {
        Objects.requireNonNull(child, "child");
        Map<String, ApprovedItem> approved = new LinkedHashMap<>();
        approved.put(ApprovedContent.CODE_CLOSING, Objects.requireNonNull(closing, "closing"));
        approved.put(ApprovedContent.CODE_WAITING_FILLER, Objects.requireNonNull(waitingFiller, "waitingFiller"));
        return new SessionPackage(
                sessionId,
                idempotencyKey,
                guardianId,
                child.getId(),
                deviceId,
                ActivityType.FREE_TALK,
                new ConsentSnapshot(policyVersion, true, true, true, true),
                childContext(child),
                List.of(new StopExpression(DEFAULT_STOP_EXPRESSION, true)),
                Map.copyOf(approved));
    }

    private ChildContext childContext(Child child) {
        String displayName = child.getDisplayName();
        Integer age = child.getAge();
        if (displayName == null || displayName.isBlank() || displayName.length() > DISPLAY_NAME_MAX) {
            throw new IllegalStateException("childContext.displayName이 계약 형식에 맞지 않는다");
        }
        if (age == null || age < AGE_MIN || age > AGE_MAX) {
            throw new IllegalStateException("childContext.age가 계약 범위(6 ~ 12) 밖이다");
        }
        List<String> interests = child.getInterestsJson() == null
                ? List.of()
                : Arrays.stream(json.read(child.getInterestsJson(), String[].class))
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(interest -> !interest.isEmpty())
                        .toList();
        if (interests.size() > INTERESTS_MAX || interests.stream().anyMatch(i -> i.length() > INTEREST_LENGTH_MAX)) {
            throw new IllegalStateException("childContext.interests가 계약 형식에 맞지 않는다");
        }
        return new ChildContext(displayName, age, interests);
    }

    /** {@code POST /internal/sessions} 본문. */
    public record SessionPackage(
            UUID sessionId,
            UUID idempotencyKey,
            UUID guardianId,
            UUID childId,
            UUID deviceId,
            ActivityType activityType,
            ConsentSnapshot consentSnapshot,
            ChildContext childContext,
            List<StopExpression> stopExpressions,
            Map<String, ApprovedItem> approvedContent) {

        public SessionPackage {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(guardianId, "guardianId");
            Objects.requireNonNull(childId, "childId");
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(activityType, "activityType");
            Objects.requireNonNull(consentSnapshot, "consentSnapshot");
            Objects.requireNonNull(childContext, "childContext");
            stopExpressions = List.copyOf(stopExpressions);
            approvedContent = Map.copyOf(approvedContent);
        }

        /** 아이 문맥은 문자열 표현에 내지 않는다(개인정보). */
        @Override
        public String toString() {
            return "SessionPackage[sessionId=" + sessionId + ", activityType=" + activityType + "]";
        }
    }

    /** 동의 스냅숏(정책 버전 + 4항목). */
    public record ConsentSnapshot(
            String policyVersion, boolean voiceAndText, boolean guardianView, boolean reportSharing, boolean rawAudio) {

        public ConsentSnapshot {
            if (policyVersion == null || policyVersion.isBlank()) {
                throw new IllegalArgumentException("policyVersion이 비었다");
            }
        }
    }

    /** 대화 역할용 최소 문맥(세 필드뿐, NFR3.1). */
    public record ChildContext(String displayName, int age, List<String> interests) {

        public ChildContext {
            interests = List.copyOf(interests);
        }

        @Override
        public String toString() {
            return "ChildContext[***]";
        }
    }

    /** 종료 표현 한 건. */
    public record StopExpression(String text, boolean isDefault) {
    }
}
