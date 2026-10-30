import { BargeInDetector } from "../audio/bargeIn";
import { startCapture, type CapturePipeline } from "../audio/capture";
import { AudioPlayer, createWebAudioOutput, fetchApprovedAudio, type AudioClip, type AudioOutput } from "../audio/player";
import { holdScreenWakeLock } from "../power/wakeLock";
import { DeviceSession } from "../ws/deviceSession";
import { connectWsClient, deviceSocketUrl, type WebSocketLike, type WsClient } from "../ws/wsClient";
import { DeviceStateReporter } from "./deviceStateReporter";
import { CLOSE_CODES, DEVICE_OFF_REASON } from "@/shared/protocol/closeCodes";
import type { SessionView } from "@/shared/protocol/messages";
import type { Timing } from "@/shared/timing/timing";

// 켜짐 상태의 기기 실행부 (U1 3.5 Step 38 · BR9.5 · BR9.7 · BR7.1 · BR7.6 · PD-14)
// 「켜기」에서 마이크가 허용된 뒤 지연 적재되어(DeviceApp의 동적 import) 캡처 · 재생 · 화면 켜짐 유지 · WSS · 세션 처리를 묶는다.
// 재접속 때는 캡처 · 재생을 그대로 두고 소켓만 다시 연다(간격은 상태 기계, BR9.7 · U3-R16). 「끄기」 · 4001 · 4401이면 모두 정리한다.
// U3: HELLO.resume으로 끊긴 세션을 잇고(U3-R16), 소리가 나는 동안 마이크 음성이 쌓이면 재생을 멈춰 끼어들기를 알리며(U3-J20),
// 마이크 트랙 · 재생 장치 · 탭 보임 · 화면 켜짐 유지가 바뀌면 DEVICE_STATE로 보고한다(U3-R17).

/** HELLO의 appVersion(1 ~ 64자). */
export const DEVICE_APP_VERSION = "mujoong-device-web/u1";

export interface DeviceRuntimeEvents {
  readonly onReady: () => void;
  readonly onClosed: (code: number, closedByDevice: boolean) => void;
  readonly onView: (view: SessionView) => void;
  readonly onSpeaking: (speaking: boolean) => void;
  /** 무중이 말 자막(OUTPUT_BEGIN.text). 소리가 시작될 때 글, 끊기면 null. 없는 재생은 앞 자막을 둔다. */
  readonly onSubtitle?: (text: string | null) => void;
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

  const doc: Document | undefined = globalThis.document;
  const reporter = new DeviceStateReporter(
    {
      mic: capture !== null && capture.active ? "OK" : "LOST",
      player: output.available ? "OK" : "ERROR",
      visibility: doc?.visibilityState === "hidden" ? "HIDDEN" : "VISIBLE",
      wakeLock: "RELEASED",
    },
    (payload) => client?.send("DEVICE_STATE", payload),
  );

  const player = new AudioPlayer<AudioClip>({
    output,
    fetchAudio: fetchApprovedAudio,
    queueMaxMs: timing.deviceOutputQueueMaxMs,
    nowMs,
    progressIntervalMs: timing.devicePlaybackMarkIntervalMs,
    onMark: (sessionId, mark) => {
      // 재생 장치 오류 · 회복을 DEVICE_STATE로 알린다(U3-R17)
      if (mark.status === "FAILED" && mark.failureReason === "AUDIO_DEVICE_ERROR") {
        reporter.update({ player: "ERROR" });
      } else if (mark.status === "STARTED") {
        reporter.update({ player: "OK" });
      }
      if (mark.status === "STARTED") {
        const text = session.takeSubtitle(mark.playbackId);
        if (text !== null) events.onSubtitle?.(text);
      } else if (mark.status !== "PROGRESS") {
        session.takeSubtitle(mark.playbackId);
        if (mark.status === "STOPPED") events.onSubtitle?.(null);
      }
      session.sendPlaybackMark(sessionId, mark);
    },
    onSpeakingChange: events.onSpeaking,
  });

  // 끼어들기 로컬 감지(U3-J20): 소리가 나는 동안 아이 음성이 쌓이면 재생을 멈춘다(PLAYBACK_MARK STOPPED · LOCAL_VOICE_ACTIVITY)
  const bargeIn = new BargeInDetector(
    { localVoiceMinMs: timing.gateLocalVoiceMinMs, bargeInStopMs: timing.gateBargeInStopMs },
    () => player.stopAll("LOCAL_VOICE_ACTIVITY"),
  );

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

  capture?.setFrameHandler((frame) => {
    // 참여 확인 안내 녹음 · 연습 첫 대본 중에는 끼어들기로 보지 않는다(서버도 이 동안 아이 소리를 무음으로 바꿔 공급자에 보낸다)
    bargeIn.feed(frame, player.isSpeaking && !player.isPlayingNotice && !player.isPlayingUninterruptible);
    session.handleInputFrame(frame);
  });
  const wakeLock = holdScreenWakeLock(undefined, undefined, (held) =>
    reporter.update({ wakeLock: held ? "HELD" : "RELEASED" }),
  );
  const onVisibilityChange = () => reporter.update({ visibility: doc?.visibilityState === "hidden" ? "HIDDEN" : "VISIBLE" });
  doc?.addEventListener("visibilitychange", onVisibilityChange);
  const onMicEnded = () => reporter.update({ mic: "LOST" });
  for (const track of stream.getAudioTracks?.() ?? []) {
    track.addEventListener?.("ended", onMicEnded);
  }

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
          resumeSessionId: () => session.resumeSessionId,
          createSocket: options.createSocket,
        },
        {
          onReady: (message) => {
            // 화면 전이(WS_READY)를 먼저 하고, 이어가기 결과의 표시 상태를 그 위에 얹는다(U3-R16)
            events.onReady();
            session.handleReady(message.session);
            reporter.connected();
          },
          onMessage: (message) => session.handleMessage(message),
          onOutputFrame: (frame) => session.handleOutputFrame(frame),
          onClose: (code, closedByDevice) => {
            if (client === opened) {
              client = null;
            }
            session.handleDisconnect();
            reporter.disconnected();
            bargeIn.reset();
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
      // 꺼짐은 세션을 잇지 않는다(다음 「켜기」는 새 연결 주기)
      session.forget();
      reporter.disconnected();
      doc?.removeEventListener("visibilitychange", onVisibilityChange);
      for (const track of stream.getAudioTracks?.() ?? []) {
        track.removeEventListener?.("ended", onMicEnded);
      }
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
