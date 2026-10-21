package kr.mujoong.realtime.voice;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.dialog.PolicyBundle;
import kr.mujoong.realtime.dialog.ServicePolicyCatalog;
import kr.mujoong.contract.status.ActivityType;
import org.springframework.beans.factory.annotation.Autowired;
import kr.mujoong.realtime.session.ChildContext;
import org.springframework.stereotype.Component;

/**
 * 공급자로 가는 모든 요청을 만드는 유일한 팩토리 (U1 3.5 Step 25 · SD-20 · NFR3.1 · NFR3.2 · U11 BR11.1 · BR11.2 · contracts/ai-io.md 3절).
 *
 * <p>요청 타입은 아래 <b>네 가지</b>뿐이다(실호출로 확인한 지원 type 안에서만 보낸다, U11-a · 설계 개정 A1): {@code session.start} ·
 * {@code session.input_audio.append} · {@code session.instructions.append} · {@code session.close}. {@code response.create}는 없다 —
 * client 위임 세션은 거절한다. 모든 서버 발화는 지시 문장 하나({@code append})로 시작한다. 필드는 지시문 · 음성 프레임뿐이고 아이 정보(이름 · 나이 ·
 * 관심사)는 지시문 문장 안에 따옴표로 묶은 데이터로만 들어간다(별도 필드 없음).
 * 자유 {@code Map} · 문자열 이어 붙이기 입구가 없고 판정 기준 · 보호자 입력 · 이전 판정 · 식별자 필드는 타입에 없다(SD-20 구조 규칙).
 * 길이 상한(이름 10자 · 관심사 10개 x 20자, 계약 C8)은 입력 단계가 검증하고 여기서 한 번 더 자른다.
 *
 * <p>U11: 응답 취소 {@code session.response.cancel}과 지시 교정 {@code session.instructions.correct}는 공급자 지원 목록에 없어 없앴다.
 * 끼어들기 · 종료 의도 · 연습 폐기는 서버 출력 세대로 처리한다. 추가한 지시는 이후 응답에도 남고 지울 방법이 없으므로(설계 개정 A6) 모든 지시문은
 * "이번 한 번만 …, 그 뒤에는 평소처럼 …"으로 범위를 스스로 닫는다({@link DialogInstructions#scoped}). 역할 지시문 문장은
 * {@link DialogInstructions}가 맡는다(역할 지식 분리, U3-R14).
 */
@Component
public class ProviderRequestFactory {

    private final ServicePolicyCatalog policies;

    public ProviderRequestFactory() {
        this(new ServicePolicyCatalog());
    }

    @Autowired
    public ProviderRequestFactory(ServicePolicyCatalog policies) {
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    /** 세션이 고정할 참조다. 연습은 기존 정책 입구를 사용한다. */
    public PolicyBundle servicePolicy(ActivityType mode) {
        return policies.select(mode).orElse(null);
    }

    /** 공급자 모델(코드 상수, BR7.2 · ai-io 2절). */
    public static final String MODEL = "gpt-live-1";
    /** 음성 형식 type(실호출 확인, U11-a). */
    public static final String AUDIO_TYPE = "audio/pcm";
    /** 기본 입출력 표본율(J27). 24kHz 수락은 실호출로 확인한다(W7 ①). */
    public static final int AUDIO_RATE = 24_000;
    /** 선 형식 type 값. */
    public static final String TYPE_SESSION_START = "session.start";
    public static final String TYPE_INPUT_AUDIO_APPEND = "session.input_audio.append";
    public static final String TYPE_INSTRUCTIONS_APPEND = "session.instructions.append";
    public static final String TYPE_SESSION_CLOSE = "session.close";
    /** 송신할 수 있는 type 전부(지원 목록 안, 시험이 대조한다). */
    public static final java.util.Set<String> SENT_TYPES = java.util.Set.of(TYPE_SESSION_START, TYPE_INPUT_AUDIO_APPEND,
            TYPE_INSTRUCTIONS_APPEND, TYPE_SESSION_CLOSE);

    static final int MAX_DISPLAY_NAME = 10;
    static final int MAX_INTERESTS = 10;
    static final int MAX_INTEREST_LENGTH = 20;
    static final int MIN_AGE = 6;
    static final int MAX_AGE = 12;

    /** {@code session.start}: 중첩 {@code session{model, instructions, audio{format{type, rate}}}}. */
    public record SessionStart(String type, SessionBody session) {
    }

    /** {@code session.start}의 {@code session} 객체. */
    public record SessionBody(String model, String instructions, AudioSpec audio) {
    }

    /** 음성 형식 묶음. */
    public record AudioSpec(AudioFormat format) {
    }

    /** 음성 형식(type + 표본율). */
    public record AudioFormat(String type, int rate) {
    }

    /** {@code session.input_audio.append}: 기기 0x01 프레임 본문(960바이트)을 base64로. */
    public record InputAudioAppend(String type, String audio) {
    }

    /**
     * {@code session.instructions.append}(실호출 관측 형식): {@code {type, event_id, delegation_id: null, content}}. {@code delegation_id}는 값이
     * null이어도 <b>키가 반드시 있어야</b> 한다(없으면 공급자가 {@code missing_required_parameter}로 거절한다). 이 프로젝트 JSON 코덱이 null 필드를
     * 쓰지 않으므로 이 속성만 항상 쓰게 한다. {@code event_id}는 공급자가 확인 사건({@code session.instructions.appended})의
     * {@code client_event_id}로 되돌려 주는 서버 발급 식별자다. {@code content}는 500토큰 이하 한 문장 묶음이다.
     */
    public record InstructionsAppend(
            String type,
            @JsonProperty("event_id") String eventId,
            @JsonProperty("delegation_id") @JsonInclude(JsonInclude.Include.ALWAYS) String delegationId,
            String content) {

        public InstructionsAppend {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(content, "content");
        }
    }

    /** {@code session.close}. */
    public record SessionClose(String type) {
    }

    /** 대화 역할용 최소 문맥 세 필드(NFR3.1). 지시문 문장을 만드는 데만 쓰고 선 형식 필드로 보내지 않는다. */
    private record ChildContextData(String displayName, Integer age, List<String> interests) {

        ChildContextData {
            interests = interests == null ? List.of() : List.copyOf(interests);
        }
    }

    /** 세션 시작 요청(기본 표본율). childContext만 받는다(세션 패키지 전체 · 08 문맥을 인자로 받지 않는다). */
    public SessionStart sessionStart(ChildContext context) {
        return sessionStart(context, AUDIO_RATE);
    }

    /** 세션 시작 요청(표본율 지정, 실호출 도구가 16kHz 같은 값을 시험한다). */
    public SessionStart sessionStart(ChildContext context, int rate) {
        Objects.requireNonNull(context, "context");
        if (rate <= 0) {
            throw new IllegalArgumentException("표본율은 0보다 커야 한다");
        }
        ChildContextData data = new ChildContextData(
                limit(context.displayName(), MAX_DISPLAY_NAME),
                context.age() == null ? null : Math.max(MIN_AGE, Math.min(MAX_AGE, context.age())),
                limitInterests(context.interests()));
        return new SessionStart(TYPE_SESSION_START,
                new SessionBody(MODEL, instructions(data), new AudioSpec(new AudioFormat(AUDIO_TYPE, rate))));
    }

    /** 현재 자유대화 세션에 고정한 정책을 한 번만 포함한다. 내부 판/해시는 wire 필드가 아니다. */
    public SessionStart sessionStart(ChildContext context, PolicyBundle policy) {
        Objects.requireNonNull(context, "context");
        String instructions = DialogInstructions.serviceRole(Objects.requireNonNull(policy, "policy"),
                limit(context.displayName(), MAX_DISPLAY_NAME),
                context.age() == null ? null : Math.max(MIN_AGE, Math.min(MAX_AGE, context.age())),
                limitInterests(context.interests()));
        return new SessionStart(TYPE_SESSION_START,
                new SessionBody(MODEL, instructions, new AudioSpec(new AudioFormat(AUDIO_TYPE, AUDIO_RATE))));
    }

    public InstructionsAppend responseStart(String eventId, InstructionTemplates.ResponseStart start, ActivityType mode) {
        return instructionsAppend(eventId, Objects.requireNonNull(start, "start").content(mode));
    }

    public InstructionsAppend instructionsAppend(String eventId, DialogInstructions.Correction correction, ActivityType mode) {
        return instructionsAppend(eventId, InstructionTemplates.correctionContent(correction, mode));
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

    /** 지시 하나: 본문은 호출자가 범위를 닫은 문장이다. {@code delegation_id}는 항상 null이다(client 위임 세션). */
    public InstructionsAppend instructionsAppend(String eventId, String content) {
        return new InstructionsAppend(TYPE_INSTRUCTIONS_APPEND, eventId, null, Objects.requireNonNull(content, "content"));
    }

    /** 게이트 폐기 뒤 교정: 범주의 고정 문장만 보낸다(폐기된 전사 · 걸린 패턴 없음). 짧은 맞장구로 시작하고 범위를 닫는다. */
    public InstructionsAppend instructionsAppend(String eventId, DialogInstructions.Correction correction) {
        return instructionsAppend(eventId, InstructionTemplates.correctionContent(Objects.requireNonNull(correction, "correction")));
    }

    /** 발언권을 모델에 돌려주는 지시(참여 뒤 첫 응답 · 확인 질문 뒤 이어 가기 · 연습 requestResponse). 응답 요청 type이 없어 지시가 응답을 시작한다. */
    public InstructionsAppend responseStart(String eventId, InstructionTemplates.ResponseStart start) {
        return instructionsAppend(eventId, Objects.requireNonNull(start, "start").content());
    }

    /** 서버 템플릿 지시문(문장은 DialogPolicy의 역할 지시문, U3-R14). 아이 문맥 값은 따옴표 안 데이터로만 들어간다. */
    static String instructions(ChildContextData data) {
        // U4b(BR2.7): 역할 지시문 끝에 참여 확인 뒤 첫 질문 문장을 더한다
        return DialogInstructions.role(data.displayName(), data.age(), data.interests()) + " "
                + DialogInstructions.JOIN_FIRST_QUESTION;
    }

    /**
     * U4b 연습 확장점의 지시문 추가(BR10.4 · {@code instructProvider}): {@code session.instructions.append}로 보낸다. 문장은 확장점(U5b)이 자기
     * 승인된 템플릿에서 만든 것이고, 검사된 값 타입({@link PracticeInstruction})으로만 받는다(자유 문자열 입구 없음). 범위를 닫는 문장은 여기서
     * 더한다.
     */
    public InstructionsAppend practiceInstruction(String eventId, PracticeInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction");
        return instructionsAppend(eventId, InstructionTemplates.scoped(instruction.text()));
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
