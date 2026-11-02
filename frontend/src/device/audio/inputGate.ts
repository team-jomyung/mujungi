import { AUDIO_SAMPLE_RATE } from "@/shared/protocol/frameCodec";
import { frameEnergy } from "./bargeIn";

// 기기 입력 음량 관문 (FT-1102-g)
//
// 주변 소리(먼 대화 · TV · 생활 소음)가 공급자에 닿아 아이 말로 전사되는 것을 줄인다.
// 1) 측정: 시작 안내(승인 문구) 재생 중에는 아이가 말하지 않으므로, 그동안의 프레임 음량 하위 백분위를 그 자리의 소음 바닥으로 본다.
//    하위 백분위를 쓰는 까닭은 스피커 소리의 남은 되울림이 섞여도 문장 사이 틈의 값이 실제 주변 소음에 가깝기 때문이다.
// 2) 기준: 소음 바닥 × GATE_FLOOR_RATIO를 GATE_THRESHOLD_MIN ~ GATE_THRESHOLD_MAX 안으로 자른다. 측정 전에는 GATE_THRESHOLD_MIN이다.
// 3) 관문: 기준 이상 프레임이 오면 열고, 그 앞 prerollMs 분량의 프레임을 먼저 보내 말머리를 살린다. 기준 아래 소리가 hangoverMs 이어지면 닫는다.
//    닫힌 동안의 프레임은 같은 길이의 무음으로 바꿔 보낸다(서버 · 공급자의 시간 흐름은 지금과 같다).
// 원음은 판정에만 쓰고 저장하지 않는다. 끼어들기 감지(bargeIn)는 이 관문과 별개로 원래 프레임을 본다.

/** 측정 전 · 측정 실패 때의 기준이자 기준의 하한(PCM16 평균 절대 진폭). 작게 말하는 아이를 자르지 않는 쪽으로 낮게 둔다. */
export const GATE_THRESHOLD_MIN = 300;
/** 기준의 상한. 시끄러운 곳에서도 보통 크기 아이 목소리는 통과하게 한다. */
export const GATE_THRESHOLD_MAX = 1500;
/** 소음 바닥에 곱하는 배수(약 10dB 위). */
export const GATE_FLOOR_RATIO = 3;
/** 소음 바닥으로 쓰는 하위 백분위. */
export const GATE_FLOOR_PERCENTILE = 0.2;
/** 측정으로 인정하는 최소 프레임 수(20ms 프레임 25개 = 0.5초). 이보다 적으면 앞 기준을 그대로 쓴다. */
export const GATE_MIN_CALIBRATION_FRAMES = 25;

const SAMPLES_PER_MS = AUDIO_SAMPLE_RATE / 1_000;

/** debug 화면용 관문 상태(음량 숫자만, 원음 없음). */
export interface InputGateSnapshot {
  /** 지금 관문 기준. */
  readonly threshold: number;
  /** 기준의 상한(막대 눈금용). */
  readonly thresholdMax: number;
  /** 마지막으로 잰 소음 바닥(측정 전이면 null). */
  readonly floor: number | null;
  /** 마지막 측정에 쓴 프레임 수(측정 전이면 0). */
  readonly calibrationFrames: number;
  /** 최근 프레임들의 가장 큰 음량. */
  readonly level: number;
  /** 지금 상태: 측정 중 · 열림 · 닫힘. */
  readonly state: "calibrating" | "open" | "closed";
}

export interface InputGateOptions {
  /** 기준 아래 소리가 이만큼 이어지면 닫는다(mujoong.timing.gate.input-hangover). */
  readonly hangoverMs: number;
  /** 열 때 먼저 보내는 앞 소리 길이(mujoong.timing.gate.input-preroll). */
  readonly prerollMs: number;
}

/** 측정한 소음 바닥으로 관문 기준을 정한다. */
export function thresholdFromFloor(floor: number): number {
  return Math.min(GATE_THRESHOLD_MAX, Math.max(GATE_THRESHOLD_MIN, Math.round(floor * GATE_FLOOR_RATIO)));
}

/** 음량 목록의 하위 백분위 값. */
export function lowPercentile(values: readonly number[], percentile: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.floor((sorted.length - 1) * percentile)));
  return sorted[index] ?? 0;
}

export class InputGate {
  private thresholdValue = GATE_THRESHOLD_MIN;
  private floorValue: number | null = null;
  private calibration: number[] = [];
  private open = false;
  private quietMs = 0;
  private preroll: Int16Array[] = [];
  private prerollSamples = 0;
  private lastCalibrationFrames = 0;
  private peakLevel = 0;

  constructor(private readonly options: InputGateOptions) {}

  /** 지금 관문 기준(진단용). */
  get threshold(): number {
    return this.thresholdValue;
  }

  /** 마지막으로 측정한 소음 바닥(측정 전이면 null, 진단용). */
  get floor(): number | null {
    return this.floorValue;
  }

  /** debug 화면용 상태. 읽으면 최근 최대 음량을 비운다(다음 보고 구간부터 다시 센다). */
  takeSnapshot(): InputGateSnapshot {
    const snapshot: InputGateSnapshot = {
      threshold: this.thresholdValue,
      thresholdMax: GATE_THRESHOLD_MAX,
      floor: this.floorValue,
      calibrationFrames: this.lastCalibrationFrames,
      level: Math.round(this.peakLevel),
      state: this.calibration.length > 0 ? "calibrating" : this.open ? "open" : "closed",
    };
    this.peakLevel = 0;
    return snapshot;
  }

  /**
   * 마이크 프레임 하나를 넣고, 서버로 보낼 프레임 목록을 돌려준다(닫힘: 무음 하나, 열림: 원래 프레임, 막 열림: 앞 소리 + 원래 프레임).
   *
   * @param calibrating 시작 안내를 재생하는 중인가(그동안의 음량으로 소음 바닥을 잰다)
   */
  process(frame: Int16Array, calibrating: boolean): Int16Array[] {
    const energy = frameEnergy(frame);
    this.peakLevel = Math.max(this.peakLevel, energy);
    if (calibrating) {
      this.calibration.push(energy);
    } else if (this.calibration.length > 0) {
      this.finishCalibration();
    }
    const frameMs = frame.length / SAMPLES_PER_MS;
    if (energy >= this.thresholdValue) {
      this.quietMs = 0;
      if (this.open) return [frame];
      this.open = true;
      const out = [...this.preroll, frame];
      this.clearPreroll();
      return out;
    }
    if (this.open) {
      this.quietMs += frameMs;
      if (this.quietMs < this.options.hangoverMs) return [frame];
      this.open = false;
      this.quietMs = 0;
    }
    this.keepPreroll(frame);
    return [new Int16Array(frame.length)];
  }

  /** 입력 창이 바뀔 때: 관문을 닫고 앞 소리를 비운다. 측정한 기준은 남긴다. */
  reset(): void {
    this.open = false;
    this.quietMs = 0;
    this.clearPreroll();
  }

  private finishCalibration(): void {
    const values = this.calibration;
    this.calibration = [];
    if (values.length < GATE_MIN_CALIBRATION_FRAMES) return;
    this.lastCalibrationFrames = values.length;
    const floor = lowPercentile(values, GATE_FLOOR_PERCENTILE);
    this.floorValue = Math.round(floor);
    this.thresholdValue = thresholdFromFloor(floor);
  }

  private keepPreroll(frame: Int16Array): void {
    const limit = this.options.prerollMs * SAMPLES_PER_MS;
    if (limit <= 0) return;
    this.preroll.push(frame);
    this.prerollSamples += frame.length;
    while (this.prerollSamples > limit && this.preroll.length > 0) {
      const dropped = this.preroll.shift();
      this.prerollSamples -= dropped?.length ?? 0;
    }
  }

  private clearPreroll(): void {
    this.preroll = [];
    this.prerollSamples = 0;
  }
}
