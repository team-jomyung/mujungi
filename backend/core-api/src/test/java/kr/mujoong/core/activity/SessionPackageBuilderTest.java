package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.identity.Child;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 세션 패키지 조립 (BR6.5 · NFR3.1 · SD-20 · J117): 기본 종료 표현 한 건 · 동의 스냅숏 · 승인 문구 두 칸 · childContext 세 필드, 08 입력을
 * 받을 입구가 없음(구조), 계약 형식 밖 아이 정보는 값 없는 오류, 문자열 표현에 아이 정보 없음.
 */
@Tag("u1")
class SessionPackageBuilderTest {

    private static final Instant NOW = Instant.parse("2001-06-01T00:00:00Z");
    private static final ApprovedItem CLOSING = new ApprovedItem(UUID.randomUUID(), "오늘 이야기 즐거웠어.",
            "/media/approved/closing-v1.wav");
    private static final ApprovedItem FILLER = new ApprovedItem(UUID.randomUUID(), "음, 잠깐만 생각해 볼게.",
            "/media/approved/waiting-filler-v1.wav");

    private final SessionPackageBuilder builder = new SessionPackageBuilder(JsonCodec.shared());
    private final UUID guardianId = UUID.randomUUID();

    @Test
    void packageCarriesDefaultStopExpressionSnapshotApprovedContentAndThreeChildFields() {
        Child child = child("하늘", 8, "[\" 공룡 \",\"그림\",\"\",null]");
        UUID sessionId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionPackage pkg = builder.build(sessionId, key, guardianId, child, deviceId, "1.0", CLOSING, FILLER);

        assertThat(pkg.sessionId()).isEqualTo(sessionId);
        assertThat(pkg.idempotencyKey()).isEqualTo(key);
        assertThat(pkg.childId()).isEqualTo(child.getId());
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
        SessionPackage pkg = builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, child("하늘", 6, null),
                UUID.randomUUID(), "1.0", CLOSING, FILLER);

        assertThat(pkg.childContext().interests()).isEmpty();
        assertThat(pkg.childContext().age()).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 13})
    void ageOutsideTheContractRangeIsRejectedWithoutTheValue(int age) {
        Child child = child("하늘", age, null);

        assertThatThrownBy(() -> builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, child, UUID.randomUUID(),
                "1.0", CLOSING, FILLER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(String.valueOf(age))
                .hasMessageNotContaining("하늘");
    }

    @Test
    void displayNameAndInterestsOutsideTheContractAreRejectedWithoutTheValue() {
        Child longName = child("가나다라마바사아자차카", 9, null);
        Child blankName = child("  ", 9, null);
        String manyInterests = Arrays.toString(java.util.stream.IntStream.range(0, 11).mapToObj(i -> "\"i" + i + "\"")
                .toArray());
        Child tooMany = child("하늘", 9, manyInterests);
        Child tooLong = child("하늘", 9, "[\"" + "공".repeat(21) + "\"]");

        for (Child child : List.of(longName, blankName, tooMany, tooLong)) {
            assertThatThrownBy(() -> builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, child,
                    UUID.randomUUID(), "1.0", CLOSING, FILLER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("가나다")
                    .hasMessageNotContaining("공공");
        }
    }

    @Test
    void builderHasNoEntryForGuardianInputOrContact() {
        // SD-20 · NFR3.1: 08 입력(context 네 칸) · 보호자 연락처를 넘길 매개변수가 없다. 문자열 매개변수는 정책 버전 하나뿐이다.
        List<Method> publicBuilders = Arrays.stream(SessionPackageBuilder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(publicBuilders).extracting(Method::getName).containsExactly("build");
        Class<?>[] parameters = publicBuilders.get(0).getParameterTypes();
        assertThat(parameters).doesNotContain(StartActivityService.Context.class, StartActivityService.StartRequest.class);
        assertThat(Arrays.stream(parameters).filter(String.class::equals).count()).isEqualTo(1);
        assertThat(Arrays.stream(SessionPackage.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("sessionId", "idempotencyKey", "guardianId", "childId", "deviceId", "activityType",
                        "consentSnapshot", "childContext", "stopExpressions", "approvedContent");
    }

    @Test
    void stringFormsHideChildContext() {
        SessionPackage pkg = builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, child("하늘", 8, "[\"공룡\"]"),
                UUID.randomUUID(), "1.0", CLOSING, FILLER);

        assertThat(pkg.toString()).doesNotContain("하늘").doesNotContain("공룡");
        assertThat(pkg.childContext().toString()).doesNotContain("하늘").doesNotContain("공룡");
    }

    @Test
    void blankPolicyVersionIsRejected() {
        assertThatThrownBy(() -> builder.build(UUID.randomUUID(), UUID.randomUUID(), guardianId, child("하늘", 8, null),
                UUID.randomUUID(), " ", CLOSING, FILLER)).isInstanceOf(IllegalArgumentException.class);
    }

    private Child child(String displayName, Integer age, String interestsJson) {
        return new Child(UUID.randomUUID(), guardianId, displayName, age, interestsJson, NOW);
    }
}
