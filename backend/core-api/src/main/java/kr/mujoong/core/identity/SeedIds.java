package kr.mujoong.core.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * dev 시드 고정 값 (U1 3.5 Step 21 · BR8.4 · J117 · J114 · functional-spec 9절).
 *
 * <p>{@code infra/seed/001-reference.sql}(정책 1 · 승인 문구 2)과 {@link DevSeedRunner}가 같은 UUID를 쓴다. 바꾸면 두 곳을 함께 바꾼다.
 * 시각은 DB 시각 함수가 아니라 고정 값(시드 정의 시각)이다(R2 · NFR6.4). 합성 데이터이고 개인정보가 아니다.
 */
public final class SeedIds {

    /** 시드 보호자. */
    public static final UUID GUARDIAN = UUID.fromString("00000000-0000-4000-8000-000000000001");
    /** 시드 아이. */
    public static final UUID CHILD = UUID.fromString("00000000-0000-4000-8000-000000000002");
    /** 시드 동의 4행(VOICE_AND_TEXT · GUARDIAN_VIEW · REPORT_SHARING · RAW_AUDIO 순). */
    public static final List<UUID> CONSENTS = List.of(
            UUID.fromString("00000000-0000-4000-8000-000000000011"),
            UUID.fromString("00000000-0000-4000-8000-000000000012"),
            UUID.fromString("00000000-0000-4000-8000-000000000013"),
            UUID.fromString("00000000-0000-4000-8000-000000000014"));

    /** 승인 문구 종류 행(001-reference.sql과 같다). */
    public static final UUID CONTENT_CLOSING = UUID.fromString("00000000-0000-4000-8000-000000000031");
    public static final UUID CONTENT_WAITING_FILLER = UUID.fromString("00000000-0000-4000-8000-000000000032");
    public static final UUID CONTENT_CONSENT_POLICY = UUID.fromString("00000000-0000-4000-8000-000000000033");

    /** 승인 문구 버전 행(001-reference.sql과 같다). */
    public static final UUID VERSION_CLOSING = UUID.fromString("00000000-0000-4000-8000-000000000041");
    public static final UUID VERSION_WAITING_FILLER = UUID.fromString("00000000-0000-4000-8000-000000000042");
    public static final UUID VERSION_CONSENT_POLICY = UUID.fromString("00000000-0000-4000-8000-000000000043");

    /** 시드 아이 표시 이름 · 나이 · 관심사(J117). */
    public static final String CHILD_DISPLAY_NAME = "하늘";
    public static final int CHILD_AGE = 8;
    public static final String CHILD_INTERESTS_JSON = "[\"공룡\",\"그림\"]";

    /** 현재 정책 버전(J114 임시). */
    public static final String POLICY_VERSION = "1.0";

    /** 승인 문구(J117 임시)와 음성 파일 이름(J102). */
    public static final String CLOSING_TEXT = "오늘 이야기 즐거웠어. 다음에 또 만나!";
    public static final String CLOSING_AUDIO = "closing-v1.wav";
    public static final String WAITING_FILLER_TEXT = "음, 잠깐만 생각해 볼게.";
    public static final String WAITING_FILLER_AUDIO = "waiting-filler-v1.wav";

    /** 시드 정의 시각(001-reference.sql과 같다). */
    public static final Instant SEED_TIME = Instant.parse("2026-10-10T00:00:00Z");

    private SeedIds() {
    }
}
