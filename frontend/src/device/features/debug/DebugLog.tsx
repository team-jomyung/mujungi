// 타입만 가져온다: 오디오 모듈은 「켜기」 뒤 동적 import로 적재한다
import type { InputGateSnapshot } from "@/device/audio/inputGate";
import { TEMP_COPY } from "@/shared/copy/ko";

// ?debug=1 상태 로그 (U1 3.5 Step 38)
// 최근 화면 이름을 순서대로 보이고, 마지막 줄이 지금 화면 표시(t19-view · d0-view의 data-state)와 같다.
// 상태 이름만 담고 토큰 · 코드 · 전사는 담지 않는다.
// FT-1102-g: 입력 음량 관문 측정값(음량 숫자만)을 함께 보인다. 막대는 기준 상한의 두 배를 끝으로 지금 음량 · 기준을 견준다.

export interface DebugLogProps {
  readonly trail: readonly string[];
  /** 입력 음량 관문 상태. 켜기 전이면 null이다. */
  readonly inputGate?: InputGateSnapshot | null;
}

function percent(value: number, max: number): string {
  return `${Math.min(100, Math.max(0, (value / max) * 100))}%`;
}

function InputGatePanel({ gate }: { readonly gate: InputGateSnapshot }) {
  const copy = TEMP_COPY.debugInputGate;
  const meterMax = gate.thresholdMax * 2;
  const rows: readonly (readonly [string, string | number])[] = [
    [copy.state, copy.states[gate.state]],
    [copy.level, gate.level],
    [copy.threshold, gate.threshold],
    [copy.floor, gate.floor ?? copy.notMeasured],
    [copy.calibrationFrames, gate.calibrationFrames],
  ];
  return (
    <div className="mj-debug__gate" data-testid="debug-input-gate" data-state={gate.state}>
      <h3 className="mj-debug__title">{copy.title}</h3>
      <div className="mj-debug__meter" aria-hidden="true">
        <span className="mj-debug__meter-level" data-open={gate.state === "open"} style={{ width: percent(gate.level, meterMax) }} />
        <span className="mj-debug__meter-threshold" style={{ left: percent(gate.threshold, meterMax) }} />
      </div>
      <dl className="mj-debug__gate-rows">
        {rows.map(([label, value]) => (
          <div key={label} className="mj-debug__gate-row">
            <dt>{label}</dt>
            <dd data-testid={`debug-input-gate-${label}`}>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

export function DebugLog({ trail, inputGate = null }: DebugLogProps) {
  return (
    <section className="mj-debug" data-testid="debug-log" aria-label={TEMP_COPY.debugLogTitle}>
      <h2 className="mj-debug__title">{TEMP_COPY.debugLogTitle}</h2>
      <ol className="mj-debug__list">
        {trail.map((entry, index) => (
          <li key={`${index}-${entry}`} data-testid="debug-log-entry">
            {entry}
          </li>
        ))}
      </ol>
      {inputGate !== null ? <InputGatePanel gate={inputGate} /> : null}
    </section>
  );
}
