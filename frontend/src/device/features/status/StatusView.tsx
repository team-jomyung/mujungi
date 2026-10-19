import { COPY } from "@/shared/copy/ko";

// T19 상태 화면 최소 (U1 3.5 Step 38 · 39 · 57)
// 클릭 요소는 「켜기」 · 「끄기」뿐이고 화면에서 가장 큰 터치 영역(72px 이상)이며 「끄기」는 캐릭터와 떨어진 구석에 둔다.
// 상태는 캐릭터 표정(1차) + 라벨(보조, aria-live polite)로 보인다. 캐릭터 그림 자산 전까지 기본 도형으로 대신한다(OQ8).

export type StatusDisplay =
  | "off"
  | "mic-request"
  | "mic-denied"
  | "connecting"
  | "standby"
  | "listening"
  | "thinking"
  | "speaking"
  | "closing"
  | "reconnecting";

export interface StatusViewProps {
  readonly display: StatusDisplay;
  /**
   * 켜짐 상태의 라벨(대기 · 듣는 중 · 생각 중 · 말하는 중 · 마무리). 한 번 연결됐던 기기의 재접속 주기에는 마지막 라벨이다. 없으면 비운다
   * (처음 연결 중 · 한 번도 연결되지 않은 기기의 재접속 주기).
   */
  readonly label: string | null;
  /** 라벨 아래 보조 줄(U3: 재접속 주기의 "다시 연결하고 있어요", mockups 9-31). 없으면 그리지 않는다. */
  readonly subLabel?: string | null;
  readonly onTurnOn: () => void;
  readonly onTurnOff: () => void;
}

function Character({ expression }: { readonly expression: StatusDisplay }) {
  return (
    <div className="mj-character" data-expression={expression} aria-hidden="true">
      <span className="mj-character__eye mj-character__eye--left" />
      <span className="mj-character__eye mj-character__eye--right" />
      <span className="mj-character__mouth" />
    </div>
  );
}

export function StatusView({ display, label, subLabel = null, onTurnOn, onTurnOff }: StatusViewProps) {
  const isOff = display === "off" || display === "mic-request" || display === "mic-denied";
  return (
    <section className="mj-t19" data-testid="t19-view" data-state={display}>
      <div className="mj-t19__stage">
        <Character expression={display} />
        {display === "mic-denied" ? (
          <div className="mj-t19__mic-denied" role="alert">
            <p className="mj-t19__mic-title">{COPY.device.t19MicDeniedTitle}</p>
            <p className="mj-t19__mic-body">{COPY.device.t19MicDeniedBody}</p>
          </div>
        ) : null}
        {!isOff ? (
          <p className="mj-t19__label" role="status" aria-live="polite" data-testid="t19-label">
            {label ?? ""}
          </p>
        ) : null}
        {!isOff && subLabel !== null ? (
          <p className="mj-t19__sublabel" data-testid="t19-sublabel">
            {subLabel}
          </p>
        ) : null}
      </div>
      {isOff ? (
        <button
          type="button"
          className="mj-t19__power mj-t19__power--on"
          data-testid="t19-on"
          onClick={onTurnOn}
          disabled={display === "mic-request"}
        >
          {COPY.device.t19On}
        </button>
      ) : (
        <button type="button" className="mj-t19__power mj-t19__power--off" data-testid="t19-off" onClick={onTurnOff}>
          {COPY.device.t19Off}
        </button>
      )}
    </section>
  );
}
