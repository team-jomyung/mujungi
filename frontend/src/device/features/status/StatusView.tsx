import dynamic from "next/dynamic";
import { useState } from "react";
import { COPY } from "@/shared/copy/ko";
import { motionFor } from "../avatar/duckClips";
import { SubtitleDialog } from "./SubtitleDialog";

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
  /** 무중이 말 자막(OUTPUT_BEGIN.text). 말하는 중에 대화창으로 보인다. */
  readonly subtitle?: string | null;
  readonly onTurnOn: () => void;
  readonly onTurnOff: () => void;
}

// 3D 무중이는 three.js가 커서 첫 화면 번들에서 떼어 기기에서만 불러온다.
const DuckAvatar = dynamic(() => import("../avatar/DuckAvatar").then((m) => m.DuckAvatar), { ssr: false });

function Character({ expression }: { readonly expression: StatusDisplay }) {
  // 3D가 준비되기 전 · 실패하면 기본 도형 캐릭터가 그대로 보인다.
  const [duck, setDuck] = useState<"loading" | "ready" | "error">("loading");
  return (
    <div className="mj-character-stage" data-duck={duck}>
      <div className="mj-character" data-expression={expression} aria-hidden="true">
        <span className="mj-character__eye mj-character__eye--left" />
        <span className="mj-character__eye mj-character__eye--right" />
        <span className="mj-character__mouth" />
      </div>
      {duck !== "error" ? <DuckAvatar motion={motionFor(expression)} onStatus={setDuck} /> : null}
    </div>
  );
}

export function StatusView({ display, label, subLabel = null, subtitle = null, onTurnOn, onTurnOff }: StatusViewProps) {
  const isOff = display === "off" || display === "mic-request" || display === "mic-denied";
  // 대화창이 같은 뜻을 크게 보이는 상태에서는 라벨을 화면에서만 숨긴다(화면 읽기용으로는 남김)
  const dialogShown = display === "listening" || display === "thinking" || (display === "speaking" && subtitle !== null);
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
          <p
            className={dialogShown ? "mj-t19__label mj-t19__label--quiet" : "mj-t19__label"}
            role="status"
            aria-live="polite"
            data-testid="t19-label"
          >
            {label ?? ""}
          </p>
        ) : null}
        {!isOff && subLabel !== null ? (
          <p className="mj-t19__sublabel" data-testid="t19-sublabel">
            {subLabel}
          </p>
        ) : null}
      </div>
      {!isOff ? <SubtitleDialog display={display} subtitle={subtitle} /> : null}
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
