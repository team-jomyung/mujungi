import { AUDIO_SAMPLE_RATE } from "@/shared/protocol/frameCodec";

// 「켜기」 클릭 핸들러 안에서 AudioContext를 만든다 (U1 3.5 Step 38 · J121 · NFR10.4 · PD-14)
// 사용자 동작 안에서 만들어야 브라우저 자동 재생 정책에 막히지 않는다. 24kHz 문맥을 먼저 청하고, 브라우저가 거부하면
// 기본 비율 문맥을 쓰고 resample.ts가 선형 보간으로 24kHz로 바꾼다. 캡처 · 재생 코드 본체는 클릭 뒤 지연 적재한다.

type AudioContextConstructor = new (options?: AudioContextOptions) => AudioContext;

/** AudioContext를 만든다. 24kHz가 거부되면 기본 비율로, 둘 다 안 되면 null(재생 · 마이크 UNAVAILABLE). */
export function createAudioContextInGesture(
  Constructor: AudioContextConstructor | undefined = globalThis.AudioContext,
): AudioContext | null {
  if (Constructor === undefined) {
    return null;
  }
  try {
    return new Constructor({ sampleRate: AUDIO_SAMPLE_RATE, latencyHint: "interactive" });
  } catch {
    try {
      return new Constructor({ latencyHint: "interactive" });
    } catch {
      return null;
    }
  }
}
