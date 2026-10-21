package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.InputTranscriptDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 입력 전사 묶음 칸 시험(설계 개정 A7 · 참여 관문 진단 로그). 조각을 이어 붙이고, 진단에 쓰는 모양(조각 수 · 최대 간격 · 연 시각 · 재생 중)을 바르게
 * 세는지 본다. 전사 원문은 문자열 표현에 나오지 않아야 한다.
 */
@Tag("join-gate")
@Tag("session-runtime")
class InputTranscriptGroupTest {

    private static final Instant T0 = Instant.parse("2026-10-21T09:00:00Z");

    private final InputTranscriptGroup group = new InputTranscriptGroup();

    @Test
    void deltasAreJoinedIntoOneGroupWithTheShapeOfTheUtterance() {
        String id = group.add(new InputTranscriptDelta(null, "응 ", 0, 300), T0, true);
        assertThat(group.add(new InputTranscriptDelta(null, "좋아", 500, 800), T0.plusMillis(400), false)).isEqualTo(id);

        InputTranscriptGroup.Closed closed = group.close();

        assertThat(closed.id()).isEqualTo(id).startsWith("in-");
        assertThat(closed.text()).isEqualTo("응 좋아");
        assertThat(closed.shape()).isEqualTo(new InputTranscriptGroup.Shape(2, 200, T0, true));
        assertThat(group.isOpen()).isFalse();
        assertThat(group.close()).as("비운 뒤에는 꺼낼 것이 없다").isNull();
    }

    @Test
    void theGapIsUnknownWithoutTimelinesAndNeverNegativeWhenPiecesOverlap() {
        group.add(new InputTranscriptDelta(null, "하나"), T0, false);
        group.add(new InputTranscriptDelta(null, "둘"), T0, false);
        assertThat(group.shape().maxGapMs()).as("시간선이 없으면 잴 수 없다").isEqualTo(InputTranscriptGroup.NO_GAP);
        group.clear();

        group.add(new InputTranscriptDelta(null, "셋", 1000, 1000), T0, false);
        group.add(new InputTranscriptDelta(null, "넷", 1000, 1000), T0, false);
        assertThat(group.shape().maxGapMs()).as("같은 start_ms = end_ms 조각(가짜 공급자)").isZero();
        group.clear();

        group.add(new InputTranscriptDelta(null, "다섯", 1000, 1500), T0, false);
        group.add(new InputTranscriptDelta(null, "여섯", 1200, 1600), T0, false);
        assertThat(group.shape().maxGapMs()).as("겹친 조각은 0으로 센다").isZero();
    }

    @Test
    void theProviderItemIdIsKeptAndOpensAreCountedOncePerGroup() {
        assertThat(group.opens()).isZero();
        assertThat(group.add(new InputTranscriptDelta("x1", "가"), T0, false)).isEqualTo("x1");
        group.add(new InputTranscriptDelta("x2", "나"), T0, false);
        assertThat(group.opens()).as("같은 묶음의 꼬리 조각은 세지 않는다").isEqualTo(1);
        assertThat(group.id()).isEqualTo("x1");
        group.clear();

        String next = group.add(new InputTranscriptDelta(null, "다"), T0.plusSeconds(1), false);

        assertThat(group.opens()).isEqualTo(2);
        assertThat(next).startsWith("in-");
        assertThat(group.shape().openedAt()).as("새 묶음은 새로 연 시각").isEqualTo(T0.plusSeconds(1));
        assertThat(group.shape().deltaCount()).isEqualTo(1);
    }

    @Test
    void theStringFormsNeverCarryTheTranscript() {
        group.add(new InputTranscriptDelta(null, "프테라노돈"), T0, false);

        assertThat(group.toString()).doesNotContain("프테라노돈");
        assertThat(group.close().toString()).doesNotContain("프테라노돈");
        assertThat(InputTranscriptGroup.Shape.single(T0, true))
                .isEqualTo(new InputTranscriptGroup.Shape(0, InputTranscriptGroup.NO_GAP, T0, true));
    }
}
