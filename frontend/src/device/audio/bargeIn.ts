import { AUDIO_SAMPLE_RATE } from "@/shared/protocol/frameCodec";

// 끼어들기 로컬 감지 (U3 L5 · U3-R5 · U3-J20 · AC6.2.12 · 계약 timing gate.barge-in-stop · gate.local-voice-min)
//
// 기기가 소리를 내는 동안 마이크 프레임의 평균 절대 진폭이 임계값(서버 음성 활동 판정과 같은 500, U3-J20) 이상이면 아이 음성으로 본다.
// 짧은 소리(localVoiceMinMs 미만의 연속 음성)는 버리고, 그보다 긴 음성 구간을 모은 길이가 bargeInStopMs에 닿으면 끼어들기다.
// 음성 없는 시간이 bargeInStopMs만큼 이어지면 모은 길이를 비운다(다른 발화). 재생이 끝나면 처음부터 센다.
// 원음은 판정에만 쓰고 저장하지 않는다. 에코 제거가 켜진 마이크를 전제로 한다(micPermission의 echoCancellation).

/** 음성으로 보는 평균 절대 진폭(PCM16, 서버 InputRelay.VOICE_ENERGY_THRESHOLD와 같다, U3-J20). */
export const VOICE_ENERGY_THRESHOLD = 500;

/** 1밀리초의 표본 수(24kHz). */
const SAMPLES_PER_MS = AUDIO_SAMPLE_RATE / 1_000;

export interface BargeInOptions {
  /** 이 길이 이상 이어진 음성만 센다(mujoong.timing.gate.local-voice-min). */
  readonly localVoiceMinMs: number;
  /** 모은 음성이 이 길이에 닿으면 끼어들기(mujoong.timing.gate.barge-in-stop). */
  readonly bargeInStopMs: number;
}

/** 프레임 하나의 평균 절대 진폭. */
export function frameEnergy(frame: Int16Array): number {
  if (frame.length === 0) {
    return 0;
  }
  let sum = 0;
  for (const sample of frame) {
    sum += Math.abs(sample);
  }
  return sum / frame.length;
}

export class BargeInDetector {
  private runMs = 0;
  private committedMs = 0;
  private silenceMs = 0;

  constructor(
    private readonly options: BargeInOptions,
    private readonly onBargeIn: () => void,
  ) {}

  /**
   * 마이크 프레임 하나를 넣는다.
   *
   * @param playing 지금 기기가 소리를 내고 있는가(아니면 세지 않고 비운다)
   */
  feed(frame: Int16Array, playing: boolean): void {
    if (!playing) {
      this.reset();
      return;
    }
    const frameMs = frame.length / SAMPLES_PER_MS;
    if (frameEnergy(frame) >= VOICE_ENERGY_THRESHOLD) {
      this.runMs += frameMs;
      this.silenceMs = 0;
      if (this.runMs >= this.options.localVoiceMinMs && this.committedMs + this.runMs >= this.options.bargeInStopMs) {
        this.reset();
        this.onBargeIn();
      }
      return;
    }
    if (this.runMs >= this.options.localVoiceMinMs) {
      this.committedMs += this.runMs;
    }
    this.runMs = 0;
    this.silenceMs += frameMs;
    if (this.silenceMs >= this.options.bargeInStopMs) {
      this.committedMs = 0;
    }
  }

  reset(): void {
    this.runMs = 0;
    this.committedMs = 0;
    this.silenceMs = 0;
  }
}
