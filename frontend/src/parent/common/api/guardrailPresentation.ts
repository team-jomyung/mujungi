/** 선택 표시 사실: 구조 실패는 객체 전체 null, 미래 문자열 값은 UNKNOWN. */
export interface GuardrailPresentation {
  readonly schemaVersion: 1;
  readonly notice: "UNKNOWN" | "PENDING" | "COMPLETED" | "FAILED";
  readonly participation: "UNKNOWN" | "PENDING" | "ACCEPTED" | "REFUSED" | "NO_RESPONSE";
  readonly goodbye: "UNKNOWN" | "NONE" | "PENDING" | "PLAYING" | "COMPLETED" | "STOPPED" | "FAILED";
  readonly confirmedStopBubbleSeq: number | null;
}

function known<T extends string>(value: string, allowed: readonly T[]): T | "UNKNOWN" {
  return allowed.find((candidate) => candidate === value) ?? "UNKNOWN";
}

export function parseGuardrailPresentation(value: unknown): GuardrailPresentation | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const root = value as Record<string, unknown>;
  const seq = root.confirmedStopBubbleSeq;
  if (root.schemaVersion !== 1 || typeof root.notice !== "string" || typeof root.participation !== "string" ||
    typeof root.goodbye !== "string" || !(seq === null || (typeof seq === "number" && Number.isSafeInteger(seq) && seq > 0))) return null;
  return {
    schemaVersion: 1,
    notice: known(root.notice, ["PENDING", "COMPLETED", "FAILED"]),
    participation: known(root.participation, ["PENDING", "ACCEPTED", "REFUSED", "NO_RESPONSE"]),
    goodbye: known(root.goodbye, ["NONE", "PENDING", "PLAYING", "COMPLETED", "STOPPED", "FAILED"]),
    confirmedStopBubbleSeq: seq,
  };
}
