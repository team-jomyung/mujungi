import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createParentQueryClient, useApiClient } from "@/parent/common/api/queries";
import { mergeBubbles, mergeLiveView } from "@/parent/features/live/api";
import {
  ResponseShapeError,
  isTerminalStatus,
  parseCsrfToken,
  parseDevLogin,
  parseEndResult,
  parseHome,
  parseLiveView,
  parseRegisterResult,
  parseStartResult,
  type Bubble,
} from "@/parent/common/api/types";
import { OTHER_SESSION_ID, SESSION_ID, bubble, homeBody, liveViewBody } from "../support/fixtures";

// 보호자 REST 응답 읽기 시험 (U1 3.5 Step 42 · 계약 3절 · 10절 · BR4.6 · BR6.15 · J23 · J120)
// 모르는 필드는 무시, 모르는 enum은 UNKNOWN(잠금 사유는 '확인 불가'로 닫힌 쪽), 필수 필드가 없으면 ResponseShapeError.

describe("parseHome · DeviceView(BR4.6)", () => {
  it("U1 홈 응답을 읽고 판정 내부값 · 해시 같은 모르는 필드는 버린다", () => {
    const body = homeBody("STABLE");
    const parsed = parseHome({ ...body, device: { ...(body.device as object), tokenHash: "x" } });
    expect(parsed.child).toEqual({ childId: expect.any(String), displayName: "하늘", age: 8, interests: ["공룡", "그림"] });
    expect(parsed.device.network.state).toBe("STABLE");
    expect(parsed.device.verdict).toEqual({ canStart: true, reasonCode: null });
    expect(parsed.lock).toEqual({ locked: false, reasonCode: null });
    expect(JSON.stringify(parsed)).not.toContain("tokenHash");
  });

  it("모르는 네트워크 상태는 UNKNOWN, 모르는 잠금 사유 · 빠진 사유는 '확인 불가'로 닫는다", () => {
    const body = homeBody("UNSTABLE") as { device: { network: object; verdict: object }; lock: object };
    const parsed = parseHome({
      ...body,
      device: { ...body.device, network: { ...body.device.network, state: "FLAKY" }, verdict: { canStart: false, reasonCode: "NEW_REASON" } },
      lock: { locked: true },
    });
    expect(parsed.device.network.state).toBe("UNKNOWN");
    expect(parsed.device.verdict.reasonCode).toBe("DEVICE_STATUS_UNKNOWN");
    expect(parsed.lock.reasonCode).toBe("DEVICE_STATUS_UNKNOWN");
  });

  it("아이가 없으면 null, 관심사 목록의 문자열 아닌 값은 버린다", () => {
    expect(parseHome(homeBody("NONE", { childName: null })).child).toBeNull();
    const body = homeBody("STABLE");
    const parsed = parseHome({ ...body, child: { childId: "c", displayName: "하늘", interests: ["공룡", 3] } });
    expect(parsed.child).toEqual({ childId: "c", displayName: "하늘", age: 0, interests: ["공룡"] });
  });

  it("필수 필드가 없거나 형식이 틀리면 ResponseShapeError다", () => {
    expect(() => parseHome(null)).toThrow(ResponseShapeError);
    expect(() => parseHome({ device: {}, lock: { locked: false } })).toThrow(ResponseShapeError);
    const body = homeBody("STABLE") as { device: { verdict: object } };
    expect(() => parseHome({ ...body, device: { ...body.device, verdict: { canStart: "yes" } } })).toThrow(ResponseShapeError);
    expect(() => parseHome({ ...homeBody("STABLE"), child: { displayName: "하늘" } })).toThrow(ResponseShapeError);
  });
});

describe("parseLiveView(BR6.15)", () => {
  it("말풍선을 읽고 seq · speaker · text가 틀린 항목은 버린다", () => {
    const parsed = parseLiveView(
      liveViewBody("ACTIVE", {
        items: [
          bubble({ seq: 1, speaker: "CHILD", text: "공룡" }),
          bubble({ seq: 2, speaker: "AI", text: "멋지다", final: false }),
          { seq: 0, speaker: "AI", text: "x" },
          { seq: 3, speaker: "GUARDIAN", text: "x" },
          { seq: 4, speaker: "AI", text: 7 },
          null,
        ],
        nextSeq: 1,
      }),
    );
    expect(parsed.items.map((item) => [item.seq, item.speaker, item.final])).toEqual([
      [1, "CHILD", true],
      [2, "AI", false],
    ]);
    expect(parsed.nextSeq).toBe(1);
    expect(parsed.status).toBe("ACTIVE");
  });

  it("모르는 status · endReason은 UNKNOWN이고 nextSeq가 틀리면 0이다", () => {
    const parsed = parseLiveView(liveViewBody("PAUSED", { endReason: "COSMIC_RAY", nextSeq: -3, items: "x", stale: "yes" }));
    expect(parsed.status).toBe("UNKNOWN");
    expect(parsed.endReason).toBe("UNKNOWN");
    expect(parsed.nextSeq).toBe(0);
    expect(parsed.items).toEqual([]);
    expect(parsed.stale).toBe(false);
    expect(() => parseLiveView({ status: "ACTIVE" })).toThrow(ResponseShapeError);
  });

  it("끝난 상태 판정(12 폴링 멈춤 · 17)", () => {
    expect(["ENDED", "ENDED_UNCONFIRMED", "ABORTED", "DECLINED", "FAILED"].every((status) => isTerminalStatus(status as never))).toBe(true);
    expect(["REQUESTED", "PREPARING", "DEVICE_ACK", "ACTIVE", "CLOSING", "UNKNOWN"].some((status) => isTerminalStatus(status as never))).toBe(false);
  });
});

describe("나머지 응답", () => {
  it("등록 · 시작 · 끝내기 · CSRF · 시험 로그인 응답을 읽는다", () => {
    expect(parseRegisterResult({ deviceId: "d", codePrefix: "7F3K", registeredAt: "t" })).toEqual({ deviceId: "d", codePrefix: "7F3K", registeredAt: "t" });
    expect(() => parseRegisterResult({ deviceId: "d", codePrefix: "7F3K" })).toThrow(ResponseShapeError);
    expect(parseStartResult({ sessionId: SESSION_ID, status: "REQUESTED" })).toEqual({ sessionId: SESSION_ID });
    expect(parseEndResult({ status: "CLOSING" })).toEqual({ status: "CLOSING" });
    expect(parseEndResult({ status: "WHATEVER" })).toEqual({ status: "UNKNOWN" });
    expect(parseCsrfToken({ token: "t", headerName: "X-CSRF-TOKEN" })).toEqual({ token: "t", headerName: "X-CSRF-TOKEN" });
    expect(() => parseCsrfToken({ token: "", headerName: "X" })).toThrow(ResponseShapeError);
    expect(parseDevLogin({ guardianId: "g" })).toEqual({ guardianId: "g" });
    expect(() => parseDevLogin([])).toThrow(ResponseShapeError);
  });
});

describe("말풍선 합치기(J23 · J120)", () => {
  const item = (seq: number, text: string, final = true): Bubble => ({
    seq,
    speaker: "CHILD",
    text,
    final,
    uncertain: false,
    startedAt: "",
    endedAt: null,
  });

  it("같은 seq는 새 값으로 덮어쓰고 seq 순서로 둔다", () => {
    const merged = mergeBubbles([item(1, "공"), item(2, "가", false)], [item(3, "나"), item(2, "가방", true)]);
    expect(merged.map((bubble) => [bubble.seq, bubble.text, bubble.final])).toEqual([
      [1, "공", true],
      [2, "가방", true],
      [3, "나", true],
    ]);
    const previous = [item(1, "공")];
    const unchanged = mergeBubbles(previous, []);
    expect(unchanged).toEqual(previous);
    expect(unchanged).not.toBe(previous);
  });

  it("상태 · nextSeq는 새 응답을 따르고 다른 세션 응답은 그대로 바꾼다", () => {
    const first = parseLiveView(liveViewBody("PREPARING", { items: [bubble({ seq: 1, speaker: "CHILD", text: "공" })], nextSeq: 1 }));
    const second = parseLiveView(liveViewBody("ACTIVE", { items: [bubble({ seq: 2, speaker: "AI", text: "응" })], nextSeq: 2 }));
    const merged = mergeLiveView(first, second);
    expect(merged.status).toBe("ACTIVE");
    expect(merged.nextSeq).toBe(2);
    expect(merged.items.map((entry) => entry.seq)).toEqual([1, 2]);
    expect(mergeLiveView(undefined, second)).toBe(second);
    const other = { ...second, sessionId: OTHER_SESSION_ID };
    expect(mergeLiveView(first, other)).toBe(other);
  });

  it("API 클라이언트 문맥 밖에서 부르면 숫자를 지어내지 않고 멈춘다", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => undefined);
    expect(() => renderHook(() => useApiClient())).toThrow(/ApiClientContext/);
    spy.mockRestore();
  });

  it("보호자 QueryClient는 자동 재시도 · 포커스 재조회가 없다(PD-12 진입 때 1회)", () => {
    const options = createParentQueryClient().getDefaultOptions();
    expect(options.queries).toMatchObject({ retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false });
    expect(options.mutations).toMatchObject({ retry: false });
  });

  it("보호자 QueryClient는 브라우저의 온라인 표시와 상관없이 요청한다(networkMode always, PD-12 · NFR4.16 · J361)", () => {
    const options = createParentQueryClient().getDefaultOptions();
    expect(options.queries?.networkMode).toBe("always");
    expect(options.mutations?.networkMode).toBe("always");
  });
});
