package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.realtime.log.SessionGapRepository;

/**
 * 세션 실행기가 끊김 구간을 적는 입구 (U3 L2 · ConversationLog · U3-R11 · U3-J18 · AC3.2.10(b) · 계약 LiveView.gaps).
 *
 * <p>묶인 기기 연결이 끊기면 구간을 열고, 같은 기기의 새 연결이 다시 묶이면 닫는다. 세션이 ABORTED로 끝나면 열린 채 남는다(복구하지 못함).
 * 운영 구현은 {@code log.SessionGapWriter}(짧은 트랜잭션, 실패는 앱 오류 한 줄)이고 실행기 단위 시험은 메모리 대역을 쓴다. 기록 실패가 대화를
 * 멈추게 하지 않도록 구현은 예외를 던지지 않는다.
 */
public interface SessionGapPort {

    /**
     * 구간을 연다.
     *
     * @return 구간 id. 쓰지 못했으면 null
     */
    UUID open(UUID sessionId, SessionGapRepository.Kind kind, Instant startedAt);

    /** 구간을 닫는다(이미 닫혔으면 바꾸지 않는다). */
    void close(UUID gapId, Instant endedAt);
}
