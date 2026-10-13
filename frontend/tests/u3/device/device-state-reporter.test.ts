import { describe, expect, it, vi } from "vitest";
import { DeviceStateReporter, NORMAL_DEVICE_STATE } from "@/device/runtime/deviceStateReporter";
import type { DeviceStatePayload } from "@/shared/protocol/messages";

// 기기 상태 보고 시험 (U3-R17 · AC6.2.7 · 계약 DEVICE_STATE '바뀐 필드만')

function setup(initial = NORMAL_DEVICE_STATE) {
  const sent: DeviceStatePayload[] = [];
  const reporter = new DeviceStateReporter(initial, (payload) => sent.push(payload));
  return { reporter, sent };
}

describe("DeviceStateReporter", () => {
  it("모두 정상이면 연결 때 아무것도 보내지 않고, 바뀐 필드만 바뀔 때마다 보낸다", () => {
    const { reporter, sent } = setup();
    reporter.connected();
    expect(sent).toEqual([]);
    reporter.update({ visibility: "HIDDEN" });
    reporter.update({ visibility: "HIDDEN" });
    reporter.update({ visibility: "VISIBLE", wakeLock: "RELEASED" });
    expect(sent).toEqual([{ visibility: "HIDDEN" }, { visibility: "VISIBLE", wakeLock: "RELEASED" }]);
    expect(reporter.snapshot).toEqual({ mic: "OK", player: "OK", visibility: "VISIBLE", wakeLock: "RELEASED" });
  });

  it("연결이 없을 때 바뀐 값은 다음 연결의 첫 보고에 정상과 다른 필드만 담긴다", () => {
    const { reporter, sent } = setup({ mic: "OK", player: "OK", visibility: "VISIBLE", wakeLock: "RELEASED" });
    reporter.update({ mic: "LOST" });
    expect(sent).toEqual([]);
    reporter.connected();
    expect(sent).toEqual([{ mic: "LOST", wakeLock: "RELEASED" }]);
    reporter.disconnected();
    reporter.update({ player: "ERROR", wakeLock: "HELD" });
    expect(sent).toHaveLength(1);
    reporter.connected();
    expect(sent.at(-1)).toEqual({ mic: "LOST", player: "ERROR" });
  });

  it("보고한 값으로 돌아오면 그 필드를 다시 알린다", () => {
    const { reporter, sent } = setup();
    reporter.connected();
    reporter.update({ player: "ERROR" });
    reporter.update({ player: "OK" });
    reporter.update({ mic: "OK" });
    expect(sent).toEqual([{ player: "ERROR" }, { player: "OK" }]);
  });

  it("보내는 함수가 없을 때도(연결 없음) 값은 들고 있다", () => {
    const send = vi.fn();
    const reporter = new DeviceStateReporter(NORMAL_DEVICE_STATE, send);
    reporter.update({ mic: "LOST" });
    reporter.disconnected();
    expect(send).not.toHaveBeenCalled();
    expect(reporter.snapshot.mic).toBe("LOST");
  });
});
