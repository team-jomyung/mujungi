import { COPY } from "@/shared/copy/ko";

// T19 상태 화면 최소 (U1 3.5 Step 38 · BR9.5 · BR9.6 · AC6.2.1 ~ AC6.2.3 · AC6.2.5 · AC6.2.9 · C.D-19.31 · 39 · 57)
// 클릭 요소는 「켜기」 · 「끄기」뿐이고 화면에서 가장 큰 터치 영역(72px 이상)이며 「끄기」는 캐릭터와 떨어진 구석에 둔다.
// 상태는 캐릭터 표정(1차) + 라벨(보조, aria-live polite)로 보인다. 캐릭터 그림 자산 전까지 기본 도형으로 대신한다(OQ8).

export type T19Display =
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

export interface T19ViewProps {
  readonly display: T19Display;
  /** 켜짐 상태의 라벨(대기 · 듣는 중 · 생각 중 · 말하는 중 · 마무리). 재접속 주기에는 마지막 라벨이다(J360). 없으면 비운다. */
  readonly label: string | null;
  readonly onTurnOn: () => void;
  readonly onTurnOff: () => void;
}

function Character({ expression }: { readonly expression: T19Display }) {
  return (
    <div className="mj-character" data-expression={expression} aria-hidden="true">
      <span className="mj-character__eye mj-character__eye--left" />
      <span className="mj-character__eye mj-character__eye--right" />
      <span className="mj-character__mouth" />
    </div>
  );
}

export function T19View({ display, label, onTurnOn, onTurnOff }: T19ViewProps) {
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
