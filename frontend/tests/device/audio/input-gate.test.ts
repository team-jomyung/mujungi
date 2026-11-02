import { describe, expect, it } from "vitest";
import {
  GATE_THRESHOLD_MAX,
  GATE_THRESHOLD_MIN,
  InputGate,
  lowPercentile,
  thresholdFromFloor,
} from "@/device/audio/inputGate";

// 기기 입력 음량 관문(FT-1102-g): 시작 안내 중 잰 소음 바닥으로 기준을 정하고, 기준 아래 소리는 무음으로 바꿔 보낸다.

const FRAME = 480; // 20ms(24kHz)
const frame = (amplitude: number) => new Int16Array(FRAME).fill(amplitude);
const isSilent = (samples: Int16Array) => samples.every((value) => value === 0);
const options = { hangoverMs: 100, prerollMs: 40 };

function calibrate(gate: InputGate, amplitudes: readonly number[]) {
  for (const amplitude of amplitudes) gate.process(frame(amplitude), true);
  gate.process(frame(0), false); // 안내가 끝난 첫 프레임에서 측정을 마친다
}

describe("InputGate", () => {
  it("기준은 소음 바닥의 3배를 하한 · 상한 안으로 자른다", () => {
    expect(thresholdFromFloor(0)).toBe(GATE_THRESHOLD_MIN);
    expect(thresholdFromFloor(200)).toBe(600);
    expect(thresholdFromFloor(5_000)).toBe(GATE_THRESHOLD_MAX);
    expect(lowPercentile([5, 1, 4, 2, 3], 0.2)).toBe(1);
    expect(lowPercentile([], 0.2)).toBe(0);
  });

  it("시작 안내 중 음량의 하위 백분위를 소음 바닥으로 잰다(되울림이 섞인 큰 값은 무시)", () => {
    const gate = new InputGate(options);
    calibrate(gate, [...Array(20).fill(200), ...Array(20).fill(3_000)]);
    expect(gate.floor).toBe(200);
    expect(gate.threshold).toBe(600);
  });

  it("측정 프레임이 너무 적으면 기준을 바꾸지 않는다", () => {
    const gate = new InputGate(options);
    calibrate(gate, Array(5).fill(400));
    expect(gate.floor).toBeNull();
    expect(gate.threshold).toBe(GATE_THRESHOLD_MIN);
  });

  it("기준 아래 주변 소리는 같은 길이의 무음으로 바꿔 보낸다", () => {
    const gate = new InputGate(options);
    calibrate(gate, Array(30).fill(200));
    const out = gate.process(frame(500), false);
    expect(out).toHaveLength(1);
    expect(out[0]).toHaveLength(FRAME);
    expect(isSilent(out[0]!)).toBe(true);
  });

  it("큰 소리가 오면 열고 앞 소리를 먼저 보낸 뒤, 조용한 시간이 이어지면 닫는다", () => {
    const gate = new InputGate(options);
    calibrate(gate, Array(30).fill(200));
    gate.process(frame(100), false);
    gate.process(frame(150), false);
    gate.process(frame(160), false);
    const opened = gate.process(frame(2_000), false);
    expect(opened.map((samples) => samples[0])).toEqual([150, 160, 2_000]); // 앞 소리 40ms(2프레임) + 지금 프레임
    expect(gate.process(frame(100), false)[0]![0]).toBe(100); // 말 사이 짧은 틈은 그대로 보낸다
    for (let i = 0; i < 4; i += 1) gate.process(frame(100), false);
    expect(isSilent(gate.process(frame(100), false)[0]!)).toBe(true); // 100ms 넘게 조용하면 닫힌다
  });

  it("입력 창이 바뀌면 닫고 앞 소리를 비우지만 잰 기준은 남긴다", () => {
    const gate = new InputGate(options);
    calibrate(gate, Array(30).fill(200));
    gate.process(frame(2_000), false);
    gate.reset();
    expect(gate.threshold).toBe(600);
    expect(isSilent(gate.process(frame(100), false)[0]!)).toBe(true);
    expect(gate.process(frame(2_000), false)).toHaveLength(2); // 앞 소리 1프레임 + 지금 프레임
  });
});
