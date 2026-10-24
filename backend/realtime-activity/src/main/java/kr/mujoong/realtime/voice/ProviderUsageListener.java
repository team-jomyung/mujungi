package kr.mujoong.realtime.voice;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.usage.AiUsageCollector;
import kr.mujoong.realtime.usage.AiUsageRecord;

/** 연결 경계의 수신기 장식자. 음성·전사는 복사하지 않고 원래 수신기로 그대로 전달한다. */
final class ProviderUsageListener implements ProviderPort.Listener {
    private final UUID sessionId;
    private final String model;
    private final ProviderPort.Listener delegate;
    private final AiUsageCollector collector;
    private final MujoongClock clock;
    private UUID id = UUID.randomUUID();
    private String providerSessionId;
    private Double seconds;
    private long revision;
    private boolean finalUsage;
    private boolean closed;

    ProviderUsageListener(UUID sessionId, String model, ProviderPort.Listener delegate,
            AiUsageCollector collector, MujoongClock clock) {
        this.sessionId = sessionId;
        this.model = model;
        this.delegate = delegate;
        this.collector = collector;
        this.clock = clock;
    }

    @Override public void onEvent(ProviderPort.ProviderEvent event) {
        if (event instanceof ProviderPort.ProviderEvent.Started started) {
            synchronized (this) {
                if (!closed) {
                    String receivedId = started.providerSessionId();
                    providerSessionId = receivedId != null && receivedId.length() <= 200 ? receivedId : null;
                    // 이미 낸 스냅샷의 키는 바꾸지 않는다(늦은/중복 started도 한 연결 한 행).
                    if (providerSessionId != null && revision == 0) {
                        id = UUID.nameUUIDFromBytes(("VOICE:" + providerSessionId).getBytes(StandardCharsets.UTF_8));
                    }
                    publish();
                }
            }
        } else if (event instanceof ProviderPort.ProviderEvent.UsageUpdated usage) {
            synchronized (this) {
                if (!closed && Double.isFinite(usage.seconds()) && usage.seconds() >= 0) {
                    seconds = seconds == null ? usage.seconds() : Math.max(seconds, usage.seconds());
                    publish();
                }
            }
            // 이 이벤트는 기존 실행기에서도 무시하던 사용량이다. 대화 편지함을 사용하지 않는다.
            return;
        } else if (event instanceof ProviderPort.ProviderEvent.Closed) {
            synchronized (this) {
                if (!closed) { closed = true; publish(); }
            }
        }
        // 수신기가 대기하더라도 사용량 상태 잠금을 잡지 않는다.
        delegate.onEvent(event);
    }

    synchronized void finalUsage(Double value) {
        if (!closed && value != null && Double.isFinite(value) && value >= 0
                && (seconds == null || value >= seconds)) {
            seconds = value;
            finalUsage = true;
            publish();
        }
    }

    private void publish() {
        AiUsageRecord.Status status = finalUsage ? AiUsageRecord.Status.COMPLETE
                : seconds == null ? AiUsageRecord.Status.UNKNOWN : AiUsageRecord.Status.PARTIAL;
        try {
            collector.offer(new AiUsageRecord(id, AiUsageRecord.Source.VOICE, sessionId, null, providerSessionId,
                    null, model, null, null, null, null, seconds, status, ++revision, clock.now()));
        } catch (RuntimeException unavailable) {
            // 수집 장애가 대화 이벤트 전파를 끊지 않는다.
        }
    }
}
