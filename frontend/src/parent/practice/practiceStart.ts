export type StartRequest = Readonly<{ idempotencyKey: string; goalSelectionIds: readonly string[] }>;
export type StartResult = { kind: "accepted"; sessionId: string } | { kind: "rejected"; code: string };
export type StartOutcome = StartResult | { kind: "unknown" };
export type StartPort = (request: StartRequest) => Promise<StartResult>;

export function ownedSelection(selected: readonly string[], owned: readonly string[]): string[] {
  const available = new Set(owned);
  return [...new Set(selected)].filter((id) => available.has(id)).sort();
}

/** 합의되지 않은 API 경로를 가정하지 않는 시작 조정자. 미확인 요청은 같은 키·선택을 보존한다. */
export class PracticeStartCoordinator {
  private request: StartRequest | null = null;
  private running: Promise<StartOutcome> | null = null;
  private outcome: StartOutcome | null = null;
  constructor(private readonly port: StartPort, private readonly key: () => string) {}

  submit(selected: readonly string[], owned: readonly string[]): Promise<StartOutcome> {
    if (this.running) return this.running;
    if (this.outcome?.kind === "accepted") return Promise.resolve(this.outcome);
    const ids = ownedSelection(selected, owned);
    if (!this.request || (this.outcome?.kind === "rejected" && JSON.stringify(ids) !== JSON.stringify(this.request.goalSelectionIds))) {
      if (ids.length === 0) return Promise.reject(new Error("선택 목표가 없어요."));
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
}
