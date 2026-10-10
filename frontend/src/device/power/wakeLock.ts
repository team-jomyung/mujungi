// 화면 켜짐 유지 (U1 3.5 Step 38 · BR9.5 · AC6.2.1 · 접근성 K5)
// 「켜기」로 마이크가 허용된 뒤 잡고 「끄기」 · 꺼짐에서 놓는다. 탭이 숨으면 브라우저가 풀어 버리므로 다시 보이면 다시 잡는다.
// Wake Lock을 지원하지 않는 브라우저는 아무것도 하지 않는다(연결 · 대화는 그대로).

export interface WakeLockHandle {
  release(): void;
}

interface WakeLockSentinelLike {
  readonly released: boolean;
  release(): Promise<void>;
}

interface WakeLockLike {
  request(type: "screen"): Promise<WakeLockSentinelLike>;
}

export function holdScreenWakeLock(
  wakeLock: WakeLockLike | undefined = (globalThis.navigator as Navigator & { wakeLock?: WakeLockLike } | undefined)
    ?.wakeLock,
  doc: Document | undefined = globalThis.document,
): WakeLockHandle {
  if (wakeLock === undefined) {
    return { release() {} };
  }
  let sentinel: WakeLockSentinelLike | null = null;
  let released = false;

  const acquire = () => {
    if (released || (sentinel !== null && !sentinel.released)) {
      return;
    }
    wakeLock.request("screen").then(
      (next) => {
        if (released) {
          void next.release();
          return;
        }
        sentinel = next;
      },
      () => {
        // 거부(배터리 절약 등)는 기능을 막지 않는다.
      },
    );
  };

  const onVisibilityChange = () => {
    if (doc?.visibilityState === "visible") {
      acquire();
    }
  };

  acquire();
  doc?.addEventListener("visibilitychange", onVisibilityChange);

  return {
    release() {
      if (released) {
        return;
      }
      released = true;
      doc?.removeEventListener("visibilitychange", onVisibilityChange);
      if (sentinel !== null && !sentinel.released) {
        void sentinel.release();
      }
      sentinel = null;
    },
  };
}
