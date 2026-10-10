// 받침에 따른 조사 고르기 (U1 3.5 Step 37 · 문구표 '{아이}가 / 민준이' 꼴)
// 마지막 글자가 한글 음절이면 받침 유무로 고르고, 한글이 아니면(영문 · 숫자 · 빈 이름) 두 꼴을 함께 쓴다('이(가)').
// 짝은 '받침 있을 때 꼴/받침 없을 때 꼴' 순서다(예: '과/와' → 민준과 · 서아와).

export type ParticlePair = "이/가" | "은/는" | "을/를" | "과/와" | "으로/로";

const HANGUL_SYLLABLE_FIRST = 0xac00;
const HANGUL_SYLLABLE_LAST = 0xd7a3;
const FINAL_CONSONANT_COUNT = 28;
/** 종성 ㄹ의 순번(으로/로에서 ㄹ 받침은 '로'). */
const FINAL_RIEUL_INDEX = 8;

/** 받침 순번(0이면 받침 없음). 마지막 글자가 한글 음절이 아니면 null. */
export function finalConsonantIndex(word: string): number | null {
  const trimmed = word.trimEnd();
  if (trimmed.length === 0) {
    return null;
  }
  const code = trimmed.codePointAt(trimmed.length - 1);
  if (code === undefined || code < HANGUL_SYLLABLE_FIRST || code > HANGUL_SYLLABLE_LAST) {
    return null;
  }
  return (code - HANGUL_SYLLABLE_FIRST) % FINAL_CONSONANT_COUNT;
}

/** 받침이 있으면 true, 없으면 false, 한글 음절이 아니면 null. */
export function hasFinalConsonant(word: string): boolean | null {
  const index = finalConsonantIndex(word);
  return index === null ? null : index !== 0;
}

/** 단어 뒤에 맞는 조사를 붙인다. 예: ('민준', '이/가') → '민준이', ('서아', '이/가') → '서아가'. */
export function attachParticle(word: string, pair: ParticlePair): string {
  const [withFinal, withoutFinal] = pair.split("/") as [string, string];
  const index = finalConsonantIndex(word);
  if (index === null) {
    return `${word}${withFinal}(${withoutFinal})`;
  }
  if (pair === "으로/로" && index === FINAL_RIEUL_INDEX) {
    return `${word}${withoutFinal}`;
  }
  return `${word}${index !== 0 ? withFinal : withoutFinal}`;
}
