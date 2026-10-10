import { TEMP_COPY } from "@/shared/copy/ko";

// ?debug=1 상태 로그 (U1 3.5 Step 38 · BR9.6 · AC6.2.9)
// 최근 화면 이름을 순서대로 보이고, 마지막 줄이 지금 화면 표시(t19-view · d0-view의 data-state)와 같다.
// 상태 이름만 담고 토큰 · 코드 · 전사는 담지 않는다(BR8.7).

export interface DebugLogProps {
  readonly trail: readonly string[];
}

export function DebugLog({ trail }: DebugLogProps) {
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
    </section>
  );
}
