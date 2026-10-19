package kr.mujoong.realtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.CloseCode;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 연결 송신기의 리뷰 반영 6 시험: 출력 묶음의 프레임 송신 콜백(원음 기록)은 소켓에 실제로 쓴 프레임만 받는다 — 세대가 바뀌어 잘린 프레임 ·
 * 쓰다 끊긴 프레임은 받지 않는다(U3-R9 '기기로 보낸 AI 음성만 기록').
 */
@Tag("voice-session")
class ConnectionSenderVoiceSessionTest {

    private final FailingTransport transport = new FailingTransport();
    private final ConnectionSender sender = new ConnectionSender("test", transport, JsonCodec.shared(),
            new MutableClock(), new ConnectionSender.Listener() {
                @Override
                public void controlOverflow() {
                    // 이 시험은 넘침을 만들지 않는다
                }

                @Override
                public void broken(IOException failure) {
                    // 끊김은 프레임 쓰기 실패로만 만든다
                }

                @Override
                public void closed(CloseCode code) {
                    // 닫기는 보지 않는다
                }
            }, 4, 48_000);

    @Test
    void onlyFramesWrittenToTheSocketReachTheFrameCallback() {
        List<byte[]> recorded = new ArrayList<>();
        AtomicBoolean current = new AtomicBoolean(true);
        byte[] first = filled(1);
        byte[] second = filled(2);
        byte[] third = filled(3);
        OutputBundle bundle = new OutputBundle(UUID.randomUUID(), UUID.randomUUID(), OutputBegin.Source.GPT_LIVE,
                List.of(first, second, third), 300, sender.reserve(3 * 4_800).orElseThrow(), current::get, at -> { },
                frame -> {
                    recorded.add(frame);
                    if (recorded.size() == 2) {
                        // 두 번째 프레임을 보낸 직후 끼어들기로 세대가 올랐다
                        current.set(false);
                    }
                });
        assertThat(sender.enqueueOutput(bundle)).isTrue();

        sender.drainAvailable();

        assertThat(recorded).containsExactly(first, second);
    }

    @Test
    void aFrameWhoseWriteFailedIsNotRecorded() {
        List<byte[]> recorded = new ArrayList<>();
        transport.failBinary = true;
        OutputBundle bundle = new OutputBundle(UUID.randomUUID(), UUID.randomUUID(), OutputBegin.Source.GPT_LIVE,
                List.of(filled(1)), 100, sender.reserve(4_800).orElseThrow(), () -> true, at -> { }, recorded::add);
        assertThat(sender.enqueueOutput(bundle)).isTrue();

        sender.drainAvailable();

        assertThat(recorded).isEmpty();
    }

    private static byte[] filled(int value) {
        byte[] frame = new byte[4_800];
        java.util.Arrays.fill(frame, (byte) value);
        return frame;
    }

    /** 프레임 쓰기를 실패시킬 수 있는 소켓 대역. */
    private static final class FailingTransport implements ConnectionSender.Transport {

        private volatile boolean failBinary;

        @Override
        public void sendText(String text) {
            // 제어 메시지 내용은 보지 않는다
        }

        @Override
        public void sendBinary(byte[] frame) throws IOException {
            if (failBinary) {
                throw new IOException("끊긴 소켓");
            }
        }

        @Override
        public void close(CloseCode code) {
            // 닫기는 보지 않는다
        }
    }
}
