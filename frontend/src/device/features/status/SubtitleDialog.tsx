"use client";

import { useEffect, useState } from "react";
import { PROPOSED_COPY } from "@/shared/copy/ko";
import type { StatusDisplay } from "./StatusView";

// 게임식 대화창(docs/ui/device-subtitle.md). 무중이가 말하면 그 말(OUTPUT_BEGIN.text)을 한 글자씩 쓰고,
// 아이 차례(듣는 중)에는 초록 창으로 「말해 볼까?」, 생각 중에는 점 세 개를 보인다. 그 밖의 상태에서는 창을 내린다.
// 글은 화면에만 두고 기록 · 로그에 남기지 않는다(R-F14). 모션 줄이기 설정이면 한 번에 다 보인다.

const TYPE_INTERVAL_MS = 45;

export interface SubtitleDialogProps {
  readonly display: StatusDisplay;
  /** 지금 무중이 말 자막. 없으면 말하는 중에도 창을 띄우지 않는다. */
  readonly subtitle: string | null;
}

type DialogKind = "say" | "turn" | "think" | "none";

/** 자막을 보일 수 있는 상태(세션 화면). 묶음 사이 틈에 잠깐 생각 중 · 듣는 중으로 바뀌어도 자막이 있으면 창을 바꾸지 않는다. */
const SESSION_DISPLAYS: readonly StatusDisplay[] = ["speaking", "listening", "thinking", "standby", "closing"];

export function kindOf(display: StatusDisplay, subtitle: string | null): DialogKind {
  if (subtitle !== null && SESSION_DISPLAYS.includes(display)) return "say";
  if (display === "speaking") return "none";
  if (display === "listening") return "turn";
  if (display === "thinking") return "think";
  return "none";
}

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** 앞 글을 이어 붙인 글이면 이미 쓴 만큼을 이어 가고, 다른 글이면 처음부터 쓴다. */
function keptCount(previous: { readonly text: string | null; readonly count: number }, text: string): number {
  return previous.text !== null && text.startsWith(previous.text) ? previous.count : 0;
}

/** 한 글자씩 늘어나는 글(코드 포인트 단위, 이모지가 깨지지 않게). 나눠 온 글은 이어서 쓴다. */
function useTypewriter(text: string | null): { readonly shown: string; readonly done: boolean } {
  const [reduced] = useState(prefersReducedMotion);
  const [progress, setProgress] = useState<{ readonly text: string | null; readonly count: number }>({ text: null, count: 0 });
  const chars = text === null ? [] : [...text];
  const count = reduced ? chars.length : text === null ? 0 : keptCount(progress, text);

  useEffect(() => {
    if (text === null || reduced) return undefined;
    const total = [...text].length;
    const timer = window.setInterval(() => {
      setProgress((current) => {
        const next = keptCount(current, text) + 1;
        if (next >= total) window.clearInterval(timer);
        return { text, count: Math.min(next, total) };
      });
    }, TYPE_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [text, reduced]);

  return { shown: chars.slice(0, count).join(""), done: chars.length > 0 && count >= chars.length };
}

export function SubtitleDialog({ display, subtitle }: SubtitleDialogProps) {
  const kind = kindOf(display, subtitle);
  const typed = useTypewriter(kind === "say" ? subtitle : null);
  const copy = PROPOSED_COPY.device;

  return (
    <div
      className="mj-dialog"
      data-testid="t19-dialog"
      data-kind={kind}
      data-done={kind === "say" ? typed.done : undefined}
      aria-hidden={kind === "none"}
    >
      {kind === "say" ? (
        <>
          <span className="mj-dialog__name">{copy.dialogSpeaker}</span>
          {/* 화면 읽기는 한 글자씩이 아니라 다 쓴 글을 한 번 읽는다 */}
          <p className="mj-dialog__text" aria-hidden="true">{typed.shown}</p>
          <p className="mj-dialog__sr" aria-live="polite">{subtitle}</p>
          <span className="mj-dialog__next" aria-hidden="true" />
        </>
      ) : null}
      {kind === "turn" ? (
        <>
          <span className="mj-dialog__name">{copy.dialogTurnTag}</span>
          <p className="mj-dialog__text">
            <span className="mj-dialog__mic" aria-hidden="true" />
            {copy.dialogTurnBody}
          </p>
        </>
      ) : null}
      {kind === "think" ? (
        <>
          <span className="mj-dialog__name">{copy.dialogSpeaker}</span>
          <p className="mj-dialog__text">
            {copy.dialogThinking}
            <span className="mj-dialog__dots" aria-hidden="true">
              <span />
              <span />
              <span />
            </span>
          </p>
        </>
      ) : null}
    </div>
  );
}
