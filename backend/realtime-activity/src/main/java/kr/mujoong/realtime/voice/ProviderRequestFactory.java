package kr.mujoong.realtime.voice;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.session.ChildContext;
import org.springframework.stereotype.Component;

/**
 * 공급자로 가는 모든 요청을 만드는 유일한 팩토리 (U1 3.5 Step 25 · SD-20 · NFR3.1 · NFR3.2 · J110 · contracts/ai-io.md 3절).
 *
 * <p>요청 타입은 아래 레코드 셋뿐이고 필드는 지시문 · childContext 세 필드(displayName · age · interests) · 음성 프레임뿐이다. 자유
 * {@code Map} · 문자열 이어 붙이기 입구가 없고 판정 기준 · 보호자 입력 · 이전 판정 · 식별자 필드는 타입에 없다(SD-20 구조 규칙). childContext
 * 값은 지시문 안에서 따옴표로 묶은 데이터로만 쓰고 지시 문장으로 쓰지 않는다. 길이 상한(이름 10자 · 관심사 10개 × 20자, 계약 C8)은 입력
 * 단계가 검증하고 여기서 한 번 더 자른다.
 *
 * <p>U3(편차 (aa) · ai-io.md 3절): 응답 취소 {@code session.response.cancel {responseId?}}(끼어들기 · 종료 의도, U3-J11)와 지시 교정
 * {@code session.instructions.correct {text}}(게이트 폐기 뒤 고정 문장, U3-J12)를 더한다. 둘 다 입구는 형식을 지킨 공급자 응답 id와 고정 교정
 * 범주뿐이다(자유 문자열 없음). 역할 지시문 문장은 {@link DialogInstructions}가 맡는다(역할 지식 분리, U3-R14).
 */
@Component
public class ProviderRequestFactory {

    /** 공급자 모델(코드 상수, BR7.2 · ai-io 2절). */
    public static final String MODEL = "gpt-live-1";
    /** 입출력 음성 형식(J27). */
    public static final String AUDIO_FORMAT = "pcm16le/24000/1";
    /** 선 형식 type 값(J110). */
    public static final String TYPE_SESSION_START = "session.start";
    public static final String TYPE_INPUT_AUDIO_APPEND = "session.input_audio.append";
    public static final String TYPE_SESSION_CLOSE = "session.close";
    /** U3 선 형식 type 값(편차 (aa)). */
    public static final String TYPE_RESPONSE_CANCEL = "session.response.cancel";
    public static final String TYPE_INSTRUCTIONS_CORRECT = "session.instructions.correct";

    static final int MAX_DISPLAY_NAME = 10;
    static final int MAX_INTERESTS = 10;
    static final int MAX_INTEREST_LENGTH = 20;
    static final int MIN_AGE = 6;
    static final int MAX_AGE = 12;

    /** {@code session.start}: 지시문 · childContext 세 필드 · 음성 형식. */
    public record SessionStart(String type, String model, String instructions, ChildContextData childContext,
            AudioSpec audio) {
    }

    /** 대화 역할용 최소 문맥 세 필드(NFR3.1). */
    public record ChildContextData(String displayName, Integer age, List<String> interests) {

        public ChildContextData {
            interests = interests == null ? List.of() : List.copyOf(interests);
        }
    }

    /** 음성 형식 묶음. */
    public record AudioSpec(String input, String output) {
    }

    /** {@code session.input_audio.append}: 기기 0x01 프레임 본문(960바이트)을 base64로. */
    public record InputAudioAppend(String type, String audio) {
    }

    /** {@code session.close}. */
    public record SessionClose(String type) {
    }

    /** U3 {@code session.response.cancel}: 응답 id가 없으면 지금 만드는 응답을 멈춘다. */
    public record ResponseCancel(String type, String responseId) {
    }

    /** U3 {@code session.instructions.correct}: 고정 교정 문장 하나. */
    public record InstructionsCorrect(String type, String text) {
    }

    /** 세션 시작 요청. childContext만 받는다(세션 패키지 전체 · 08 문맥을 인자로 받지 않는다). */
    public SessionStart sessionStart(ChildContext context) {
        Objects.requireNonNull(context, "context");
        ChildContextData data = new ChildContextData(
                limit(context.displayName(), MAX_DISPLAY_NAME),
                context.age() == null ? null : Math.max(MIN_AGE, Math.min(MAX_AGE, context.age())),
                limitInterests(context.interests()));
        return new SessionStart(TYPE_SESSION_START, MODEL, instructions(data), data,
                new AudioSpec(AUDIO_FORMAT, AUDIO_FORMAT));
    }

    /** 입력 음성 한 프레임. 본문은 변환하지 않는다(J131 무변환). */
    public InputAudioAppend inputAudio(byte[] pcm) {
        Objects.requireNonNull(pcm, "pcm");
        if (pcm.length != AudioFrameCodec.INPUT_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("입력 프레임 본문은 " + AudioFrameCodec.INPUT_PAYLOAD_BYTES + "바이트다");
        }
        return new InputAudioAppend(TYPE_INPUT_AUDIO_APPEND, Base64.getEncoder().encodeToString(pcm));
    }

    /** 공급자 세션 정리. */
    public SessionClose sessionClose() {
        return new SessionClose(TYPE_SESSION_CLOSE);
    }

    /** U3 응답 취소(끼어들기 · 종료 의도). {@code responseId}가 없으면 지금 응답을 멈춘다. */
    public ResponseCancel responseCancel(ProviderResponseId responseId) {
        return new ResponseCancel(TYPE_RESPONSE_CANCEL, responseId == null ? null : responseId.value());
    }

    /** U3 지시 교정: 범주의 고정 문장만 보낸다(폐기된 전사 · 걸린 패턴 없음). */
    public InstructionsCorrect instructionsCorrect(DialogInstructions.Correction correction) {
        return new InstructionsCorrect(TYPE_INSTRUCTIONS_CORRECT, Objects.requireNonNull(correction, "correction").text());
    }

    /** 서버 템플릿 지시문(문장은 DialogPolicy의 역할 지시문, U3-R14). 아이 문맥 값은 따옴표 안 데이터로만 들어간다. */
    static String instructions(ChildContextData data) {
        return DialogInstructions.role(data.displayName(), data.age(), data.interests());
    }

    private static List<String> limitInterests(List<String> interests) {
        List<String> limited = new ArrayList<>();
        for (String interest : interests) {
            if (interest == null || interest.isBlank()) {
                continue;
            }
            if (limited.size() == MAX_INTERESTS) {
                break;
            }
            limited.add(limit(interest, MAX_INTEREST_LENGTH));
        }
        return limited;
    }

    private static String limit(String value, int max) {
        String trimmed = value.strip();
        return trimmed.codePointCount(0, trimmed.length()) <= max
                ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, max));
    }
}
