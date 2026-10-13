package kr.mujoong.realtime.voice;

/**
 * 입력 중계가 공급자로 보내는 아이 프레임을 함께 받는 곳 (U3 L3 · U3-R9). 동의된 세션의 원음 기록기만 붙는다.
 *
 * <p>중계 스레드에서 불리므로 기다리지 않고(대기열에 넣기만) 예외를 던지지 않아야 한다. 프레임 경로 규칙(J113 ④)대로 이 인터페이스는 파일 · DB를
 * 모른다 — 구현은 {@code recording} 패키지에 있다.
 */
@FunctionalInterface
public interface AudioTap {

    /** 공급자로 보낼 아이 입력 프레임(960바이트 PCM16LE). 배열을 바꾸지 않는다. */
    void childFrame(byte[] pcm);
}
