import type { StatusDisplay } from "../status/StatusView";

// 3D 무중이 GLB의 클립 이름(Explorer_A_Web 패키지, 21개 중 화면에 쓰는 것)과 T19 상태 → 동작 대응.
// V6_Neutral은 0.08초짜리 고정 자세라 모션 줄이기 설정에서만 쓴다. 나머지 상태는 숨쉬기 · 둘러보기처럼 늘 움직이는 클립을 쓴다.
export const DUCK_CLIPS = {
  neutral: "V6_Neutral",
  idle: "V6_Breathe_WeightShift",
  look: "V6_HeadLook_Blink_Test",
  wave: "Expressive_Wave",
  talk: "Expressive_Talk",
  steps: "V6_SmallSteps_Prototype",
  laugh: "Happy_Laugh",
  sad: "Mild_Sad",
} as const;

export type DuckMotion = keyof typeof DUCK_CLIPS;

/** 켜져 대화 중인 상태. 이 상태에서만 발화 유지(talking)가 동작을 정한다(꺼짐 · 마이크 · 연결 화면은 상태 동작 그대로). */
const TALK_HOLD_DISPLAYS: readonly StatusDisplay[] = ["speaking", "listening", "thinking", "standby", "closing"];

/**
 * 상태는 표정이 1차라 말하는 중 · 듣는 중이 동작으로도 구분되게 고른다.
 * talking(무중이 발화 중, 묶음 사이 틈 포함)이면 발화가 끝날 때까지 말하기 동작을 이어 간다. 응답 묶음 사이에 소리가 잠깐 멎어
 * 상태가 말하는 중 ↔ 듣는 중으로 오가도 동작이 talk ↔ look으로 바뀌며 끊겨 보이지 않게 한다.
 */
export function motionFor(display: StatusDisplay, talking = false): DuckMotion {
  if (talking && TALK_HOLD_DISPLAYS.includes(display)) return "talk";
  switch (display) {
    case "speaking":
      return "talk";
    case "standby":
      return "wave";
    case "listening":
    case "thinking":
      return "look";
    case "connecting":
    case "reconnecting":
      return "steps";
    case "closing":
      return "laugh";
    case "mic-denied":
      return "sad";
    default:
      return "idle";
  }
}
