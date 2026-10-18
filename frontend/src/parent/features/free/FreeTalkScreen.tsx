"use client";

import { useState } from "react";
import type { FreeTalkContext, SituationType, SourceType } from "../../common/api/types";
import { BottomTabBar } from "../../common/components/BottomTabBar";
import { ChipGroup, type ChipOption } from "./ChipGroup";
import { TextArea200 } from "./TextArea200";
import { useFreeTalkScreen } from "./useFreeTalkScreen";
import { COPY } from "@/shared/copy/ko";

// 08 자유대화 준비 (U1 3.5 Step 40 · BR6.1 · BR6.2 · BR6.4 · AC3.1.1 · AC3.1.3 · AC3.1.4 · AC3.1.6 · AC3.1.8 · mockups 9-12 · J118 · J120)
// 네 칸은 모두 선택(비워도 시작), 서술 칸 200자에서 막힘, 칩은 단일 선택. 전송 중 「자유대화 시작」이 꺼진다.
// 202 → 12. 거절은 사유 문구 하나(mockups 9-12)를 보이고 08에 머문다. 12에서 FAILED로 돌아오면 사유 카드 한 종류(J120).
// idempotencyKey 재사용(표 2.9-C): 응답 없음 · 시간 초과 · 403 CSRF는 같은 키(같은 본문일 때), 그 밖의 거절 · 로컬 FAILED 재생 뒤에는 새 키.
// 토스트 「다시 시도」는 실패한 렌더의 클로저가 아니라 지금 화면의 폼을 다시 제출한다. 보이는 입력으로 보내고(고쳤으면 새 키, J232)
// 「자유대화 시작」 요청이 진행 중이면 막힌다(BR6.4 · J362).
// 입력 원문은 시작 요청 본문으로만 가고 화면 밖 저장소에 두지 않는다(NFR3.1).

const SITUATION_OPTIONS: readonly ChipOption<SituationType>[] = [
  { value: "FRIEND", label: COPY.free.situationFriend },
  { value: "SCHOOL", label: COPY.free.situationSchool },
  { value: "FAMILY", label: COPY.free.situationFamily },
  { value: "ACADEMY", label: COPY.free.situationAcademy },
  { value: "OTHER", label: COPY.free.situationOther },
];

const SOURCE_OPTIONS: readonly ChipOption<SourceType>[] = [
  { value: "SEEN", label: COPY.free.sourceSeen },
  { value: "HEARD_FROM_CHILD", label: COPY.free.sourceHeardFromChild },
];

export interface FreeTalkScreenProps {
  /** 12에서 FAILED로 돌아왔는지(사유 카드 한 종류, J120). */
  readonly returnedFailed: boolean;
  /** 돌아온 FAILED의 실패 코드(U4a-J16, 없으면 DEVICE_NO_ACK 문구). */
  readonly failureCode?: string | null;
}

export function FreeTalkScreen({ returnedFailed, failureCode = null }: FreeTalkScreenProps) {
  const { submitting, reason, formRef, submit, handleNavigate, onLockedPress } = useFreeTalkScreen(returnedFailed, failureCode);
  const [knownContent, setKnownContent] = useState("");
  const [situation, setSituation] = useState<SituationType | null>(null);
  const [source, setSource] = useState<SourceType | null>(null);
  const [question, setQuestion] = useState("");

  const buildContext = (): FreeTalkContext | undefined => {
    const context: { -readonly [K in keyof FreeTalkContext]: FreeTalkContext[K] } = {};
    if (knownContent.length > 0) {
      context.knownContent = knownContent;
    }
    if (situation !== null) {
      context.situationType = situation;
    }
    if (source !== null) {
      context.sourceType = source;
    }
    if (question.length > 0) {
      context.question = question;
    }
    return Object.keys(context).length > 0 ? context : undefined;
  };

  return (
    <div className="mj-screen" data-testid="free-screen">
      <header className="mj-header">
        <h1 className="mj-header__title">{COPY.free.title}</h1>
      </header>
      <main className="mj-screen__body">
        <form
          ref={formRef}
          className="mj-form"
          noValidate
          data-testid="free-form"
          onSubmit={(event) => {
            event.preventDefault();
            submit(buildContext());
          }}
        >
          <TextArea200
            label={COPY.free.knownContentLabel}
            value={knownContent}
            onChange={setKnownContent}
            placeholder={COPY.free.knownContentExample}
            disabled={submitting}
            testId="free-known-content"
          />
          <ChipGroup
            name="situationType"
            legend={COPY.free.situationLabel}
            options={SITUATION_OPTIONS}
            value={situation}
            onChange={setSituation}
            disabled={submitting}
            testIdPrefix="free-situation"
          />
          <ChipGroup
            name="sourceType"
            legend={COPY.free.sourceLabel}
            options={SOURCE_OPTIONS}
            value={source}
            onChange={setSource}
            disabled={submitting}
            testIdPrefix="free-source"
          />
          <TextArea200
            label={COPY.free.questionLabel}
            value={question}
            onChange={setQuestion}
            placeholder={COPY.free.questionExample}
            disabled={submitting}
            testId="free-question"
          />
          <div aria-live="assertive" className="mj-free__reason-slot">
            {reason !== null ? (
              <p className="mj-reason-card" role="alert" data-testid="free-reason">
                {reason}
              </p>
            ) : null}
          </div>
          <button type="submit" className="mj-button mj-button--primary mj-button--block" data-testid="free-start" disabled={submitting}>
            {COPY.free.start}
          </button>
        </form>
      </main>
      <BottomTabBar active="free" lockMessage={null} onNavigate={handleNavigate} onLockedPress={onLockedPress} />
    </div>
  );
}
