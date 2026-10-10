import { decodeFrame, FRAME_KIND_OUTPUT, type DecodedFrame } from "@/shared/protocol/frameCodec";
import {
  ackPayload,
  heartbeatPayload,
  helloPayload,
  parseServerMessage,
  type HelloOkMessage,
  type ServerMessage,
} from "@/shared/protocol/messages";
import {
  OP_ID_COMMANDS,
  buildEnvelope,
  createInboundDeduper,
  createSeqCounter,
  parseInboundEnvelope,
  serializeEnvelope,
  type DeviceToServerType,
  type ServerToDeviceType,
} from "@/shared/protocol/wsEnvelope";

// 기기 WSS 클라이언트 (U1 3.5 Step 38 · 계약 4.2 · BR3.6 · BR3.7 · BR9.7 · OD-8 · R3-4 · J27 · J123)
//
// - 같은 origin의 /ws/device에 붙는다. 쿠키(MUJOONG_DEVICE)와 Origin은 브라우저가 싣고, URL · HELLO에는 토큰이 없다(J29).
// - 열리면 HELLO를 보내고, HELLO_OK를 받으면 heartbeatIntervalMs마다 HEARTBEAT를 보낸다. HEARTBEAT 봉투의 sentAt은
//   송신 직전에 채운다(서버가 clockOffsetMs를 계산한다, OD-8 · R3-4). lastRttMs는 직전 HEARTBEAT_ACK까지의 왕복 시간이다.
// - opId가 붙은 서버 명령(START_SESSION · PLAY_APPROVED_CONTENT · STOP_OUTPUT · CLOSE_TRANSPORT)은 받자마자 ACK한다.
// - 보내는 seq는 연결마다 1부터, 받은 seq는 한 번만 처리한다. 모르는 type · 틀린 payload는 버리고 연결을 유지한다.
// - 서버가 보낸 0x01 · 형식이 틀린 바이너리는 버린다. 0x02만 넘긴다(재생 여부는 재생기가 정한다, BR7.6).

/** 브라우저 WebSocket 중 이 클라이언트가 쓰는 부분(시험 대역 주입용). */
export interface WebSocketLike {
  binaryType: BinaryType;
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  send(data: string | ArrayBufferView | ArrayBuffer): void;
  close(code?: number, reason?: string): void;
}

export interface WsClientHandlers {
  /** HELLO_OK를 받음(연결 준비, BR3.7). */
  readonly onReady: (message: HelloOkMessage) => void;
  /** HELLO_OK · HEARTBEAT_ACK 밖의 서버 메시지. */
  readonly onMessage: (message: ServerMessage) => void;
  /** 0x02 출력 프레임. */
  readonly onOutputFrame: (frame: DecodedFrame) => void;
  /** 소켓이 닫힘. closedByDevice는 이 클라이언트가 close()를 부른 경우다. */
  readonly onClose: (code: number, closedByDevice: boolean) => void;
}

export interface WsClientOptions {
  readonly url: string;
  readonly appVersion: string;
  /** HELLO_OK.heartbeatIntervalMs가 없을 때 쓰는 값(mujoong.timing.device.heartbeat-interval). */
  readonly heartbeatFallbackMs: number;
  /** 기기 벽시계(밀리초). */
  readonly nowMs: () => number;
  readonly createSocket?: (url: string) => WebSocketLike;
}

export interface WsClient {
  /** 제어 메시지를 보낸다. 열려 있지 않으면 버린다. */
  send(type: DeviceToServerType, payload: object, sessionId?: string): void;
  /** 바이너리 프레임을 보낸다. 열려 있지 않으면 버린다. */
  sendBinary(frame: Uint8Array): void;
  /** 기기가 닫는다(「끄기」는 1000 DEVICE_OFF). */
  close(code: number, reason: string): void;
  readonly isReady: boolean;
}

const OPEN = 1;
/** 답을 기다리는 HEARTBEAT 기록 상한(개수). */
const PENDING_HEARTBEAT_LIMIT = 16;

/** 같은 origin의 기기 WSS 주소(http → ws, https → wss). */
export function deviceSocketUrl(location: Pick<Location, "protocol" | "host">): string {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  return `${scheme}//${location.host}/ws/device`;
}

export function connectWsClient(options: WsClientOptions, handlers: WsClientHandlers): WsClient {
  const socket = (options.createSocket ?? ((url: string) => new WebSocket(url) as WebSocketLike))(options.url);
  socket.binaryType = "arraybuffer";
  const nextSeq = createSeqCounter();
  const isNewInbound = createInboundDeduper();
  const heartbeatSentAt = new Map<number, number>();
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  let hbSeq = 0;
  let lastRttMs: number | null = null;
  let ready = false;
  let closedByDevice = false;
  let closed = false;

  const sendText = (type: DeviceToServerType, payload: object, sessionId?: string) => {
    if (closed || socket.readyState !== OPEN) {
      return;
    }
    socket.send(serializeEnvelope(buildEnvelope(type, nextSeq(), options.nowMs(), payload, sessionId)));
  };

  const stopHeartbeat = () => {
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
    heartbeatSentAt.clear();
  };

  const sendHeartbeat = () => {
    hbSeq += 1;
    // sentAt은 sendText 안에서 송신 직전에 읽은 기기 시계다(OD-8).
    heartbeatSentAt.set(hbSeq, options.nowMs());
    if (heartbeatSentAt.size > PENDING_HEARTBEAT_LIMIT) {
      // 답이 오지 않은 오래된 HEARTBEAT 기록은 버린다(메모리 상한).
      const oldest = heartbeatSentAt.keys().next();
      if (oldest.done !== true) {
        heartbeatSentAt.delete(oldest.value);
      }
    }
    sendText("HEARTBEAT", heartbeatPayload(hbSeq, lastRttMs));
  };

  const handleText = (text: string) => {
    const envelope = parseInboundEnvelope(text);
    if (envelope === null || !isNewInbound(envelope.seq)) {
      return;
    }
    if (envelope.opId !== null && OP_ID_COMMANDS.includes(envelope.type as ServerToDeviceType)) {
      sendText("ACK", ackPayload(envelope.opId));
    }
    const message = parseServerMessage(envelope);
    if (message === null) {
      return;
    }
    if (message.type === "HELLO_OK") {
      if (ready) {
        return;
      }
      ready = true;
      const interval = message.heartbeatIntervalMs > 0 ? message.heartbeatIntervalMs : options.heartbeatFallbackMs;
      heartbeatTimer = setInterval(sendHeartbeat, interval);
      handlers.onReady(message);
      return;
    }
    if (message.type === "HEARTBEAT_ACK") {
      const sentAt = heartbeatSentAt.get(message.hbSeq);
      if (sentAt !== undefined) {
        heartbeatSentAt.delete(message.hbSeq);
        lastRttMs = Math.max(0, Math.round(options.nowMs() - sentAt));
      }
      return;
    }
    if (ready) {
      handlers.onMessage(message);
    }
  };

  const handleBinary = (data: ArrayBuffer) => {
    if (!ready) {
      return;
    }
    const decoded = decodeFrame(data);
    if (decoded.ok && decoded.frame.kind === FRAME_KIND_OUTPUT) {
      handlers.onOutputFrame(decoded.frame);
    }
  };

  socket.onopen = () => {
    sendText("HELLO", helloPayload(options.appVersion));
  };
  socket.onmessage = (event: MessageEvent) => {
    if (typeof event.data === "string") {
      handleText(event.data);
    } else if (event.data instanceof ArrayBuffer) {
      handleBinary(event.data);
    }
  };
  socket.onerror = () => {
    // 오류 뒤에는 close가 따라오므로 동작은 onclose가 정한다.
  };
  socket.onclose = (event: CloseEvent) => {
    if (closed) {
      return;
    }
    closed = true;
    ready = false;
    stopHeartbeat();
    handlers.onClose(event.code, closedByDevice);
  };

  return {
    send: sendText,
    sendBinary(frame: Uint8Array) {
      if (!closed && ready && socket.readyState === OPEN) {
        socket.send(frame);
      }
    },
    close(code: number, reason: string) {
      if (closed) {
        return;
      }
      closedByDevice = true;
      stopHeartbeat();
      socket.close(code, reason);
    },
    get isReady() {
      return ready && !closed;
    },
  };
}
