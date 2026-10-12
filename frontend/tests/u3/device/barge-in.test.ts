import { describe, expect, it, vi } from "vitest";
import { BargeInDetector, VOICE_ENERGY_THRESHOLD, frameEnergy } from "@/device/audio/bargeIn";

// 끼어들기 로컬 감지 시험 (U3-R5 · U3-J20 · AC6.2.12 · gate.barge-in-stop 500ms · gate.local-voice-min 200ms)
// 프레임은 480표본(24kHz 20ms)이다.

const FRAME_SAMPLES = 480;
const OPTIONS = { localVoiceMinMs: 200, bargeInStopMs: 500 };

const voice = () => new Int16Array(FRAME_SAMPLES).fill(VOICE_ENERGY_THRESHOLD);
const quiet = () => new Int16Array(FRAME_SAMPLES).fill(VOICE_ENERGY_THRESHOLD - 1);
const frames = (make: () => Int16Array, count: number) => Array.from({ length: count }, make);

function feed(detector: BargeInDetector, list: Int16Array[], playing = true) {
  for (const frame of list) {
    detector.feed(frame, playing);
  }
}

describe("BargeInDetector", () => {
  it("평균 절대 진폭은 표본 절댓값의 평균이고 빈 프레임은 0이다", () => {
    expect(frameEnergy(Int16Array.of(-600, 600, 0, 200))).toBe(350);
    expect(frameEnergy(new Int16Array(0))).toBe(0);
    expect(VOICE_ENERGY_THRESHOLD).toBe(500);
  });

  it("재생 중 아이 음성이 500ms 이어지면 한 번 알리고 다시 처음부터 센다", () => {
    const onBargeIn = vi.fn();
    const detector = new BargeInDetector(OPTIONS, onBargeIn);
    feed(detector, frames(voice, 24));
    expect(onBargeIn).not.toHaveBeenCalled();
    feed(detector, [voice()]);
    expect(onBargeIn).toHaveBeenCalledTimes(1);
    feed(detector, frames(voice, 24));
    expect(onBargeIn).toHaveBeenCalledTimes(1);
    feed(detector, [voice()]);
    expect(onBargeIn).toHaveBeenCalledTimes(2);
  });

  it("200ms보다 짧은 소리는 버리고, 그보다 긴 소리 조각은 모아서 센다", () => {
    const onBargeIn = vi.fn();
    const detector = new BargeInDetector(OPTIONS, onBargeIn);
    // 180ms 소리(버림) · 조용 100ms · 300ms 소리(인정) · 조용 100ms · 180ms 소리(아직 최소 미만)
    feed(detector, [...frames(voice, 9), ...frames(quiet, 5), ...frames(voice, 15), ...frames(quiet, 5), ...frames(voice, 9)]);
    expect(onBargeIn).not.toHaveBeenCalled();
    // 다음 20ms로 그 조각이 200ms가 되어 300 + 200 = 500ms
    feed(detector, [voice()]);
    expect(onBargeIn).toHaveBeenCalledTimes(1);
  });

  it("조용한 시간이 500ms 이어지면 모은 길이를 비운다(다른 발화)", () => {
    const onBargeIn = vi.fn();
    const detector = new BargeInDetector(OPTIONS, onBargeIn);
    feed(detector, [...frames(voice, 15), ...frames(quiet, 25), ...frames(voice, 15), quiet()]);
    expect(onBargeIn).not.toHaveBeenCalled();
    feed(detector, frames(voice, 10));
    expect(onBargeIn).toHaveBeenCalledTimes(1);
  });

  it("기기가 소리를 내지 않을 때는 세지 않고 비운다", () => {
    const onBargeIn = vi.fn();
    const detector = new BargeInDetector(OPTIONS, onBargeIn);
    feed(detector, frames(voice, 20));
    feed(detector, [voice()], false);
    feed(detector, frames(voice, 20));
    expect(onBargeIn).not.toHaveBeenCalled();
    feed(detector, frames(voice, 30), false);
    expect(onBargeIn).not.toHaveBeenCalled();
    detector.reset();
    feed(detector, frames(voice, 25));
    expect(onBargeIn).toHaveBeenCalledTimes(1);
  });
});
