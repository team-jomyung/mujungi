package kr.mujoong.realtime.speech;

/**
 * 대본 줄 음성의 말투(3회차 V1). 목소리는 하나({@code mujoong.speech-clip.voice})이고 말투 지시만 다르다.
 */
public enum SpeechClipVoice {
    /** 무중이 역할 밖에서 하는 설명 · 제안 · 알림({@code mujoong.speech-clip.narrator-style}). */
    NARRATOR,
    /** 장면 속 친구의 대사({@code mujoong.speech-clip.peer-style}). */
    PEER
}
