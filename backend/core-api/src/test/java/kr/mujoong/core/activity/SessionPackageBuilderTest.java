package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.child.ChildProfileService.ChildContext;
import kr.mujoong.core.child.ChildProfileService.StopExpression;
import kr.mujoong.core.consent.ConsentPolicyService.Snapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 세션 패키지 조립 (BR6.5 · NFR3.1 · SD-20 · J117): 기본 종료 표현 한 건 · 동의 스냅숏 · 승인 문구 두 칸 · childContext 세 필드, 08 입력을
 * 받을 입구가 없음(구조), 계약 형식 밖 아이 정보는 값 없는 오류, 문자열 표현에 아이 정보 없음.
 *
 * <p>U4a: 빌더는 계약 8절 포트 값(ChildContext · ConsentGate 스냅숏 · 고정한 승인 문구)으로 조립한다. 부르는 모양만 바꿨고 단언은 U1
 * 그대로다(문자열 매개변수 검사는 정책 버전이 스냅숏 안으로 들어가 '0개'로 좁혔다). 종료 표현 스냅숏 · 추가 문구는 {@code SessionPackageU4aTest}.
 */
@Tag("skeleton")
class SessionPackageBuilderTest {

    private static final ApprovedItem CLOSING = new ApprovedItem(UUID.randomUUID(), "오늘 이야기 즐거웠어.",
            "/media/approved/closing-v1.wav");
    private static final ApprovedItem FILLER = new ApprovedItem(UUID.randomUUID(), "음, 잠깐만 생각해 볼게.",
            "/media/approved/waiting-filler-v1.wav");
    private static final Snapshot CONSENT = new Snapshot("1.0", true, true, true, true);

    private final SessionPackageBuilder builder = new SessionPackageBuilder();
    private final UUID guardianId = UUID.randomUUID();

    @Test
    void packageCarriesDefaultStopExpressionSnapshotApprovedContentAndThreeChildFields() {
        ChildContext child = child("하늘", 8, List.of(" 공룡 ", "그림", ""));
        UUID sessionId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionPackage pkg = builder.build(sessionId, key, guardianId, deviceId, child, CONSENT, approved());

        assertThat(pkg.sessionId()).isEqualTo(sessionId);
        assertThat(pkg.idempotencyKey()).isEqualTo(key);
        assertThat(pkg.childId()).isEqualTo(child.childId());
        assertThat(pkg.deviceId()).isEqualTo(deviceId);
        assertThat(pkg.activityType()).isEqualTo(ActivityType.FREE_TALK);
        assertThat(pkg.stopExpressions()).singleElement().satisfies(stop -> {
            assertThat(stop.text()).isEqualTo("무중아 그만할래");
            assertThat(stop.isDefault()).isTrue();
        });
        assertThat(pkg.consentSnapshot().policyVersion()).isEqualTo("1.0");
        assertThat(pkg.consentSnapshot().voiceAndText() && pkg.consentSnapshot().guardianView()
                && pkg.consentSnapshot().reportSharing() && pkg.consentSnapshot().rawAudio()).isTrue();
        assertThat(pkg.approvedContent()).containsOnlyKeys("CLOSING", "WAITING_FILLER")
                .containsEntry("CLOSING", CLOSING).containsEntry("WAITING_FILLER", FILLER);
        assertThat(pkg.childContext().displayName()).isEqualTo("하늘");
        assertThat(pkg.childContext().age()).isEqualTo(8);
        assertThat(pkg.childContext().interests()).containsExactly("공룡", "그림");
        assertThat(JsonCodec.shared().write(pkg.childContext())).doesNotContain("guardian").doesNotContain("id");
    }

    @Test
    void missingInterestsBecomeAnEmptyList() {
        SessionPackage pkg = builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, UUID.randomUUID(),
                child("하늘", 6, List.of()), CONSENT, approved());

        assertThat(pkg.childContext().interests()).isEmpty();
        assertThat(pkg.childContext().age()).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, -1})
    void ageOutsideTheContractRangeIsRejectedWithoutTheValue(int age) {
        ChildContext child = child("하늘", age, List.of());

        assertThatThrownBy(() -> builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, UUID.randomUUID(), child,
                CONSENT, approved()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(String.valueOf(age))
                .hasMessageNotContaining("하늘");
    }

    @Test
    void displayNameAndInterestsOutsideTheContractAreRejectedWithoutTheValue() {
        ChildContext longName = child("가나다라마바사아자차카", 9, List.of());
        ChildContext blankName = child("  ", 9, List.of());
        List<String> manyInterests = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "i" + i).toList();
        ChildContext tooMany = child("하늘", 9, manyInterests);
        ChildContext tooLong = child("하늘", 9, List.of("공".repeat(21)));

        for (ChildContext child : List.of(longName, blankName, tooMany, tooLong)) {
            assertThatThrownBy(() -> builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, UUID.randomUUID(),
                    child, CONSENT, approved()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("가나다")
                    .hasMessageNotContaining("공공");
        }
    }

    @Test
    void builderHasNoEntryForGuardianInputOrContact() {
        // SD-20 · NFR3.1: 08 입력(context 네 칸) · 보호자 연락처를 넘길 매개변수가 없다. 문자열 매개변수도 없다(정책 버전은 동의 스냅숏 안).
        List<Method> publicBuilders = Arrays.stream(SessionPackageBuilder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(publicBuilders).extracting(Method::getName).containsExactly("build");
        Class<?>[] parameters = publicBuilders.get(0).getParameterTypes();
        assertThat(parameters).doesNotContain(StartActivityService.Context.class, StartActivityService.StartRequest.class);
        assertThat(Arrays.stream(parameters).filter(String.class::equals).count()).isZero();
        assertThat(Arrays.stream(SessionPackage.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("sessionId", "idempotencyKey", "guardianId", "childId", "deviceId", "activityType",
                        "consentSnapshot", "childContext", "stopExpressions", "approvedContent");
    }

    @Test
    void stringFormsHideChildContext() {
        SessionPackage pkg = builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, UUID.randomUUID(),
                child("하늘", 8, List.of("공룡")), CONSENT, approved());

        assertThat(pkg.toString()).doesNotContain("하늘").doesNotContain("공룡");
        assertThat(pkg.childContext().toString()).doesNotContain("하늘").doesNotContain("공룡");
    }

    @Test
    void blankPolicyVersionIsRejected() {
        assertThatThrownBy(() -> builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, UUID.randomUUID(),
                child("하늘", 8, List.of()), new Snapshot(" ", true, true, true, true), approved()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChildContext child(String displayName, int age, List<String> interests) {
        return new ChildContext(UUID.randomUUID(), displayName, age, interests,
                List.of(new StopExpression(SessionPackageBuilder.DEFAULT_STOP_EXPRESSION, true)));
    }

    private static Map<String, ApprovedItem> approved() {
        Map<String, ApprovedItem> approved = new LinkedHashMap<>();
        approved.put("CLOSING", CLOSING);
        approved.put("WAITING_FILLER", FILLER);
        return approved;
    }
}
