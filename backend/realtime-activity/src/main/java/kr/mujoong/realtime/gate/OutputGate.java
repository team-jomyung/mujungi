package kr.mujoong.realtime.gate;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import kr.mujoong.realtime.gate.GateDecision.DiscardReason;

/**
 * 출력 게이트 판정 (U1 3.5 Step 25 · BR7.4 · SD-17 · PD-3 표 2.3-C · R3-19 · NFR1.1 · NFR1.2).
 *
 * <p>두 단계로 나뉜다. 둘 다 DB를 보지 않고 세션 실행기 안에서만 부른다.
 *
 * <ol>
 *   <li>{@link #inspect}(보안 설계 단계 3 · 4): 전사가 비었거나 음성이 없거나 PCM16이 아닌 길이면 GATE_EMPTY, 시드 사전에 걸리면
 *       GATE_PATTERN. 세션 상태를 보지 않는다. 판정 중 예외는 GATE_EMPTY로 폐기한다(fail-closed).
 *   <li>{@link #recheck}(단계 6, 송신 직전 재확인 — NOT_ACTIVE를 판정하는 유일한 자리): ① ACTIVE가 아니거나 출력 세대가 다르거나 기기
 *       연결이 없으면 NOT_ACTIVE ② 기록기 지연 상태면 RECORDER_DEGRADED ③ 출력 레인 자리 예약이 안 되면 OUTBOUND_FULL. 셋 모두 통과한
 *       직후가 tGatePass다(호출자가 찍는다).
 * </ol>
 */
public final class OutputGate {

    private final SeedDictionary dictionary;

    public OutputGate(SeedDictionary dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
    }

    /** 단계 3 · 4. */
    public GateDecision inspect(SegmentBuffer.Sealed segment) {
        try {
            if (segment == null || segment.transcript() == null || segment.transcript().isBlank()
                    || segment.totalBytes() == 0 || segment.totalBytes() % 2 != 0) {
                return GateDecision.discard(DiscardReason.GATE_EMPTY);
            }
            if (dictionary.findMatch(segment.transcript()).isPresent()) {
                return GateDecision.discard(DiscardReason.GATE_PATTERN);
            }
            return GateDecision.pass();
        } catch (RuntimeException e) {
            return GateDecision.discard(DiscardReason.GATE_EMPTY);
        }
    }

    /**
     * 단계 6. 처음 걸린 조건이 사유다. 통과면 예약을 함께 돌려준다(호출자가 tGatePass를 찍고 기록기에 승인 행을 넣은 뒤 예약을 확정한다).
     *
     * @param activeAndCurrent 세션이 ACTIVE이고 구간 세대가 현재 세대와 같고 기기 연결이 살아 있는가
     * @param recorderDegraded 기록기 지연 상태인가
     * @param reserve 출력 레인 자리 예약(자리가 없으면 빈 값)
     */
    public <R> Rechecked<R> recheck(boolean activeAndCurrent, boolean recorderDegraded, Supplier<Optional<R>> reserve) {
        if (!activeAndCurrent) {
            return new Rechecked<>(GateDecision.discard(DiscardReason.NOT_ACTIVE), null);
        }
        if (recorderDegraded) {
            return new Rechecked<>(GateDecision.discard(DiscardReason.RECORDER_DEGRADED), null);
        }
        Optional<R> reservation;
        try {
            reservation = reserve.get();
        } catch (RuntimeException e) {
            reservation = Optional.empty();
        }
        if (reservation.isEmpty()) {
            return new Rechecked<>(GateDecision.discard(DiscardReason.OUTBOUND_FULL), null);
        }
        return new Rechecked<>(GateDecision.pass(), reservation.get());
    }

    /** 재확인 결과. 통과일 때만 예약이 있다. */
    public record Rechecked<R>(GateDecision decision, R reservation) {
    }
}
