// 검증 명령 종료 코드 · 보고 시험 (U1 3.5 Step 49 · SEC-05 · J144 · J133 · J134 · SEC-18)
// 임시 디렉터리(git 작업 트리)에 scripts/verify.sh · scripts/verify/lib.sh · infra/ci/source-fingerprint.sh 사본과 가짜 단계
// 스크립트를 두고 돌린다. verify.sh는 자기 위치에서 저장소 루트를 잡으므로 바꿔치기 훅이 필요 없다. 바깥 도구는 가짜로 바꾼다:
// docker(데몬 없음), java(판 21), PostgreSQL 16 바이너리(MUJOONG_PG_BIN). 확인하는 것:
//   단계 실패 → 1, 기한 초과 → 1(잠자는 단계 프로세스도 멈춤), 전제 누락 → 2(단계가 2로 끝나거나 compose 전제가 없을 때),
//   --engine인데 미리 만든 산출물이 없음 → 1, 실행 중 작업 트리가 바뀜 → 1, 모두 통과 → 0.
//   실패한 단계의 PASS 줄이 report.txt에 없고, 첫 줄은 mode=<모드>, host 모드는 NOT_VERIFIED(host) 줄을 남긴다.
import { spawnSync } from "node:child_process";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");

const FAKE_STAGES = [
  "a-checks.sh",
  "a-secrets.sh",
  "b-stack.sh",
  "b-host.sh",
  "c-skeleton-turn.sh",
  "assert-schema-isolation.sh",
  "assert-log-hygiene.sh",
];

// 가짜 단계: FAKE_BEHAVIOR="<키>=<동작>,..." 로 동작을 고른다. 키는 스크립트 이름(a-checks는 '<이름>:<부분>'도 받는다).
// 동작: fail(1) · prereq(2) · sleep(pid를 FAKE_PID_FILE에 쓰고 60초 잔다) · touch(추적 파일을 고친다). 없으면 통과(0).
// a-checks backend · frontend는 host 모드 지문 기록(F)이 보는 산출물을 만든다.
const fakeStage = (name) => `#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "\${BASH_SOURCE[0]}")/../.."
key="${name}"
part="\${1:-}"
behavior=""
for item in \${FAKE_BEHAVIOR//,/ }; do
  case "$item" in
    "$key="*) behavior="\${item#*=}" ;;
    "$key:$part="*) behavior="\${item#*=}" ;;
  esac
done
echo "fake $key $part behavior=\${behavior:-pass}"
case "$behavior" in
  fail) echo "FAIL fake.$key: 일부러 실패" >&2; exit 1 ;;
  prereq) echo "PREREQ 가짜 전제 누락" >&2; exit 2 ;;
  sleep) echo "$$" >"$FAKE_PID_FILE"; exec sleep 60 ;;
  touch) echo "changed" >>README.md ;;
esac
if [ "$key" = a-checks.sh ] && [ "$part" = backend ]; then
  mkdir -p backend/core-api/build/libs backend/realtime-activity/build/libs
  echo core >backend/core-api/build/libs/core-api.jar
  echo realtime >backend/realtime-activity/build/libs/realtime-activity.jar
fi
if [ "$key" = a-checks.sh ] && [ "$part" = frontend ]; then
  mkdir -p frontend/.next/standalone
  echo server >frontend/.next/standalone/server.js
  echo build-id >frontend/.next/BUILD_ID
fi
exit 0
`;

function writeExecutable(file, content) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, content);
  chmodSync(file, 0o755);
}

function git(cwd, ...args) {
  const result = spawnSync(
    "git",
    [
      "-c",
      "user.name=verify-test",
      "-c",
      "user.email=verify-test@mujoong.invalid",
      "-c",
      "commit.gpgsign=false",
      ...args,
    ],
    { cwd, encoding: "utf8" },
  );
  if (result.status !== 0) {
    throw new Error(`git ${args.join(" ")} 실패: ${result.stderr}`);
  }
}

let sandbox;

beforeEach(() => {
  sandbox = mkdtempSync(path.join(os.tmpdir(), "mujoong-verify-exit-"));
  for (const rel of [
    "scripts/verify.sh",
    "scripts/verify/lib.sh",
    "infra/ci/source-fingerprint.sh",
  ]) {
    mkdirSync(path.dirname(path.join(sandbox, rel)), { recursive: true });
    copyFileSync(path.join(repoRoot, rel), path.join(sandbox, rel));
  }
  for (const name of FAKE_STAGES) {
    writeExecutable(path.join(sandbox, "scripts", "verify", name), fakeStage(name));
  }
  writeFileSync(
    path.join(sandbox, ".gitignore"),
    "build/\n.next/\nnode_modules/\nfake-bin/\nfake-pg/\n",
  );
  writeFileSync(path.join(sandbox, "README.md"), "verify exit sandbox\n");
  // 바깥 도구 대역(무시되는 디렉터리라 작업 트리 비교 · 소스 지문에 들어가지 않는다)
  writeExecutable(path.join(sandbox, "fake-bin", "docker"), "#!/usr/bin/env bash\nexit 1\n");
  writeExecutable(
    path.join(sandbox, "fake-bin", "java"),
    "#!/usr/bin/env bash\necho 'openjdk version \"21.0.9\" 2025-10-21' >&2\nexit 0\n",
  );
  for (const bin of ["initdb", "pg_ctl", "postgres", "psql"]) {
    writeExecutable(path.join(sandbox, "fake-pg", bin), "#!/usr/bin/env bash\nexit 0\n");
  }
  git(sandbox, "init", "-q");
  git(sandbox, "add", "-A");
  git(sandbox, "commit", "-q", "-m", "sandbox");
});

afterEach(() => {
  rmSync(sandbox, { recursive: true, force: true });
});

/** 사본 verify.sh를 다른 작업 디렉터리에서 돌린다(루트를 자기 위치에서 잡는지도 확인). */
function runVerify(args, { behavior = "", extraEnv = {} } = {}) {
  const env = {
    PATH: [
      path.join(sandbox, "fake-bin"),
      path.dirname(process.execPath),
      process.env.PATH ?? "/usr/bin:/bin",
    ].join(path.delimiter),
    HOME: process.env.HOME ?? os.homedir(),
    LANG: "C.UTF-8",
    MUJOONG_PG_BIN: path.join(sandbox, "fake-pg"),
    FAKE_BEHAVIOR: behavior,
    FAKE_PID_FILE: path.join(sandbox, "build", "sleeper.pid"),
    ...extraEnv,
  };
  const started = Date.now();
  const result = spawnSync("bash", [path.join(sandbox, "scripts", "verify.sh"), ...args], {
    cwd: os.tmpdir(),
    env,
    encoding: "utf8",
    timeout: 60_000,
  });
  const reportFile = path.join(sandbox, "build", "verify", "report.txt");
  return {
    status: result.status,
    stdout: result.stdout,
    stderr: result.stderr,
    report: existsSync(reportFile) ? readFileSync(reportFile, "utf8") : "",
    seconds: (Date.now() - started) / 1000,
  };
}

const hasLine = (text, re) => text.split("\n").some((line) => re.test(line));

describe("scripts/verify.sh 종료 코드와 report.txt", () => {
  it("모든 단계가 통과하면 0이고 단계마다 PASS 줄 · mode=host · NOT_VERIFIED(host)를 남긴다", () => {
    const run = runVerify(["--mode", "host"]);
    expect(run.status, run.stdout + run.stderr).toBe(0);
    expect(run.report.split("\n")[0]).toBe("mode=host");
    for (const stage of ["A1", "A2", "A3", "A4", "F", "B", "C", "P1", "P2", "P3", "T"]) {
      expect(hasLine(run.report, new RegExp(`^${stage} PASS \\d+s `)), stage).toBe(true);
    }
    expect(hasLine(run.report, /^NOT_VERIFIED\(host\) /)).toBe(true);
    expect(hasLine(run.report, /^RESULT PASS rc=0 /)).toBe(true);
    expect(existsSync(path.join(sandbox, "build", "verify", "fingerprint"))).toBe(true);
    // 표준 출력은 단계마다 한 줄(머리 줄 1 + 단계 11 + 결과 1)
    expect(run.stdout.trim().split("\n")).toHaveLength(13);
  });

  it("단계가 실패하면 1이고 그 단계의 PASS 줄이 없으며 다음 단계를 돌지 않는다", () => {
    const run = runVerify(["--mode", "host"], { behavior: "b-host.sh=fail" });
    expect(run.status).toBe(1);
    expect(hasLine(run.report, /^B PASS/)).toBe(false);
    expect(hasLine(run.report, /^B FAIL rc=1 /)).toBe(true);
    expect(hasLine(run.report, /^C /)).toBe(false);
    expect(hasLine(run.report, /^RESULT FAIL rc=1 /)).toBe(true);
    expect(run.stdout).toContain("FAIL fake.b-host.sh");
  });

  it("기한을 넘기면 1이고 TIMEOUT 줄만 남으며 잠든 단계 프로세스를 멈춘다", () => {
    const run = runVerify(["--mode", "host", "--deadline", "8"], {
      behavior: "c-skeleton-turn.sh=sleep",
    });
    expect(run.status).toBe(1);
    expect(run.seconds).toBeLessThan(30);
    expect(hasLine(run.report, /^C PASS/)).toBe(false);
    expect(hasLine(run.report, /^C TIMEOUT /)).toBe(true);
    expect(hasLine(run.report, /^RESULT FAIL rc=1 /)).toBe(true);
    const pid = Number(readFileSync(path.join(sandbox, "build", "sleeper.pid"), "utf8").trim());
    expect(Number.isInteger(pid) && pid > 0).toBe(true);
    expect(() => process.kill(pid, 0)).toThrow();
  });

  it("단계가 전제 누락(2)으로 끝나면 2이고 그 단계의 PASS 줄이 없다", () => {
    const run = runVerify(["--mode", "host"], { behavior: "a-checks.sh:frontend=prereq" });
    expect(run.status).toBe(2);
    expect(hasLine(run.report, /^A1 PASS/)).toBe(true);
    expect(hasLine(run.report, /^A2 PASS/)).toBe(false);
    expect(hasLine(run.report, /^A2 PREREQ /)).toBe(true);
    expect(hasLine(run.report, /^RESULT PREREQ rc=2 /)).toBe(true);
  });

  it("compose 모드의 전제(Docker)가 없으면 2이고 어떤 단계도 PASS로 보고하지 않는다", () => {
    const run = runVerify(["--mode", "compose"]);
    expect(run.status).toBe(2);
    expect(run.report.split("\n")[0]).toBe("mode=compose");
    expect(hasLine(run.report, /^PREREQ .*Docker/)).toBe(true);
    expect(hasLine(run.report, /^[A-Z][0-9]* PASS/)).toBe(false);
  });

  it("--engine인데 미리 만든 산출물(지문 파일)이 없으면 1이고 B를 돌지 않는다", () => {
    mkdirSync(path.join(sandbox, "tools", "node_modules", "ws"), { recursive: true });
    writeFileSync(path.join(sandbox, "tools", "node_modules", "ws", "package.json"), "{}\n");
    const run = runVerify(["--engine", "--mode", "host"]);
    expect(run.status).toBe(1);
    expect(hasLine(run.report, /^engine=1$/)).toBe(true);
    expect(hasLine(run.report, /^E0 FAIL /)).toBe(true);
    expect(hasLine(run.report, /^B /)).toBe(false);
  });

  it("전체 실행이 남긴 지문이 지금 소스와 같으면 --engine이 A 없이 B · C · 사후 단언을 돌아 0이다", () => {
    expect(runVerify(["--mode", "host"]).status).toBe(0);
    mkdirSync(path.join(sandbox, "tools", "node_modules", "ws"), { recursive: true });
    writeFileSync(path.join(sandbox, "tools", "node_modules", "ws", "package.json"), "{}\n");
    const run = runVerify(["--engine", "--mode", "host"]);
    expect(run.status, run.stdout + run.stderr).toBe(0);
    expect(hasLine(run.report, /^E0 PASS /)).toBe(true);
    expect(hasLine(run.report, /^A1 /)).toBe(false);
    expect(hasLine(run.report, /^B PASS /)).toBe(true);
    // 소스가 바뀌면 같은 지문 파일로는 통과하지 못한다
    writeFileSync(path.join(sandbox, "scripts", "verify", "extra.txt"), "new source\n");
    git(sandbox, "add", "-A");
    git(sandbox, "commit", "-q", "-m", "change");
    const stale = runVerify(["--engine", "--mode", "host"]);
    expect(stale.status).toBe(1);
    expect(hasLine(stale.report, /^E0 FAIL /)).toBe(true);
  });

  it("실행 중 작업 트리가 바뀌면 1이다(시작 · 끝 비교 SEC-18)", () => {
    const run = runVerify(["--mode", "host"], { behavior: "assert-log-hygiene.sh=touch" });
    expect(run.status).toBe(1);
    expect(hasLine(run.report, /^T PASS/)).toBe(false);
    expect(hasLine(run.report, /^T FAIL /)).toBe(true);
  });

  it("--help는 단계 · 모드 · 종료 코드를 설명하고 0이다", () => {
    const run = runVerify(["--help"]);
    expect(run.status).toBe(0);
    expect(run.stdout).toContain("--mode auto");
    expect(run.stdout).toContain("--engine");
    expect(run.stdout).toContain("종료 코드");
    expect(run.stdout).toContain("NOT_VERIFIED(host)");
  });

  it("모르는 인자는 통과로 끝나지 않는다", () => {
    const run = runVerify(["--skip-a"]);
    expect(run.status).not.toBe(0);
  });
});
