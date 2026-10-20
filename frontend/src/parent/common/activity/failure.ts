import { PARENT_ROUTES } from "../navigation";
import { ACTIVITY_PROPOSED_COPY } from "@/shared/copy/activity";
import { PROPOSED_COPY, TEMP_COPY } from "@/shared/copy/ko";

// 시작 전에 FAILED로 끝나 08로 돌아왔을 때의 사유 한 줄 (U4a · AC3.1.9 · AC3.2.4 · AC3.2.5 · mockups 9-12)
// failureCode가 없으면(Core 로컬 FAILED — PREPARING 10초 시한 · 시작 실패) U1과 같이 DEVICE_NO_ACK 문구다.

/** 실패 코드 → 08 사유 문구. */
export function startFailureReason(failureCode: string | null): string {
  switch (failureCode) {
    case "PROVIDER_UNAVAILABLE":
      return TEMP_COPY.freeRealtimeUnavailable;
    case "SCENARIO_GEN_FAILED":
      return ACTIVITY_PROPOSED_COPY.free.rejectScenarioGenFailed;
    default:
      return PROPOSED_COPY.free.rejectDeviceNoAck;
  }
}

/** 기본 문구(DEVICE_NO_ACK)와 다른 사유를 가진 코드. 이 코드만 경로에 싣는다(기본은 U1과 같은 '?failed=1'). */
const DISTINCT_REASON_CODES: readonly string[] = ["PROVIDER_UNAVAILABLE", "SCENARIO_GEN_FAILED"];

/** 08로 돌아가는 경로('?failed=1', 기본과 다른 사유면 '&reason=코드'). */
export function freeFailedRoute(failureCode: string | null): string {
  const base = PARENT_ROUTES.freeFailed;
  return failureCode !== null && DISTINCT_REASON_CODES.includes(failureCode) ? `${base}&reason=${failureCode}` : base;
}
