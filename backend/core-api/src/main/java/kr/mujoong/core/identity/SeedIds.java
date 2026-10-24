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

    /**
     * U8 승인 발화(APPROVED_SPEECH, {@code infra/seed/004-u8-reference.sql}과 같은 UUID · 글 · 시각). 종류 행 HELP_OFFER는 002의 …0ab를
     * 그대로 쓰고, 새 code는 …8a1 ~ …8a4, 버전 행은 …8b1 ~ …8b5다. 글은 AI 동작 명세 v0.2 §3.1 · §8.5에서 글자 그대로 옮겼다.
     */
    public static final UUID CONTENT_HELP_OFFER = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
    public static final UUID CONTENT_D_REST_ACK_START = UUID.fromString("00000000-0000-4000-8000-0000000008a1");
    public static final UUID CONTENT_D_REST_ACK = UUID.fromString("00000000-0000-4000-8000-0000000008a2");
    public static final UUID CONTENT_COACH_SWITCH = UUID.fromString("00000000-0000-4000-8000-0000000008a3");
    public static final UUID CONTENT_PEER_RETURN = UUID.fromString("00000000-0000-4000-8000-0000000008a4");

    public static final UUID VERSION_HELP_OFFER_V2 = UUID.fromString("00000000-0000-4000-8000-0000000008b1");
    public static final UUID VERSION_D_REST_ACK_START = UUID.fromString("00000000-0000-4000-8000-0000000008b2");
    public static final UUID VERSION_D_REST_ACK = UUID.fromString("00000000-0000-4000-8000-0000000008b3");
    public static final UUID VERSION_COACH_SWITCH = UUID.fromString("00000000-0000-4000-8000-0000000008b4");
    public static final UUID VERSION_PEER_RETURN = UUID.fromString("00000000-0000-4000-8000-0000000008b5");

    public static final String HELP_OFFER_TEXT = "무중이가 조금 도와줄까?";
    public static final String D_REST_ACK_START_TEXT = "잠깐 쉬고 싶구나. 괜찮아. 준비되면 말해 줘.";
    public static final String D_REST_ACK_TEXT = "잠깐 쉬고 싶구나. 천천히 해도 괜찮아.";
    public static final String COACH_SWITCH_TEXT = "잠깐, 무중이 도와줄게";
    public static final String PEER_RETURN_TEXT = "다시 친구 역할로 돌아갈게";

    /** U8 시드 정의 시각(004-u8-reference.sql과 같다). */
    public static final Instant U8_SEED_TIME = Instant.parse("2026-10-16T00:00:00Z");

    /**
     * 연습 대화 C안 고정 승인 발화(APPROVED_SPEECH, {@code infra/seed/006-practice-direct-ask-reference.sql}과 같은 UUID · 글 · 시각). 종류 행은
     * …8a5 ~ …8aa, 판 행은 …8b6 ~ …8bb이고 모두 v1이다. 글은 docs/practice-direct-ask.md 5장 표에서 글자 그대로 옮겼다.
     */
    public static final UUID CONTENT_VARIANT_OFFER = UUID.fromString("00000000-0000-4000-8000-0000000008a5");
    public static final UUID CONTENT_RETRY_PROMPT = UUID.fromString("00000000-0000-4000-8000-0000000008a6");
    public static final UUID CONTENT_INPUT_CONFIRM = UUID.fromString("00000000-0000-4000-8000-0000000008a7");
    public static final UUID CONTENT_SCENE_CLOSE = UUID.fromString("00000000-0000-4000-8000-0000000008a8");
    public static final UUID CONTENT_WAIT_LEAD = UUID.fromString("00000000-0000-4000-8000-0000000008a9");
    public static final UUID CONTENT_ASK_DEFAULT = UUID.fromString("00000000-0000-4000-8000-0000000008aa");

    public static final UUID VERSION_VARIANT_OFFER = UUID.fromString("00000000-0000-4000-8000-0000000008b6");
    public static final UUID VERSION_RETRY_PROMPT = UUID.fromString("00000000-0000-4000-8000-0000000008b7");
    public static final UUID VERSION_INPUT_CONFIRM = UUID.fromString("00000000-0000-4000-8000-0000000008b8");
    public static final UUID VERSION_SCENE_CLOSE = UUID.fromString("00000000-0000-4000-8000-0000000008b9");
    public static final UUID VERSION_WAIT_LEAD = UUID.fromString("00000000-0000-4000-8000-0000000008ba");
    public static final UUID VERSION_ASK_DEFAULT = UUID.fromString("00000000-0000-4000-8000-0000000008bb");

    public static final String VARIANT_OFFER_TEXT = "비슷한 이야기로 한 번 더 해 볼까?";
    public static final String RETRY_PROMPT_TEXT = "네 말로 한 번 말해 볼래?";
    public static final String INPUT_CONFIRM_TEXT = "잘 못 들었어. 한 번 더 말해 줄래?";
    public static final String SCENE_CLOSE_TEXT = "알겠어. 이 이야기는 여기까지 할게.";
    public static final String WAIT_LEAD_TEXT = "천천히 생각해도 돼.";
    public static final String ASK_DEFAULT_TEXT = "너라면 친구한테 뭐라고 말할래?";

    /** C안 시드 정의 시각(006-practice-direct-ask-reference.sql과 같다). */
    public static final Instant DIRECT_ASK_SEED_TIME = Instant.parse("2026-10-24T00:00:00Z");

    private SeedIds() {
    }
}
