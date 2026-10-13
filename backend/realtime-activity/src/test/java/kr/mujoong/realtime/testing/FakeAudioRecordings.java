package kr.mujoong.realtime.testing;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.recording.AudioRecordings;

/** 시험용 원음 기록(세션 실행기 단위 시험, U3). 연 세션과 받은 바이트를 메모리에 둔다. */
public final class FakeAudioRecordings implements AudioRecordings {

    /** 연 세션 하나의 기록. */
    public static final class Recorded implements Session {

        private final UUID sessionId;
        private final ByteArrayOutputStream child = new ByteArrayOutputStream();
        private final ByteArrayOutputStream ai = new ByteArrayOutputStream();
        private Instant closedAt;

        private Recorded(UUID sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public synchronized void child(byte[] pcm) {
            child.writeBytes(pcm);
        }

        @Override
        public synchronized void ai(byte[] pcm) {
            ai.writeBytes(pcm);
        }

        @Override
        public synchronized void close(Instant endedAt) {
            if (closedAt == null) {
                closedAt = endedAt;
            }
        }

        public UUID sessionId() {
            return sessionId;
        }

        public synchronized byte[] childBytes() {
            return child.toByteArray();
        }

        public synchronized byte[] aiBytes() {
            return ai.toByteArray();
        }

        public synchronized Instant closedAt() {
            return closedAt;
        }
    }

    private final List<Recorded> opened = new ArrayList<>();

    @Override
    public synchronized Session open(UUID sessionId) {
        Recorded recorded = new Recorded(sessionId);
        opened.add(recorded);
        return recorded;
    }

    public synchronized List<Recorded> opened() {
        return List.copyOf(opened);
    }
}
