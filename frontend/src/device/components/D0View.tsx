import { COPY, PROPOSED_COPY, formatRegistrationCode } from "@/shared/copy/ko";

// D0 처음 켜기 (U1 3.5 Step 38 · BR9.2 · BR9.4 · AC6.1.1 · AC6.1.2 · AC6.1.5 · C.D-00.30)
// 안내 + 4 · 4로 나눈 큰 코드, 등록되면 「등록되었어요」. 코드를 아직 받지 못한 동안만 '연결을 확인하는 중'을 보인다.
// 클릭 요소가 없다(BR9.2).

export type D0Display = "d0-checking" | "d0-code" | "d0-registered";

export interface D0ViewProps {
  readonly display: D0Display;
  readonly code: string | null;
}

export function D0View({ display, code }: D0ViewProps) {
  return (
    <section className="mj-d0" data-testid="d0-view" data-state={display}>
      <p className="mj-d0__brand">{COPY.brand}</p>
      {display === "d0-registered" ? (
        <p className="mj-d0__registered" role="status" data-testid="d0-registered">
          {COPY.device.d0Registered}
        </p>
      ) : null}
      {display === "d0-code" && code !== null ? (
        <>
          <p className="mj-d0__guide">{COPY.device.d0Guide}</p>
          <p className="mj-d0__code" data-testid="d0-code">
            {formatRegistrationCode(code)}
          </p>
        </>
      ) : null}
      {display === "d0-checking" || (display === "d0-code" && code === null) ? (
        <p className="mj-d0__checking" role="status" data-testid="d0-checking">
          {PROPOSED_COPY.device.d0Checking}
        </p>
      ) : null}
    </section>
  );
}
