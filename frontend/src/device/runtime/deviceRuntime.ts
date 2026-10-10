import { startCapture, type CapturePipeline } from "../audio/capture";
import { AudioPlayer, createWebAudioOutput, fetchApprovedAudio, type AudioClip, type AudioOutput } from "../audio/player";
import { holdScreenWakeLock } from "../power/wakeLock";
import { DeviceSession } from "../ws/deviceSession";
import { connectWsClient, deviceSocketUrl, type WebSocketLike, type WsClient } from "../ws/wsClient";
import { CLOSE_CODES, DEVICE_OFF_REASON } from "@/shared/protocol/closeCodes";
import type { SessionView } from "@/shared/protocol/messages";
import type { Timing } from "@/shared/timing/timing";

// 켜짐 상태의 기기 실행부 (U1 3.5 Step 38 · BR9.5 · BR9.7 · BR7.1 · BR7.6 · PD-14)
// 「켜기」에서 마이크가 허용된 뒤 지연 적재되어(DeviceApp의 동적 import) 캡처 · 재생 · 화면 켜짐 유지 · WSS · 세션 처리를 묶는다.
// 재접속 때는 캡처 · 재생을 그대로 두고 소켓만 다시 연다(5초 간격, BR9.7). 「끄기」 · 4001 · 4401이면 모두 정리한다.

/** HELLO의 appVersion(1 ~ 64자). */
export const DEVICE_APP_VERSION = "mujoong-device-web/u1";

export interface DeviceRuntimeEvents {
  readonly onReady: () => void;
  readonly onClosed: (code: number, closedByDevice: boolean) => void;
  readonly onView: (view: SessionView) => void;
  readonly onSpeaking: (speaking: boolean) => void;
}

export interface DeviceRuntimeOptions {
  readonly audioContext: AudioContext | null;
  readonly stream: MediaStream;
  readonly timing: Timing;
  /** 기본은 같은 origin의 /ws/device. */
  readonly socketUrl?: string;
  readonly nowMs: () => number;
  readonly events: DeviceRuntimeEvents;
  readonly createSocket?: (url: string) => WebSocketLike;
}

export interface DeviceRuntime {
  /** WSS를 연다. 이미 열려 있거나 여는 중이면 아무것도 하지 않는다(단일 비행). */
  connect(): void;
  /** 모두 정리한다. closeSocket이면 1000 DEVICE_OFF로 닫는다(「끄기」). */
  shutdown(closeSocket: boolean): void;
}

/** AudioContext를 만들 수 없을 때의 출력(재생 UNAVAILABLE). */
const UNAVAILABLE_OUTPUT: AudioOutput<AudioClip> = {
  available: false,
  currentTime: () => 0,
  fromPcm: () => {
    throw new Error("재생 장치를 쓸 수 없다");
  },
  decode: () => Promise.reject(new Error("재생 장치를 쓸 수 없다")),
  schedule: () => {
    throw new Error("재생 장치를 쓸 수 없다");
  },
};

export async function startDeviceRuntime(options: DeviceRuntimeOptions): Promise<DeviceRuntime> {
  const { audioContext, stream, timing, events, nowMs } = options;
  let capture: CapturePipeline | null = null;
  if (audioContext !== null) {
    try {
      await audioContext.resume();
    } catch {
      // 재개 실패는 재생 준비 상태(DEVICE_READY.player)로 드러난다.
    }
    try {
      capture = await startCapture(audioContext, stream);
    } catch {
      capture = null;
    }
  }
  if (capture === null) {
    // 캡처를 세우지 못하면 마이크를 붙잡고 있지 않는다(DEVICE_READY.mic = UNAVAILABLE).
    for (const track of stream.getTracks()) {
      track.stop();
    }
  }

  const output: AudioOutput<AudioClip> = audioContext !== null ? createWebAudioOutput(audioContext) : UNAVAILABLE_OUTPUT;
  let client: WsClient | null = null;
  let shutDown = false;

  const player = new AudioPlayer<AudioClip>({
    output,
    fetchAudio: fetchApprovedAudio,
    queueMaxMs: timing.deviceOutputQueueMaxMs,
    nowMs,
    onMark: (sessionId, mark) => session.sendPlaybackMark(sessionId, mark),
    onSpeakingChange: events.onSpeaking,
  });

  const session = new DeviceSession({
    send: (type, payload, sessionId) => client?.send(type, payload, sessionId),
    sendBinary: (frame) => client?.sendBinary(frame),
    player,
    readiness: () => ({
      mic: capture !== null && capture.active ? "OK" : "UNAVAILABLE",
      player: output.available && audioContext?.state !== "closed" ? "OK" : "UNAVAILABLE",
    }),
    onInputWindowChange: () => capture?.resetBuffer(),
    onView: events.onView,
    nowMs,
  });

  capture?.setFrameHandler((frame) => session.handleInputFrame(frame));
  const wakeLock = holdScreenWakeLock();

  return {
    connect() {
      if (shutDown || client !== null) {
        return;
      }
      const opened: WsClient = connectWsClient(
        {
          url: options.socketUrl ?? deviceSocketUrl(window.location),
          appVersion: DEVICE_APP_VERSION,
          heartbeatFallbackMs: timing.deviceHeartbeatIntervalMs,
          nowMs,
          createSocket: options.createSocket,
        },
        {
          onReady: () => events.onReady(),
          onMessage: (message) => session.handleMessage(message),
          onOutputFrame: (frame) => session.handleOutputFrame(frame),
          onClose: (code, closedByDevice) => {
            if (client === opened) {
              client = null;
            }
            session.handleDisconnect();
            if (!shutDown) {
              events.onClosed(code, closedByDevice);
            }
          },
        },
      );
      client = opened;
    },
    shutdown(closeSocket: boolean) {
      if (shutDown) {
        return;
      }
      shutDown = true;
      const current = client;
      client = null;
      if (closeSocket && current !== null) {
        current.close(CLOSE_CODES.DEVICE_OFF, DEVICE_OFF_REASON);
      }
      session.handleDisconnect();
      capture?.stop();
      for (const track of stream.getTracks()) {
        track.stop();
      }
      wakeLock.release();
      if (audioContext !== null && audioContext.state !== "closed") {
        void audioContext.close().catch(() => undefined);
      }
    },
  };
}
