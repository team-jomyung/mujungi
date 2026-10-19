import { COPY } from "@/shared/copy/ko";

// 하단 탭 바 (U1 3.5 Step 39 · interaction-spec 3.1 · 접근성 O7)
// 홈 · 자유대화 · 연습 · 기록 네 탭. 잠금 사유가 있으면 자유대화 · 연습 탭은 흐리게 + aria-disabled이지만 초점은 받고,
// 누르면 화면이 이유 토스트를 보인다(disabled를 쓰지 않는다). 어디로 갈지는 화면이 정한다(04 잠금 → 05 스텁, 12 → 그대로 등).

export type TabKey = "home" | "free" | "practice" | "records";

export interface BottomTabBarProps {
  readonly active: TabKey;
  /** 자유대화 · 연습 탭 잠금 이유(토스트 문구). null이면 열림. */
  readonly lockMessage: string | null;
  readonly onNavigate: (tab: TabKey) => void;
  readonly onLockedPress: (message: string) => void;
}

const TABS: ReadonlyArray<{ readonly key: TabKey; readonly label: string; readonly lockable: boolean }> = [
  { key: "home", label: COPY.tabs.home, lockable: false },
  { key: "free", label: COPY.tabs.free, lockable: true },
  { key: "practice", label: COPY.tabs.practice, lockable: true },
  { key: "records", label: COPY.tabs.records, lockable: false },
];

export function BottomTabBar({ active, lockMessage, onNavigate, onLockedPress }: BottomTabBarProps) {
  return (
    <nav className="mj-tabbar" data-testid="bottom-tab-bar">
      <div className="mj-tabbar__list" role="tablist">
        {TABS.map((tab) => {
          const locked = tab.lockable && lockMessage !== null;
          const selected = tab.key === active;
          return (
            <button
              key={tab.key}
              type="button"
              role="tab"
              className={`mj-tabbar__tab${selected ? " mj-tabbar__tab--active" : ""}${locked ? " mj-tabbar__tab--locked" : ""}`}
              aria-selected={selected}
              aria-disabled={locked ? true : undefined}
              data-testid={`tab-${tab.key}`}
              onClick={() => {
                if (locked && lockMessage !== null) {
                  onLockedPress(lockMessage);
                  return;
                }
                onNavigate(tab.key);
              }}
            >
              <span className={`mj-tabbar__icon mj-tabbar__icon--${tab.key}`} aria-hidden="true" />
              {tab.label}
            </button>
          );
        })}
      </div>
    </nav>
  );
}
