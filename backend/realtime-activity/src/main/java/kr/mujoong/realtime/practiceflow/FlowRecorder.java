package kr.mujoong.realtime.practiceflow;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;

/**
 * 상태 기계가 장면 · 사실 · 도움을 남기는 자리 (U12 FR4.1 · BR7). 상태 기계는 이 포트로만 기록을 부르고 저장 방식을 모른다. 모든 값은 분류 코드 ·
 * 길이 · 횟수 · 시각이고 원문(아이 전사 · 도움 발화 · 대본 문장)을 받는 메서드는 없다(R-F14 · BR7.1, 편차 U12-D2).
 *
 * <p>운영 구현(트랜잭션 · V7 표 · {@code record.practice} 조립)은 묶음 D가 {@link PracticeFlowRepository}로 만든다. 그 전의 기본 구현
 * {@link #NONE}은 아무것도 남기지 않는다. 호출은 실행기 스레드 안에서 직렬로 오고, 구현의 예외는 상태 기계가 앱 오류로 한 줄 남기고 흐름을
 * 잇는다(기록이 연습을 멈추지 않는다).
 */
public interface FlowRecorder {

    /** 장면 실행 한 행을 만든다(도입 재생이 성공한 뒤에만 부른다, BR7.3). */
    void sceneStarted(SceneRunRecord started);

    /** 일어난 사실 하나를 그 장면 실행에 쌓는다. */
    void fact(UUID sceneRunId, FactKind kind, Instant at);

    /** 도움 · 역할 전환 한 건을 남긴다(분류 코드와 길이만). */
    void help(HelpRecord help);

    /** 장면의 결과(끝 시각 · 진행 중 판정 시점 · 변형 생략 사유 · 횟수)를 덮어쓴다. 같은 값으로 다시 불러도 같다. */
    void sceneOutcome(UUID sceneRunId, Instant endedAt, LiveTiming timing, VariantSkipReason skip, int retryCount,
            int reexplainCount);

    /** 정산 시점의 {@code practice} 값(없으면 null). */
    Object practiceRecord();

    /** 아무것도 남기지 않는 기록기(운영 기록기를 연결하기 전). */
    FlowRecorder NONE = new FlowRecorder() {
        @Override
        public void sceneStarted(SceneRunRecord started) {
        }

        @Override
        public void fact(UUID sceneRunId, FactKind kind, Instant at) {
        }

        @Override
        public void help(HelpRecord help) {
        }

        @Override
        public void sceneOutcome(UUID sceneRunId, Instant endedAt, LiveTiming timing, VariantSkipReason skip,
                int retryCount, int reexplainCount) {
        }

        @Override
        public Object practiceRecord() {
            return null;
        }
    };
}
