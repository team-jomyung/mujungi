// 마이크 허용 요청 — getUserMedia를 부르는 유일한 모듈 (U1 3.5 Step 38 · NFR10.4 · PD-16 · BR9.5)
// 「켜기」 클릭 핸들러가 동적 import로만 부른다. 착지 · 새로고침 · 재접속에서는 부르지 않는다(ESLint no-restricted-syntax가
// 다른 파일의 getUserMedia 호출을 막는다). 거부되면 연결하지 않고 '마이크를 허용해 주세요'를 보인다(BR9.5 · AC6.2.2).

export type MicResult =
  | { readonly granted: true; readonly stream: MediaStream }
  | { readonly granted: false; readonly reason: "denied" | "unavailable" };

const DENIED_ERROR_NAMES: readonly string[] = ["NotAllowedError", "SecurityError", "PermissionDeniedError"];

/** 마이크를 청한다. 반향 제거를 켠다(J121). 지원하지 않는 브라우저 · 장치 없음은 unavailable. */
export async function requestMicrophone(
  mediaDevices: MediaDevices | undefined = globalThis.navigator?.mediaDevices,
): Promise<MicResult> {
  if (mediaDevices === undefined || typeof mediaDevices.getUserMedia !== "function") {
    return { granted: false, reason: "unavailable" };
  }
  try {
    const stream = await mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        channelCount: 1,
      },
      video: false,
    });
    return { granted: true, stream };
  } catch (error) {
    // DOMException은 환경에 따라 Error의 하위 형이 아닐 수 있어 name만 본다.
    const name =
      typeof error === "object" && error !== null && typeof (error as { name?: unknown }).name === "string"
        ? (error as { name: string }).name
        : "";
    return { granted: false, reason: DENIED_ERROR_NAMES.includes(name) ? "denied" : "unavailable" };
  }
}
