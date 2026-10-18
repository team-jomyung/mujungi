package kr.mujoong.realtime.practicelegacy;

import static org.assertj.core.api.Assertions.*;
import static kr.mujoong.realtime.practicelegacy.PracticeTypes.*;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("practice-legacy")
class PracticeBehaviorProjectionTest {
    @Test void frozenBehaviorIdentityRemainsAvailableToResultConsumerWithoutJudgmentCriteria() {
        var goal = new SessionGoal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "차례 기다리기", "WAIT_TURN", 3, "비공개 판정 기준", GoalStatus.NOT_REACHED, null, 0, null);
        var snapshot = new PracticeRepository.Snapshot(List.of(goal), List.of(), List.of(), List.of(), List.of());
        var ready = (PracticeRecordProjector.Ready) new PracticeRecordProjector().projectSettled(snapshot);
        assertThat(ready.practice().goals()).singleElement().satisfies(record -> {
            assertThat(record.behaviorKey()).isEqualTo("WAIT_TURN");
            assertThat(record.behaviorVersion()).isEqualTo(3);
        });
        assertThat(JsonCodec.shared().write(ready.practice())).contains("\"behaviorKey\":\"WAIT_TURN\"", "\"behaviorVersion\":3")
                .doesNotContain("비공개 판정 기준", "judgmentCriteria");
    }
}
