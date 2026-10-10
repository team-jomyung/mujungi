import type { WebSocketLike } from "@/device/ws/wsClient";

// 기기 WSS 시험용 가짜 소켓 (U1 3.5 Step 41 · unit-test-instructions 'WebSocket은 시험용 가짜 소켓')
// 서버 쪽 메시지를 계약 4.2 봉투 모양으로 넣고, 기기가 보낸 텍스트 · 바이너리를 기록한다.

export interface SentEnvelope {
  readonly type: string;
  readonly seq: number;
  readonly sentAt: string;
  readonly sessionId?: string;
  readonly payload: Record<string, unknown>;
}

export interface ServerSendOptions {
  readonly seq?: number;
  readonly opId?: string;
  readonly sessionId?: string;
}

const CONNECTING = 0;
const OPEN = 1;
const CLOSED = 3;

export class FakeSocket implements WebSocketLike {
  binaryType: BinaryType = "blob";
  readyState = CONNECTING;
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  readonly sent: Array<string | Uint8Array> = [];
  readonly closeCalls: Array<{ readonly code: number | undefined; readonly reason: string | undefined }> = [];
  private serverSeq = 0;

  constructor(readonly url: string = "ws://test/ws/device") {}

  send(data: string | ArrayBufferView | ArrayBuffer): void {
    if (typeof data === "string") {
      this.sent.push(data);
      return;
    }
    if (data instanceof ArrayBuffer) {
      this.sent.push(new Uint8Array(data.slice(0)));
      return;
    }
    this.sent.push(new Uint8Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)));
  }

  /** 기기(클라이언트)가 닫는다. 실제 브라우저처럼 close 사건이 뒤따른다. */
  close(code?: number, reason?: string): void {
    this.closeCalls.push({ code, reason });
    this.finish(code ?? 1005);
  }

  open(): void {
    this.readyState = OPEN;
    this.onopen?.(new Event("open"));
  }

  /** 서버가 텍스트 봉투를 보낸다. seq는 지정하지 않으면 1부터 는다. */
  serverSend(type: string, payload: Record<string, unknown>, options: ServerSendOptions = {}): void {
    const seq = options.seq ?? (this.serverSeq += 1);
    const envelope: Record<string, unknown> = { type, seq, sentAt: "2026-10-15T01:00:00.000Z", payload };
    if (options.opId !== undefined) {
      envelope.opId = options.opId;
    }
    if (options.sessionId !== undefined) {
      envelope.sessionId = options.sessionId;
    }
    this.serverRaw(JSON.stringify(envelope));
  }

  serverRaw(text: string): void {
    this.onmessage?.({ data: text } as MessageEvent);
  }

  serverBinary(bytes: Uint8Array): void {
    const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    this.onmessage?.({ data: buffer } as MessageEvent);
  }

  /** 서버가 닫는다(닫기 코드). */
  serverClose(code: number): void {
    this.finish(code);
  }

  sentEnvelopes(): SentEnvelope[] {
    return this.sent.filter((item): item is string => typeof item === "string").map((item) => JSON.parse(item) as SentEnvelope);
  }

  sentOfType(type: string): SentEnvelope[] {
    return this.sentEnvelopes().filter((envelope) => envelope.type === type);
  }

  sentBinary(): Uint8Array[] {
    return this.sent.filter((item): item is Uint8Array => typeof item !== "string");
  }

  private finish(code: number): void {
    this.readyState = CLOSED;
    this.onclose?.({ code } as CloseEvent);
  }
}

/** HELLO_OK payload(계약 4.2). */
export function helloOkPayload(heartbeatIntervalMs = 5000): Record<string, unknown> {
  return {
    connectionId: "00000000-0000-4000-8000-000000000060",
    serverTime: "2026-10-15T01:00:00.000Z",
    heartbeatIntervalMs,
    audio: "pcm16le/24000/1",
  };
}
