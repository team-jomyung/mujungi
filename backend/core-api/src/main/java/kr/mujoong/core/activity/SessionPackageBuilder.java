package kr.mujoong.core.activity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.consent.ConsentPolicyService;
import org.springframework.stereotype.Component;

/**
 * 세션 패키지 조립 (U1 3.5 Step 22 · BR6.5 · NFR3.1 · SD-20 · J11 · J117, U4a가 계약 8절 포트 값으로 조립하게 바꿈 · AC2.3.7 · AC6.3.7).
 *
 * <p>Realtime {@code POST /internal/sessions} 본문(internal-session-start-request.schema.json)을 만든다. 담는 것은 sessionId · 멱등 키 ·
 * 보호자 · 아이 · 기기 id, activityType FREE_TALK, consentSnapshot(ConsentGate 포트의 정책 버전 + 4항목), childContext(ChildContext 포트의
 * displayName · age · interests 세 필드뿐 — 앞뒤 공백을 걷고 빈 항목은 뺀다), stopExpressions(ChildContext 포트의 종료 표현 스냅숏 —
 * 기본 '무중아 그만할래'가 맨 앞, 아이 추가 표현이 뒤), approvedContent(CLOSING · WAITING_FILLER 필수 + 시작 서비스가 고른 승인 문구). 이
 * 빌더는 08 입력(context 네 칸)과 보호자 연락처를 인자로 받지 않는다 — 넣을 길이 없다(NFR3.1 · SD-20). practice는 U5 몫이라 넣지 않는다.
 * 계약 형식 밖 값은 값을 메시지에 넣지 않고 IllegalStateException으로 시작을 멈춘다(J343).
 */
@Component
public class SessionPackageBuilder {

    /** 기본 종료 표현(BR6.5). U2 ChildContext 포트도 같은 값을 맨 앞에 둔다. */
    public static final String DEFAULT_STOP_EXPRESSION = "무중아 그만할래";

    /** stopExpressions.text 최대 길이(계약 스키마). */
    static final int STOP_EXPRESSION_MAX = 20;

    /** childContext.displayName 최대 길이(계약 스키마). */
    static final int DISPLAY_NAME_MAX = 10;
    /** childContext.age 범위(계약 스키마). */
    static final int AGE_MIN = 6;
    static final int AGE_MAX = 12;
    /** interests 항목 수 · 길이 상한(계약 스키마). */
    static final int INTERESTS_MAX = 10;
    static final int INTEREST_LENGTH_MAX = 20;

    /**
     * 패키지를 만든다.
     *
     * @param child ChildContext 포트 값(아이 · 종료 표현 스냅숏)
     * @param consent ConsentGate 포트가 통과시킨 동의 스냅숏(4항목 모두 GIVEN)
     * @param approvedContent 고정한 승인 문구(CLOSING · WAITING_FILLER 필수, 입력 순서를 지킨다)
     * @throws IllegalStateException 아이 정보 · 종료 표현이 계약 형식에 맞지 않거나 필수 문구가 없을 때(값은 메시지에 넣지 않는다)
     */
    public SessionPackage build(
            UUID sessionId,
            UUID idempotencyKey,
            UUID guardianId,
            UUID deviceId,
            ChildProfileService.ChildContext child,
            ConsentPolicyService.Snapshot consent,
            Map<String, ApprovedItem> approvedContent) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(consent, "consent");
        Objects.requireNonNull(approvedContent, "approvedContent");
        if (!approvedContent.containsKey(ApprovedContent.CODE_CLOSING)
                || !approvedContent.containsKey(ApprovedContent.CODE_WAITING_FILLER)) {
            throw new IllegalStateException("approvedContent에 CLOSING · WAITING_FILLER가 없다(시드 확인)");
        }
        return new SessionPackage(
                sessionId,
                idempotencyKey,
                guardianId,
                child.childId(),
                deviceId,
                ActivityType.FREE_TALK,
                new ConsentSnapshot(consent.policyVersion(), consent.voiceAndText(), consent.guardianView(),
                        consent.reportSharing(), consent.rawAudio()),
                childContext(child),
                stopExpressions(child.stopExpressions()),
                approvedContent);
    }

    private static ChildContext childContext(ChildProfileService.ChildContext child) {
        String displayName = child.displayName();
        int age = child.age();
        if (displayName == null || displayName.isBlank() || displayName.length() > DISPLAY_NAME_MAX) {
            throw new IllegalStateException("childContext.displayName이 계약 형식에 맞지 않는다");
        }
        if (age < AGE_MIN || age > AGE_MAX) {
            throw new IllegalStateException("childContext.age가 계약 범위(6 ~ 12) 밖이다");
        }
        List<String> interests = child.interests().stream()
                .map(String::strip)
                .filter(interest -> !interest.isEmpty())
                .toList();
        if (interests.size() > INTERESTS_MAX || interests.stream().anyMatch(i -> i.length() > INTEREST_LENGTH_MAX)) {
            throw new IllegalStateException("childContext.interests가 계약 형식에 맞지 않는다");
        }
        return new ChildContext(displayName, age, interests);
    }

    /** 종료 표현 스냅숏(AC2.3.7): 기본 표현이 맨 앞에 정확히 하나, 글은 계약 길이(1 ~ 20) 안. */
    private static List<StopExpression> stopExpressions(List<ChildProfileService.StopExpression> stops) {
        List<StopExpression> snapshot = stops.stream().map(stop -> new StopExpression(stop.text(), stop.isDefault())).toList();
        boolean defaultFirst = !snapshot.isEmpty() && snapshot.get(0).isDefault()
                && DEFAULT_STOP_EXPRESSION.equals(snapshot.get(0).text())
                && snapshot.stream().filter(StopExpression::isDefault).count() == 1;
        boolean lengthsOk = snapshot.stream().allMatch(stop -> stop.text() != null && !stop.text().isBlank()
                && stop.text().codePointCount(0, stop.text().length()) <= STOP_EXPRESSION_MAX);
        if (!defaultFirst || !lengthsOk) {
            throw new IllegalStateException("stopExpressions가 계약 형식에 맞지 않는다");
        }
        return snapshot;
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
            // 입력 순서(CLOSING · WAITING_FILLER가 앞)를 지키는 바꿀 수 없는 지도
            approvedContent = Collections.unmodifiableMap(new LinkedHashMap<>(approvedContent));
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
