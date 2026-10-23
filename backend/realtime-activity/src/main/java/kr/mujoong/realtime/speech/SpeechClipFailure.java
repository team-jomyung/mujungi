package kr.mujoong.realtime.speech;

/**
 * 대본 줄 음성을 만들지 못한 까닭(3회차 V1). 사유 코드만 남기고 원문 · 응답 본문 · 키는 남기지 않는다(R-F14). 어느 사유든 그 줄은 낼 차례에 GPT-Live
 * 원문 읽기로 낸다.
 */
public enum SpeechClipFailure {
    /** 합성이 꺼졌다(스위치 꺼짐 · 키 없음). */
    DISABLED,
    /** 차례를 기다리는 합성 요청이 상한에 닿았다. */
    OVERLOADED,
    /** 대본 줄이 비었거나 입력 상한을 넘는다. */
    INVALID_INPUT,
    /** 보낼 주소가 허용 규칙 밖이다(보내지 않았다). */
    REJECTED_URL,
    /** 보낼 때 키가 없다(보내지 않았다). */
    NO_KEY,
    /** 합성 시한({@code timing.speech-clip.synthesis-timeout})을 넘었다. */
    TIMEOUT,
    /** 인증 실패(401 · 403). */
    AUTH_FAILED,
    /** 요청 한도(429). */
    RATE_LIMITED,
    /** 그 밖의 요청 거절(4xx). */
    REQUEST_REJECTED,
    /** 공급자 오류(5xx). */
    PROVIDER_ERROR,
    /** 연결 · 전송 오류. */
    TRANSPORT_ERROR,
    /** 음성이 비었다. */
    EMPTY_AUDIO,
    /** 음성이 PCM16 표본 배수가 아니다. */
    INVALID_AUDIO,
    /** 음성이 출력 구간 상한(20초 분량)을 넘는다. */
    AUDIO_TOO_LONG
}
