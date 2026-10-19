// 승인 음성 임시 WAV 시험 (U1 3.5 Step 13 · J102 · J60 · AC3.3.3 · BR6.10)
// 생성기는 임시 디렉터리에만 쓰고 저장소 파일(infra/seed-audio/)은 읽어서 바이트만 비교한다(검증 명령의 작업 트리 비교 SEC-18).
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  APPROVED_WAVS,
  CLOSING_MAX_SECONDS,
  DEFAULT_OUT_DIR,
  REPO_ROOT,
  buildApprovedWav,
  encodeWav,
  findOutdatedWavs,
  generateApprovedWavs,
  main,
  renderNotes,
} from "../../seed/gen-approved-wav.mjs";

/** 생성기와 독립된 WAV 머리 해석(RIFF · fmt · data 청크를 차례로 읽는다). */
function parseWav(bytes) {
  if (
    bytes.length < 12 ||
    bytes.toString("ascii", 0, 4) !== "RIFF" ||
    bytes.toString("ascii", 8, 12) !== "WAVE"
  ) {
    throw new Error("RIFF/WAVE가 아니다");
  }
  if (bytes.readUInt32LE(4) !== bytes.length - 8) {
    throw new Error("RIFF 길이가 파일 길이와 다르다");
  }
  let offset = 12;
  let format;
  let data;
  while (offset + 8 <= bytes.length) {
    const id = bytes.toString("ascii", offset, offset + 4);
    const size = bytes.readUInt32LE(offset + 4);
    const body = offset + 8;
    if (body + size > bytes.length) throw new Error(`청크 ${id} 길이가 파일을 넘는다`);
    if (id === "fmt ") {
      format = {
        audioFormat: bytes.readUInt16LE(body),
        channels: bytes.readUInt16LE(body + 2),
        sampleRate: bytes.readUInt32LE(body + 4),
        byteRate: bytes.readUInt32LE(body + 8),
        blockAlign: bytes.readUInt16LE(body + 12),
        bitsPerSample: bytes.readUInt16LE(body + 14),
      };
    } else if (id === "data") {
      data = bytes.subarray(body, body + size);
    }
    offset = body + size + (size % 2);
  }
  if (!format || !data) throw new Error("fmt 또는 data 청크가 없다");
  return { ...format, data };
}

function samplesOf(data) {
  const samples = [];
  for (let i = 0; i + 1 < data.length; i += 2) samples.push(data.readInt16LE(i));
  return samples;
}

const committedPath = (fileName) => path.join(REPO_ROOT, "infra", "seed-audio", fileName);

describe("승인 음성 임시 WAV (J102)", () => {
  let tempDir;

  beforeEach(async () => {
    tempDir = await mkdtemp(path.join(os.tmpdir(), "mujoong-wav-"));
  });

  afterEach(async () => {
    await rm(tempDir, { recursive: true, force: true });
  });

  it("생성기를 다시 돌리면 커밋된 두 파일과 바이트가 같다(재생성 바이트 동일)", async () => {
    const written = await generateApprovedWavs(tempDir);
    expect(written.map((file) => path.basename(file))).toEqual([
      "closing-v1.wav",
      "waiting-filler-v1.wav",
    ]);
    for (const spec of APPROVED_WAVS) {
      const regenerated = await readFile(path.join(tempDir, spec.fileName));
      const committed = await readFile(committedPath(spec.fileName));
      expect(regenerated.equals(committed), spec.fileName).toBe(true);
    }
    expect(await findOutdatedWavs(DEFAULT_OUT_DIR)).toEqual([]);
  });

  it.each(APPROVED_WAVS.map((spec) => [spec.fileName]))(
    "%s는 RIFF · PCM16 · 24kHz · 모노이고 data 길이가 표본 단위로 맞다",
    async (fileName) => {
      const wav = parseWav(await readFile(committedPath(fileName)));
      expect(wav).toMatchObject({
        audioFormat: 1,
        channels: 1,
        sampleRate: 24_000,
        bitsPerSample: 16,
        blockAlign: 2,
        byteRate: 48_000,
      });
      expect(wav.data.length % 2).toBe(0);
      expect(wav.data.length).toBeGreaterThan(0);
    },
  );

  it("CLOSING은 3초 이내이고 WAITING_FILLER도 비어 있지 않다(BR6.10 · AC3.3.3)", async () => {
    const closing = parseWav(await readFile(committedPath("closing-v1.wav")));
    const closingSeconds = closing.data.length / closing.byteRate;
    expect(closingSeconds).toBeGreaterThan(0);
    expect(closingSeconds).toBeLessThanOrEqual(CLOSING_MAX_SECONDS);

    const filler = parseWav(await readFile(committedPath("waiting-filler-v1.wav")));
    expect(filler.data.length / filler.byteRate).toBeGreaterThan(0);
  });

  it.each(APPROVED_WAVS.map((spec) => [spec.fileName]))(
    "%s는 무음만이 아니고 잘림 없이 0에서 시작해 0으로 끝난다",
    async (fileName) => {
      const samples = samplesOf(parseWav(await readFile(committedPath(fileName))).data);
      const peak = Math.max(...samples.map((value) => Math.abs(value)));
      expect(peak).toBeGreaterThan(1_000);
      expect(peak).toBeLessThan(32_767);
      expect(samples[0]).toBe(0);
      expect(samples.at(-1)).toBe(0);
    },
  );

  it("파일 이름이 참조 시드의 audio_path와 계약의 승인 음성 URL 형식에 맞다", async () => {
    const seedSql = await readFile(
      path.join(REPO_ROOT, "infra", "seed", "001-reference.sql"),
      "utf8",
    );
    for (const spec of APPROVED_WAVS) {
      expect(seedSql).toContain(`'${spec.fileName}'`);
      expect(`/media/approved/${spec.fileName}`).toMatch(
        /^\/media\/approved\/[A-Za-z0-9._-]+\.wav$/,
      );
    }
    expect(APPROVED_WAVS.map((spec) => spec.contentCode)).toEqual(["CLOSING", "WAITING_FILLER"]);
  });

  it("같은 입력이면 매번 같은 바이트를 만든다", () => {
    for (const spec of APPROVED_WAVS) {
      expect(buildApprovedWav(spec).equals(buildApprovedWav(spec))).toBe(true);
    }
  });

  it("--check는 다른 바이트 · 없는 파일을 찾아 종료 코드 1, 같으면 0, 모르는 인자는 2다", async () => {
    const logged = [];
    const log = { log: (line) => logged.push(line), error: (line) => logged.push(line) };
    await generateApprovedWavs(tempDir);
    expect(await main(["--check", "--out", tempDir], log)).toBe(0);

    const tampered = path.join(tempDir, "closing-v1.wav");
    const bytes = await readFile(tampered);
    bytes[bytes.length - 1] ^= 0x01;
    await writeFile(tampered, bytes);
    await rm(path.join(tempDir, "waiting-filler-v1.wav"));
    expect(await findOutdatedWavs(tempDir)).toEqual(["closing-v1.wav", "waiting-filler-v1.wav"]);
    expect(await main(["--check", "--out", tempDir], log)).toBe(1);

    expect(await main(["--bogus"], log)).toBe(2);
    expect(await main(["--out"], log)).toBe(2);
    expect(logged.join("\n")).toContain("모르는 인자");
  });

  it("잘못된 입력은 거부한다(빈 음표 · 길이 초과 · 음수 주파수 · Int16Array 아님)", () => {
    expect(() => renderNotes([], 1)).toThrow(/비어 있다/);
    expect(() => renderNotes([{ freq: 440, seconds: 2 }], 1)).toThrow(/넘는다/);
    expect(() => renderNotes([{ freq: -1, seconds: 0.1 }], 1)).toThrow(/형식/);
    expect(() => encodeWav([0, 1, 2])).toThrow(TypeError);
  });

  it("경계: 표본 하나짜리 WAV는 머리 44바이트 + 2바이트이고, RIFF가 아닌 바이트는 해석기가 거부한다", () => {
    const single = encodeWav(Int16Array.of(-32_768));
    expect(single.length).toBe(46);
    expect(samplesOf(parseWav(single).data)).toEqual([-32_768]);
    expect(() => parseWav(Buffer.from("not a wav file at all"))).toThrow(/RIFF/);
  });
});
