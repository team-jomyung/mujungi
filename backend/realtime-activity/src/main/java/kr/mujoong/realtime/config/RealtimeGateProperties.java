package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;

/** BR7.3 출력 구간 조용 시간. */
public record RealtimeGateProperties(Duration segmentQuiet) {
    public RealtimeGateProperties {
        positive(segmentQuiet, "mujoong.gate.segment-quiet");
    }
}
