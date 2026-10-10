#!/usr/bin/env node
// 무중 승인 음성 임시 WAV 생성기 (U1 3.5 Step 11 · J102 · J60 · AC3.3.3 · contracts/ai-io.md 5절)
//
// 실제 녹음 · 승인 방법은 D 담당이 정한다(J102). 그 전까지 저장소에서 다시 만들 수 있는 합성음(톤 + 무음) 두 개를 둔다.
//   - closing-v1.wav        : 끝내기 마무리 인사 자리(BR6.10, 3초 이내)
//   - waiting-filler-v1.wav : 게이트 폐기 뒤 대기 · 대체 안내 자리(BR7.4)
// 형식: RIFF/WAVE · PCM16 리틀엔디언 · 24kHz · 모노(J27과 같은 오디오 형식). 개인정보 없음.
// 같은 입력이면 같은 바이트가 나온다(난수 · 시각 · 환경 값을 쓰지 않는다). 생성 결과도 커밋한다(J102).
//
// 쓰는 법
//   node tools/seed/gen-approved-wav.mjs                 # infra/seed-audio/에 두 파일을 쓴다
//   node tools/seed/gen-approved-wav.mjs --out <디렉터리> # 다른 곳에 쓴다(시험은 임시 디렉터리)
//   node tools/seed/gen-approved-wav.mjs --check         # 쓰지 않고 infra/seed-audio/의 파일과 바이트를 비교한다(다르면 종료 코드 1)
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const SAMPLE_RATE = 24_000;
export const CHANNELS = 1;
export const BITS_PER_SAMPLE = 16;
/** CLOSING 재생 상한(BR6.10 · J60 '3초 이내'). */
export const CLOSING_MAX_SECONDS = 3;

const HEADER_BYTES = 44;
const PEAK = 32_767;
/** 톤 진폭: −12 dBFS 근처(어린이 기기 스피커에서 놀라지 않을 크기). */
const TONE_AMPLITUDE = 0.25;
/** 클릭 잡음을 막는 앞뒤 경사 길이(초). */
const RAMP_SECONDS = 0.02;

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
/** 저장소 루트(tools/seed/의 두 단계 위). */
export const REPO_ROOT = path.resolve(toolsDir, "..", "..");
/** 기본 출력 위치(Nginx /media/approved/의 원본, J136). */
export const DEFAULT_OUT_DIR = path.join(REPO_ROOT, "infra", "seed-audio");

/**
 * 음표 목록을 PCM16 표본으로 그린다. 음표는 {freq(Hz, 0이면 무음), seconds}이고 순서대로 이어 붙인다.
 * 끝에 무음을 더해 전체 길이를 totalSeconds로 맞춘다.
 */
export function renderNotes(notes, totalSeconds) {
  if (!Array.isArray(notes) || notes.length === 0) {
    throw new Error("음표 목록이 비어 있다");
  }
  const totalSamples = Math.round(totalSeconds * SAMPLE_RATE);
  const samples = new Int16Array(totalSamples);
  let offset = 0;
  for (const note of notes) {
    if (!(note.seconds > 0) || !(note.freq >= 0)) {
      throw new Error(`음표 형식이 틀렸다: ${JSON.stringify(note)}`);
    }
    const count = Math.round(note.seconds * SAMPLE_RATE);
    if (offset + count > totalSamples) {
      throw new Error("음표 길이 합이 전체 길이를 넘는다");
    }
    if (note.freq > 0) {
      const ramp = Math.min(Math.round(RAMP_SECONDS * SAMPLE_RATE), Math.floor(count / 2));
      for (let i = 0; i < count; i += 1) {
        let gain = 1;
        if (i < ramp) gain = i / ramp;
        else if (i >= count - ramp) gain = (count - 1 - i) / ramp;
        const value = Math.sin((2 * Math.PI * note.freq * i) / SAMPLE_RATE) * TONE_AMPLITUDE * gain;
        samples[offset + i] = Math.round(value * PEAK);
      }
    }
    offset += count;
  }
  return samples;
}

/** PCM16 표본을 44바이트 머리 + data 청크의 WAV 바이트로 만든다(리틀엔디언). */
export function encodeWav(samples) {
  if (!(samples instanceof Int16Array)) {
    throw new TypeError("표본은 Int16Array여야 한다");
  }
  const dataBytes = samples.length * 2;
  const buffer = Buffer.alloc(HEADER_BYTES + dataBytes);
  const blockAlign = (CHANNELS * BITS_PER_SAMPLE) / 8;
  buffer.write("RIFF", 0, "ascii");
  buffer.writeUInt32LE(36 + dataBytes, 4);
  buffer.write("WAVE", 8, "ascii");
  buffer.write("fmt ", 12, "ascii");
  buffer.writeUInt32LE(16, 16); // fmt 청크 길이
  buffer.writeUInt16LE(1, 20); // PCM
  buffer.writeUInt16LE(CHANNELS, 22);
  buffer.writeUInt32LE(SAMPLE_RATE, 24);
  buffer.writeUInt32LE(SAMPLE_RATE * blockAlign, 28); // 초당 바이트
  buffer.writeUInt16LE(blockAlign, 32);
  buffer.writeUInt16LE(BITS_PER_SAMPLE, 34);
  buffer.write("data", 36, "ascii");
  buffer.writeUInt32LE(dataBytes, 40);
  for (let i = 0; i < samples.length; i += 1) {
    buffer.writeInt16LE(samples[i], HEADER_BYTES + i * 2);
  }
  return buffer;
}

/** 승인 음성 임시 파일 목록. 파일 이름은 core_approved_content_version.audio_path · infra/seed/001-reference.sql과 같다. */
export const APPROVED_WAVS = Object.freeze([
  Object.freeze({
    contentCode: "CLOSING",
    fileName: "closing-v1.wav",
    // 올라가는 세 음(도 · 미 · 솔) 뒤 짧은 무음, 전체 2초(3초 이내)
    seconds: 2,
    notes: Object.freeze([
      { freq: 523.25, seconds: 0.35 },
      { freq: 0, seconds: 0.05 },
      { freq: 659.25, seconds: 0.35 },
      { freq: 0, seconds: 0.05 },
      { freq: 783.99, seconds: 0.6 },
    ]),
  }),
  Object.freeze({
    contentCode: "WAITING_FILLER",
    fileName: "waiting-filler-v1.wav",
    // 낮은 음 두 번(생각하는 소리 자리) 뒤 무음, 전체 1.5초
    seconds: 1.5,
    notes: Object.freeze([
      { freq: 392, seconds: 0.25 },
      { freq: 0, seconds: 0.15 },
      { freq: 349.23, seconds: 0.4 },
    ]),
  }),
]);

/** 목록의 한 항목을 WAV 바이트로 만든다. */
export function buildApprovedWav(spec) {
  return encodeWav(renderNotes(spec.notes, spec.seconds));
}

/** 두 파일을 outDir에 쓴다(임시 파일 → 이름 바꾸기). 쓴 경로 목록을 돌려준다. */
export async function generateApprovedWavs(outDir = DEFAULT_OUT_DIR) {
  await mkdir(outDir, { recursive: true });
  const written = [];
  for (const spec of APPROVED_WAVS) {
    const target = path.join(outDir, spec.fileName);
    const temporary = `${target}.tmp-${process.pid}`;
    await writeFile(temporary, buildApprovedWav(spec));
    await rename(temporary, target);
    written.push(target);
  }
  return written;
}

/** dir의 두 파일이 생성기 결과와 바이트가 같은지 본다. 다르거나 없는 파일 이름 목록을 돌려준다. */
export async function findOutdatedWavs(dir = DEFAULT_OUT_DIR) {
  const outdated = [];
  for (const spec of APPROVED_WAVS) {
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
    if (!existing.equals(buildApprovedWav(spec))) {
      outdated.push(spec.fileName);
    }
  }
  return outdated;
}

function parseArgs(argv) {
  const options = { outDir: DEFAULT_OUT_DIR, check: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--check") {
      options.check = true;
    } else if (arg === "--out") {
      const value = argv[i + 1];
      if (!value || value.startsWith("--")) {
        throw new Error("--out 뒤에 디렉터리가 필요하다");
      }
      options.outDir = path.resolve(value);
      i += 1;
    } else {
      throw new Error(`모르는 인자: ${arg}`);
    }
  }
  return options;
}

/** 명령줄 진입점. 종료 코드: 0 성공, 1 --check 불일치, 2 인자 오류. */
export async function main(argv = process.argv.slice(2), log = console) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    log.error(`gen-approved-wav: ${error.message}`);
    return 2;
  }
  if (options.check) {
    const outdated = await findOutdatedWavs(options.outDir);
    if (outdated.length > 0) {
      log.error(`gen-approved-wav: 생성기 결과와 다르다: ${outdated.join(", ")}`);
      return 1;
    }
    log.log("gen-approved-wav: 두 파일이 생성기 결과와 같다");
    return 0;
  }
  const written = await generateApprovedWavs(options.outDir);
  log.log(`gen-approved-wav: ${written.length}개를 썼다 → ${options.outDir}`);
  return 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main();
}
