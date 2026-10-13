package kr.mujoong.realtime.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.session.SessionGapPort;

/** 시험용 끊김 구간 기록(세션 실행기 단위 시험, U3). 연 구간 · 닫은 구간을 메모리에 둔다. */
public final class FakeSessionGaps implements SessionGapPort {

    /** 연 구간 한 건. */
    public record Opened(UUID id, UUID sessionId, SessionGapRepository.Kind kind, Instant startedAt) {
    }

    /** 닫은 구간 한 건. */
    public record Closed(UUID id, Instant endedAt) {
    }

    private final List<Opened> opened = new ArrayList<>();
    private final List<Closed> closed = new ArrayList<>();
    private boolean failOpen;

    @Override
    public synchronized UUID open(UUID sessionId, SessionGapRepository.Kind kind, Instant startedAt) {
        if (failOpen) {
            return null;
        }
        UUID id = UUID.randomUUID();
        opened.add(new Opened(id, sessionId, kind, startedAt));
        return id;
    }

    @Override
    public synchronized void close(UUID gapId, Instant endedAt) {
        if (gapId != null) {
            closed.add(new Closed(gapId, endedAt));
        }
    }

    /** 다음 열기가 실패(null)하게 한다. */
    public synchronized void failOpen(boolean value) {
        this.failOpen = value;
    }

    public synchronized List<Opened> opened() {
        return List.copyOf(opened);
    }

    public synchronized List<Closed> closed() {
        return List.copyOf(closed);
    }
}
