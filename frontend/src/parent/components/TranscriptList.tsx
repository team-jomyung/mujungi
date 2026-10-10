"use client";

import { useEffect, useRef } from "react";
import { speakerPrefix } from "@/shared/copy/ko";
import type { Bubble } from "../api/types";

// 말풍선 목록 (U1 3.5 Step 39 · interaction-spec 3.10 · BR6.15 · BR7.5 · AC3.2.20(U4) · 접근성 P3 · P7)
// 무중(왼쪽) · 아이(오른쪽). 발화자 이름은 보조기기용 숨김 접두('무중:' · '{아이}:')로도 전한다. 목록은 role=log +
// aria-live polite로 새 말풍선만 조용히 알리고, 사용자가 아래를 보고 있을 때만 자동으로 내린다(위로 올려 보는 중이면 멈춤).
// items는 아이 전사와 게이트를 통과한 AI 말뿐이다(서버가 거른다, BR7.5). 확정 전 조각은 같은 seq로 덮어쓰고
// '(말하는 중)' 표시는 U4 몫이다(J120).

export interface TranscriptListProps {
  readonly items: readonly Bubble[];
  readonly childName: string | null;
}

/** 아래 끝에서 이 거리(px) 안이면 '아래를 보고 있음'으로 본다. */
const STICK_TO_BOTTOM_PX = 48;

export function TranscriptList({ items, childName }: TranscriptListProps) {
  const listRef = useRef<HTMLOListElement | null>(null);
  const followRef = useRef(true);
  const lastSeq = items.length === 0 ? 0 : items[items.length - 1].seq;

  useEffect(() => {
    const list = listRef.current;
    if (list !== null && followRef.current) {
      list.scrollTop = list.scrollHeight;
    }
  }, [lastSeq, items.length]);

  return (
    <ol
      ref={listRef}
      className="mj-transcript"
      role="log"
      aria-live="polite"
      aria-relevant="additions text"
      data-testid="live-transcript"
      onScroll={(event) => {
        const list = event.currentTarget;
        followRef.current = list.scrollHeight - list.scrollTop - list.clientHeight <= STICK_TO_BOTTOM_PX;
      }}
    >
      {items.map((bubble) => (
        <li
          key={bubble.seq}
          className={`mj-bubble mj-bubble--${bubble.speaker === "AI" ? "ai" : "child"}`}
          data-testid="live-bubble"
          data-seq={bubble.seq}
          data-speaker={bubble.speaker}
          data-final={bubble.final ? "true" : "false"}
        >
          <span className="mj-visually-hidden">{speakerPrefix(bubble.speaker, childName)}</span>
          <span className="mj-bubble__text">{bubble.text}</span>
        </li>
      ))}
    </ol>
  );
}
