// 세션 승인 문구 임시 WAV 시험 (U4a · U4a-J20 · U4b 설계 8절 1 · J102와 같은 방식)
// 생성기는 임시 디렉터리에만 쓰고 저장소 파일(infra/seed-audio/)은 읽어서 바이트만 비교한다.
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { APPROVED_WAVS, DEFAULT_OUT_DIR, REPO_ROOT } from "../../seed/gen-approved-wav.mjs";
import {
  SESSION_WAVS,
  buildSessionWav,
  findOutdatedSessionWavs,
  generateSessionWavs,
  main,
} from "../../seed/gen-session-wav.mjs";

/** WAV 머리(44바이트 고정 모양)를 읽는다. */
function header(bytes) {
  return {
    riff: bytes.toString("ascii", 0, 4),
    wave: bytes.toString("ascii", 8, 12),
    audioFormat: bytes.readUInt16LE(20),
    channels: bytes.readUInt16LE(22),
    sampleRate: bytes.readUInt32LE(24),
    bitsPerSample: bytes.readUInt16LE(34),
    dataBytes: bytes.readUInt32LE(40),
  };
}

const committed = (fileName) => readFile(path.join(REPO_ROOT, "infra", "seed-audio", fileName));
const silentLog = { log: () => undefined, error: () => undefined };

describe("세션 승인 문구 임시 WAV (U4a-J20)", () => {
  let tempDir;

  beforeEach(async () => {
    tempDir = await mkdtemp(path.join(os.tmpdir(), "mujoong-session-wav-"));
  });

  afterEach(async () => {
    await rm(tempDir, { recursive: true, force: true });
  });

  it("코드 여섯 개가 U4b가 기대하는 참여 확인 · 확인 질문 · 공백 안내 문구이고 U1 두 파일과 겹치지 않는다", () => {
    expect(SESSION_WAVS.map((spec) => spec.contentCode)).toEqual([
      "INTRO_FREE_TALK",
      "INTRO_PRACTICE",
      "RE_PROMPT",
      "DECLINE_ALTERNATIVE",
      "SILENCE_WARNING",
      "STOP_CONFIRM",
    ]);
    const u1Files = APPROVED_WAVS.map((spec) => spec.fileName);
    for (const spec of SESSION_WAVS) {
      expect(u1Files).not.toContain(spec.fileName);
      expect(`/media/approved/${spec.fileName}`).toMatch(/^\/media\/approved\/[A-Za-z0-9._-]+\.wav$/);
    }
  });

  it("생성기를 다시 돌리면 커밋된 여섯 파일과 바이트가 같다", async () => {
    const written = await generateSessionWavs(tempDir);
    expect(written).toHaveLength(SESSION_WAVS.length);
    for (const spec of SESSION_WAVS) {
      const regenerated = await readFile(path.join(tempDir, spec.fileName));
      expect(regenerated.equals(await committed(spec.fileName)), spec.fileName).toBe(true);
    }
    expect(await findOutdatedSessionWavs(DEFAULT_OUT_DIR)).toEqual([]);
  });

  it.each(SESSION_WAVS.map((spec) => [spec.fileName, spec]))(
    "%s는 PCM16 · 24kHz · 모노이고 길이가 정한 초와 같으며 0에서 시작해 0으로 끝난다",
    async (fileName, spec) => {
      const bytes = await committed(fileName);
      expect(header(bytes)).toMatchObject({
        riff: "RIFF",
        wave: "WAVE",
        audioFormat: 1,
        channels: 1,
        sampleRate: 24_000,
        bitsPerSample: 16,
      });
      const { dataBytes } = header(bytes);
      expect(dataBytes / 48_000).toBeCloseTo(spec.seconds, 5);
      expect(bytes.readInt16LE(44)).toBe(0);
      expect(bytes.readInt16LE(bytes.length - 2)).toBe(0);
      expect(buildSessionWav(spec).equals(bytes)).toBe(true);
    },
  );

  it("파일 이름이 시드 003의 audio_path와 같다", async () => {
    const seedSql = await readFile(path.join(REPO_ROOT, "infra", "seed", "003-u4a-reference.sql"), "utf8");
    for (const spec of SESSION_WAVS) {
      expect(seedSql).toContain(`'${spec.contentCode}', 2`);
      expect(seedSql).toContain(`'${spec.fileName}'`);
    }
  });

  it("--check는 없는 파일에 1, 같으면 0, 모르는 인자는 2다", async () => {
    expect(await main(["--check", "--out", tempDir], silentLog)).toBe(1);
    expect(await main(["--out", tempDir], silentLog)).toBe(0);
    expect(await main(["--check", "--out", tempDir], silentLog)).toBe(0);
    expect(await main(["--what"], silentLog)).toBe(2);
  });
});
