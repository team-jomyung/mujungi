#!/usr/bin/env node
// 무중 세션 승인 문구 임시 WAV 생성기 (U4a · U4a-J20 · U4b 설계 8절 1 · AC6.3.1 · AC6.3.7 · J102와 같은 방식)
//
// U1 gen-approved-wav.mjs(CLOSING · WAITING_FILLER 두 파일, U1 시험이 그 두 파일만 단언)와 같은 합성음 규칙으로, 참여 확인 · 확인 질문 ·
// 공백 안내에 쓰는 세션 승인 문구 6종의 음성 자리를 만든다. 실제 녹음 · 사람 승인은 D 담당이 정한다(J102). 그 전까지 저장소에서 다시 만들
// 수 있는 톤 + 무음이다. 파일 이름은 infra/seed/003-u4a-reference.sql의 audio_path와 같다.
// 형식 · 결정성은 U1 생성기와 같다(PCM16 · 24kHz · 모노, 같은 입력이면 같은 바이트). 생성 결과도 커밋한다.
//
// 쓰는 법
//   node tools/seed/gen-session-wav.mjs                 # infra/seed-audio/에 여섯 파일을 쓴다
//   node tools/seed/gen-session-wav.mjs --out <디렉터리> # 다른 곳에 쓴다(시험은 임시 디렉터리)
//   node tools/seed/gen-session-wav.mjs --check         # 쓰지 않고 infra/seed-audio/의 파일과 바이트를 비교한다(다르면 종료 코드 1)
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DEFAULT_OUT_DIR, encodeWav, renderNotes } from "./gen-approved-wav.mjs";

/** 음표 하나. */
const note = (freq, seconds) => Object.freeze({ freq, seconds });

/** 세션 승인 문구 음성 자리 목록(코드 · 파일 이름 · 길이 · 음표). */
export const SESSION_WAVS = Object.freeze([
  Object.freeze({
    contentCode: "INTRO_FREE_TALK",
    fileName: "intro-free-talk-v2.wav",
    // 인사 · 안내 · 질문 자리: 오르는 네 음 뒤 끝을 올리는 두 음(묻는 소리), 전체 4초
    seconds: 4,
    notes: Object.freeze([note(523.25, 0.3), note(0, 0.1), note(587.33, 0.3), note(0, 0.1), note(659.25, 0.3),
      note(0, 0.1), note(698.46, 0.3), note(0, 0.4), note(659.25, 0.3), note(0, 0.05), note(880, 0.45)]),
  }),
  Object.freeze({
    contentCode: "INTRO_PRACTICE",
    fileName: "intro-practice-v2.wav",
    // 연습 시작 안내 자리: 자유대화와 다른 음 높이(구분), 전체 4초
    seconds: 4,
    notes: Object.freeze([note(392, 0.3), note(0, 0.1), note(440, 0.3), note(0, 0.1), note(493.88, 0.3),
      note(0, 0.1), note(523.25, 0.3), note(0, 0.4), note(493.88, 0.3), note(0, 0.05), note(659.25, 0.45)]),
  }),
  Object.freeze({
    contentCode: "RE_PROMPT",
    fileName: "re-prompt-v2.wav",
    // 다시 부르는 두 음, 전체 2초
    seconds: 2,
    notes: Object.freeze([note(587.33, 0.3), note(0, 0.1), note(783.99, 0.45)]),
  }),
  Object.freeze({
    contentCode: "DECLINE_ALTERNATIVE",
    fileName: "decline-alternative-v2.wav",
    // 부드럽게 내려갔다 묻는 세 음, 전체 2.5초
    seconds: 2.5,
    notes: Object.freeze([note(659.25, 0.3), note(0, 0.1), note(523.25, 0.3), note(0, 0.1), note(698.46, 0.45)]),
  }),
  Object.freeze({
    contentCode: "SILENCE_WARNING",
    fileName: "silence-warning-v2.wav",
    // 기다리는 낮은 두 음, 전체 2초
    seconds: 2,
    notes: Object.freeze([note(440, 0.35), note(0, 0.15), note(440, 0.35)]),
  }),
  Object.freeze({
    contentCode: "STOP_CONFIRM",
    fileName: "stop-confirm-v2.wav",
    // 끝을 올리는 짧은 질문 두 음, 전체 1.5초
    seconds: 1.5,
    notes: Object.freeze([note(523.25, 0.3), note(0, 0.05), note(698.46, 0.4)]),
  }),
]);

/** 목록의 한 항목을 WAV 바이트로 만든다. */
export function buildSessionWav(spec) {
  return encodeWav(renderNotes(spec.notes, spec.seconds));
}

/** 여섯 파일을 outDir에 쓴다(임시 파일 → 이름 바꾸기). 쓴 경로 목록을 돌려준다. */
export async function generateSessionWavs(outDir = DEFAULT_OUT_DIR) {
  await mkdir(outDir, { recursive: true });
  const written = [];
  for (const spec of SESSION_WAVS) {
    const target = path.join(outDir, spec.fileName);
    const temporary = `${target}.tmp-${process.pid}`;
    await writeFile(temporary, buildSessionWav(spec));
    await rename(temporary, target);
    written.push(target);
  }
  return written;
}

/** dir의 여섯 파일이 생성기 결과와 바이트가 같은지 본다. 다르거나 없는 파일 이름 목록을 돌려준다. */
export async function findOutdatedSessionWavs(dir = DEFAULT_OUT_DIR) {
  const outdated = [];
  for (const spec of SESSION_WAVS) {
    let existing;
    try {
      existing = await readFile(path.join(dir, spec.fileName));
    } catch (error) {
      if (error.code === "ENOENT") {
        outdated.push(spec.fileName);
        continue;
      }
      throw error;
    }
    if (!existing.equals(buildSessionWav(spec))) {
      outdated.push(spec.fileName);
    }
  }
  return outdated;
}

/** 명령줄 진입점. 종료 코드: 0 성공, 1 --check 불일치, 2 인자 오류. */
export async function main(argv = process.argv.slice(2), log = console) {
  let outDir = DEFAULT_OUT_DIR;
  let check = false;
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === "--check") {
      check = true;
    } else if (argv[i] === "--out" && argv[i + 1] && !argv[i + 1].startsWith("--")) {
      outDir = path.resolve(argv[i + 1]);
      i += 1;
    } else {
      log.error(`gen-session-wav: 모르는 인자: ${argv[i]}`);
      return 2;
    }
  }
  if (check) {
    const outdated = await findOutdatedSessionWavs(outDir);
    if (outdated.length > 0) {
      log.error(`gen-session-wav: 생성기 결과와 다르다: ${outdated.join(", ")}`);
      return 1;
    }
    return 0;
  }
  const written = await generateSessionWavs(outDir);
  log.log(`gen-session-wav: ${written.length}개를 썼다`);
  return 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main();
}
