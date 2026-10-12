import { CHILD_ID } from "../../u1/support/fixtures";

// U2 프런트 시험 픽스처 (docs/units/u2/design.md 7절). 값은 모두 합성이고 비밀이 없다.

export const GUARDIAN_ID = "00000000-0000-4000-8000-000000000001";
export const EXPRESSION_ID_1 = "00000000-0000-4000-8000-000000000061";
export const EXPRESSION_ID_2 = "00000000-0000-4000-8000-000000000062";

export interface MeOptions {
  readonly consentStatus?: string;
  readonly childRegistered?: boolean;
  readonly deletionStatus?: string;
}

/** GET /api/me · POST /api/auth/google 200(me-response.schema.json). */
export function meBody(options: MeOptions = {}): Record<string, unknown> {
  return {
    guardianId: GUARDIAN_ID,
    consentStatus: options.consentStatus ?? "GIVEN",
    childRegistered: options.childRegistered ?? true,
    deletionStatus: options.deletionStatus ?? "NONE",
  };
}

const AI_NOTICE = { heading: "생성형 AI 안내", body: "무중은 생성형 AI가 아이와 대화하는 서비스예요. 아이가 듣는 말은 사람이 아니라 AI가 만들어요." };

/** GET /api/policies/current 200(policy-current-response.schema.json, 시드 002와 같은 글). */
export function policyBody(policyVersion = "1.0"): Record<string, unknown> {
  return {
    policyVersion,
    items: [
      {
        type: "VOICE_AND_TEXT",
        title: "음성 수집 · 글로 옮겨 저장",
        sections: [
          { heading: "수집하는 것", body: "아이가 기기에서 말한 음성" },
          { heading: "이용 목적", body: "대화를 진행하고, 음성을 글로 옮겨 기록으로 저장" },
          { heading: "보관", body: "글 기록과 음성 원본 모두 계정을 삭제할 때까지" },
          AI_NOTICE,
          { heading: "동의하지 않으면", body: "자유대화 · 연습을 시작할 수 없어요" },
        ],
      },
      {
        type: "GUARDIAN_VIEW",
        title: "보호자 열람",
        sections: [{ heading: "보는 사람", body: "이 계정으로 로그인한 보호자" }, AI_NOTICE, { heading: "동의하지 않으면", body: "자유대화 · 연습을 시작할 수 없어요" }],
      },
      {
        type: "REPORT_SHARING",
        title: "리포트 생성 · 외부 전달",
        sections: [{ heading: "만드는 것", body: "고른 기간의 기록으로 상담용 리포트(PDF)" }, AI_NOTICE, { heading: "동의하지 않으면", body: "서비스를 시작할 수 없어요" }],
      },
      {
        type: "RAW_AUDIO",
        title: "원음 보관",
        sections: [{ heading: "보관하는 것", body: "아이 음성 원본" }, AI_NOTICE, { heading: "동의하지 않으면", body: "서비스를 시작할 수 없어요" }],
      },
    ],
  };
}

/** GET · POST · PATCH /api/child(child-response.schema.json). */
export function childBody(overrides: Partial<{ displayName: string; age: number; interests: string[] }> = {}): Record<string, unknown> {
  return {
    childId: CHILD_ID,
    displayName: overrides.displayName ?? "하늘",
    age: overrides.age ?? 8,
    interests: overrides.interests ?? ["공룡", "그림"],
  };
}

/** GET /api/child/stop-expressions 200(stop-expressions-response.schema.json). */
export function stopBody(texts: readonly string[] = []): Record<string, unknown> {
  const ids = [EXPRESSION_ID_1, EXPRESSION_ID_2, "00000000-0000-4000-8000-000000000063", "00000000-0000-4000-8000-000000000064", "00000000-0000-4000-8000-000000000065"];
  return {
    default: "무중아 그만할래",
    items: texts.map((text, index) => ({ id: ids[index], text, sortOrder: index + 1 })),
  };
}

/** 요청 본문(JSON). */
export function jsonBody(init: RequestInit): unknown {
  return typeof init.body === "string" ? (JSON.parse(init.body) as unknown) : undefined;
}

/** 204 응답. */
export function noContent(): Response {
  return new Response(null, { status: 204 });
}
