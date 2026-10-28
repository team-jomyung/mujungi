"use client";

import { useCallback, useEffect, useRef, useState } from "react";

// 기기 자막 상태(docs/ui/device-subtitle.md). 응답을 나눠 보내면 묶음 사이에 소리가 잠깐 멎어 말하기 끝 → 시작이 반복된다.
// 그때마다 자막을 지우면 대화창이 깜빡이므로, 말하기가 끝나도 잠깐(SUBTITLE_HOLD_MS) 두었다가 지우고,
// 그 사이 새 묶음의 글이 오면 앞 글 뒤에 이어 붙인다(대화창은 이어서 쓴다).

/** 말하기가 끝난 뒤 자막을 두는 시간. 묶음 사이 틈보다 길고, 아이 차례로 넘어가는 것을 늦추지 않을 만큼 짧다. */
export const SUBTITLE_HOLD_MS = 1200;

export interface DeviceSubtitle {
  readonly subtitle: string | null;
  /** 재생 STARTED의 글(이어 붙임) 또는 null(STOPPED: 바로 지움). */
  readonly show: (text: string | null) => void;
  readonly speakingChanged: (speaking: boolean) => void;
  /** 끊김 · 끄기: 바로 지운다. */
  readonly clear: () => void;
}

export function useDeviceSubtitle(): DeviceSubtitle {
  const [subtitle, setSubtitle] = useState<string | null>(null);
  const holdTimer = useRef<number | null>(null);
  const speaking = useRef(false);

  const cancelHold = useCallback(() => {
    if (holdTimer.current !== null) {
      window.clearTimeout(holdTimer.current);
      holdTimer.current = null;
    }
  }, []);

  const clear = useCallback(() => {
    cancelHold();
    setSubtitle(null);
  }, [cancelHold]);

  const show = useCallback(
    (text: string | null) => {
      if (text === null) {
        clear();
        return;
      }
      const continuing = speaking.current || holdTimer.current !== null;
      cancelHold();
      setSubtitle((current) => (continuing && current !== null ? `${current} ${text}` : text));
    },
    [cancelHold, clear],
  );

  const speakingChanged = useCallback(
    (now: boolean) => {
      speaking.current = now;
      if (now) {
        cancelHold();
        return;
      }
      cancelHold();
      holdTimer.current = window.setTimeout(() => {
        holdTimer.current = null;
        setSubtitle(null);
      }, SUBTITLE_HOLD_MS);
    },
    [cancelHold],
  );

  useEffect(() => cancelHold, [cancelHold]);

  return { subtitle, show, speakingChanged, clear };
}
