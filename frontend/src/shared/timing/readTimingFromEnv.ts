import { TIMING_KEYS, resolveTiming, type Timing } from "./timing";

// 서버 컴포넌트 전용: 기동 환경 변수에서 web 시간 설정을 읽는다 (U1 3.5 Step 37 · NFR6.1 · LC-2 · J94).
// 루트 레이아웃이 요청마다(connection() 뒤) 부르고 결과를 TimingProvider로 첫 응답에 싣는다. 같은 이미지가 환경 변수만
// 바꿔 다른 값으로 돈다. 브라우저 번들에는 이 함수가 들어가지 않는다(클라이언트 코드는 TimingProvider 값만 읽는다).

/** 계약 키의 환경 변수만 골라 Timing을 만든다. 시험은 env 대신 임의 객체를 넘긴다. */
export function readTimingFromEnv(env: Readonly<Record<string, string | undefined>> = process.env): Timing {
  const picked: Record<string, string | undefined> = {};
  for (const spec of TIMING_KEYS) {
    if (spec.envVar !== null) {
      picked[spec.envVar] = env[spec.envVar];
    }
  }
  return resolveTiming(picked);
}
