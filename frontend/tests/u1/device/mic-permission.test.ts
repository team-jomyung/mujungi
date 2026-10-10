import path from "node:path";
import { act, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { requestMicrophone } from "@/device/mic/micPermission";
import { fakeStream } from "../support/fakeAudio";
import { FakeSocket, helloOkPayload } from "../support/fakeSocket";
import { flush, registeredDeviceApi, renderDeviceApp } from "../support/deviceHarness";
import { codeOf, frontendRoot, relative, sourceFiles } from "../support/sourceScan";

// 마이크 허용 시험 (U1 3.5 Step 41 · NFR10.4 · PD-16 · BR9.5 · AC6.2.1 · AC6.2.2 · J121)
// getUserMedia는 「켜기」 클릭 뒤 micPermission.ts에서만 부르고, 거부되면 WSS를 열지 않는다.

function deniedError(name: string): Error {
  const error = new Error("denied");
  error.name = name;
  return error;
}

describe("requestMicrophone", () => {
  it("허용되면 스트림을 돌려주고 반향 제거를 켠 모노 오디오만 청한다(J121)", async () => {
    const { stream } = fakeStream();
    const getUserMedia = vi.fn(async () => stream);
    const result = await requestMicrophone({ getUserMedia } as unknown as MediaDevices);
    expect(result).toEqual({ granted: true, stream });
    expect(getUserMedia).toHaveBeenCalledWith({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
      video: false,
    });
  });

  it.each(["NotAllowedError", "SecurityError", "PermissionDeniedError"])("%s는 거부(denied)다", async (name) => {
    const getUserMedia = vi.fn(async () => {
      throw deniedError(name);
    });
    expect(await requestMicrophone({ getUserMedia } as unknown as MediaDevices)).toEqual({ granted: false, reason: "denied" });
  });

  it("장치 없음 · 이름 없는 오류 · 지원하지 않는 브라우저는 unavailable이다", async () => {
    const notFound = vi.fn(async () => {
      throw deniedError("NotFoundError");
    });
    expect(await requestMicrophone({ getUserMedia: notFound } as unknown as MediaDevices)).toEqual({ granted: false, reason: "unavailable" });
    const weird = vi.fn(async () => {
      throw "boom";
    });
    expect(await requestMicrophone({ getUserMedia: weird } as unknown as MediaDevices)).toEqual({ granted: false, reason: "unavailable" });
    expect(await requestMicrophone({} as MediaDevices)).toEqual({ granted: false, reason: "unavailable" });
    expect(await requestMicrophone(undefined)).toEqual({ granted: false, reason: "unavailable" });
  });

  it("getUserMedia를 부르는 코드는 src/device/mic/micPermission.ts 하나뿐이다(PD-16)", () => {
    const callers = sourceFiles(path.join(frontendRoot, "src"))
      .filter((file) => codeOf(file).includes("getUserMedia"))
      .map(relative);
    expect(callers).toEqual(["src/device/mic/micPermission.ts"]);
  });
});

describe("DeviceApp — 클릭 전 getUserMedia 0건, 거부면 WSS 0건(PD-16 · AC6.2.2)", () => {
  const sockets: FakeSocket[] = [];
  let getUserMedia: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    await import("@/device/mic/micPermission");
    await import("@/device/runtime/deviceRuntime");
    vi.useFakeTimers();
    sockets.length = 0;
    getUserMedia = vi.fn();
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: { getUserMedia } });
    vi.stubGlobal(
      "WebSocket",
      class extends FakeSocket {
        constructor(url: string) {
          super(url);
          sockets.push(this);
        }
      },
    );
  });

  afterEach(() => {
    Reflect.deleteProperty(navigator, "mediaDevices");
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("착지 · 유휴 미리 받기 동안 마이크를 묻지 않고, 거부되면 '마이크 허용 안 됨'이며 소켓을 열지 않는다", async () => {
    getUserMedia.mockRejectedValue(deniedError("NotAllowedError"));
    renderDeviceApp(registeredDeviceApi());
    await flush();
    await act(async () => {
      await vi.runOnlyPendingTimersAsync();
    });
    expect(getUserMedia).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(getUserMedia).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "mic-denied");
    expect(screen.getByRole("alert")).toHaveTextContent("마이크를 허용해 주세요");
    expect(sockets).toHaveLength(0);
    // 다시 누를 수 있다.
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(getUserMedia).toHaveBeenCalledTimes(2);
    expect(sockets).toHaveLength(0);
  });

  it("허용되면 같은 origin /ws/device로 접속해 HELLO를 보내고 HELLO_OK 뒤 대기가 된다(AC6.2.1)", async () => {
    const { stream, tracks } = fakeStream();
    getUserMedia.mockResolvedValue(stream);
    renderDeviceApp(registeredDeviceApi());
    await flush();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(sockets).toHaveLength(1);
    expect(sockets[0].url).toBe("ws://localhost:3000/ws/device");
    act(() => sockets[0].open());
    expect(sockets[0].sentOfType("HELLO")).toHaveLength(1);
    act(() => sockets[0].serverSend("HELLO_OK", helloOkPayload()));
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "standby");
    // jsdom에는 AudioContext가 없어 캡처를 세우지 못하므로 마이크 트랙을 붙잡지 않는다(DEVICE_READY mic UNAVAILABLE).
    expect(tracks[0].stop).toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("t19-off"));
    expect(sockets[0].closeCalls).toEqual([{ code: 1000, reason: "DEVICE_OFF" }]);
    expect(screen.getByTestId("t19-view")).toHaveAttribute("data-state", "off");
  });
});
