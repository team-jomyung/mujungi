export type StartRequest = Readonly<{ idempotencyKey: string; goalSelectionIds: readonly string[] }>;
export type StartResult = { kind: "accepted"; sessionId: string } | { kind: "rejected"; code: string };
export type StartOutcome = StartResult | { kind: "unknown" };
export type StartPort = (request: StartRequest) => Promise<StartResult>;
/** 한 번에 연습할 목표 수 상한(2026-10-27 사람 결정 3 → 1, contracts/deviations.md FT-1027-a). */
export const MAX_PRACTICE_GOALS: number = 1;

export function ownedSelection(selected: readonly string[], owned: readonly string[]): string[] {
  const available = new Set(owned);
  return [...new Set(selected)].filter((id) => available.has(id)).sort();
}

/** 미확인 요청은 같은 키·선택을 보존하고 확정 거절 뒤 명시 재시도만 새 키를 쓴다. */
export class PracticeStartCoordinator {
  private request: StartRequest | null = null;
  private running: Promise<StartOutcome> | null = null;
  private outcome: StartOutcome | null = null;
  constructor(private readonly port: StartPort, private readonly key: () => string) {}

  submit(selected: readonly string[], owned: readonly string[]): Promise<StartOutcome> {
    if (this.running) return this.running;
    if (this.outcome?.kind === "accepted") return Promise.resolve(this.outcome);
    const ids = ownedSelection(selected, owned);
    if (!this.request || this.outcome?.kind === "rejected") {
      if (ids.length === 0) return Promise.reject(new Error("선택 목표가 없어요."));
      if (ids.length > MAX_PRACTICE_GOALS) return Promise.resolve({ kind: "rejected", code: "VALIDATION_FAILED" });
      this.request = Object.freeze({ idempotencyKey: this.key(), goalSelectionIds: Object.freeze(ids) });
    }
    const original = this.request;
    this.running = Promise.resolve().then(() => this.port(original)).then((result): StartOutcome => {
      this.outcome = result;
      return result;
    }).catch((): StartOutcome => {
      this.outcome = { kind: "unknown" };
      return this.outcome;
    }).finally(() => { this.running = null; });
    return this.running;
  }

  /** 아직 끝나지 않은 시작 요청(없으면 null). */
  get pending(): Promise<StartOutcome> | null { return this.running; }
  /** 마지막으로 끝난 요청의 결과(없으면 null). */
  get lastOutcome(): StartOutcome | null { return this.outcome; }
  /** 처음 보낸 요청의 목표 수(요청 전이면 0). */
  get requestedCount(): number { return this.request?.goalSelectionIds.length ?? 0; }
}

/**
 * 연습 화면을 떠났다 돌아와도 진행 중인 시작 요청을 이어 보도록 화면 밖에 둔다. 상황 준비(최대 1분, FT-1030-a) 동안 다른 탭으로 갔다 오면
 * 같은 요청의 준비 중 상태를 다시 보이고, 끝난 결과도 그대로 받는다. 같은 탭 안 화면 이동만 지키며 새로 고침은 지키지 않는다.
 */
export class PracticeStartStore {
  private coordinator: PracticeStartCoordinator | null = null;

  /** 보관된 조정기를 쓰고, 없으면 새로 만든다. */
  acquire(port: StartPort, key: () => string): PracticeStartCoordinator {
    // 떠나 있는 동안 시작이 수락됐으면 지난 세션 화면으로 옮기지 않는다(진행 중이면 진입 상태가 안내한다). 다음 시작은 새 요청으로 한다
    if (this.coordinator?.pending === null && this.coordinator.lastOutcome?.kind === "accepted") this.coordinator = null;
    this.coordinator ??= new PracticeStartCoordinator(port, key);
    return this.coordinator;
  }

  /** 보관한 요청을 잊는다(시험 사이 격리용). */
  clear(): void { this.coordinator = null; }

  /** 연습 준비 요청이 아직 끝나지 않았는가(화면에 돌아왔을 때 목표 선택을 처음부터 잠근다). */
  get preparing(): boolean { return this.coordinator?.pending != null; }

  /** 결과를 화면이 받아 처리했다: 수락 · 확정 거절이면 다음 시작은 새 조정기로 한다(미확인은 같은 키로 다시 확인하도록 남긴다). */
  settled(coordinator: PracticeStartCoordinator, outcome: StartOutcome): void {
    if (outcome.kind !== "unknown" && this.coordinator === coordinator) this.coordinator = null;
  }
}

export const practiceStartStore = new PracticeStartStore();
