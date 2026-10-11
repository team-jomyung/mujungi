# 성능 시험 지시 — U1 u1-walking-skeleton (3.6 Build and Test)

> 3.6 Build and Test Step 3 ~ 7 산출물(성능 NFR이 있어 생성) · 2026-10-11 · 단독 실행(`--single`) · 대화 언어 한국어
>
> 입력: `nfr-requirements/performance-requirements.md`(## 1 · ## 3 · ## 4) · `scalability-requirements.md` · `observability-requirements.md`(## 3.1 SLO · ## 3.4 로그 필드), `nfr-design/performance-design.md`(PD-1 ~ PD-20, 2.8 · 2.10 · ## 3 · ## 4) · `scalability-design.md`(2.5 · 2.6) · `observability-design.md`(OD-8 · OD-17), `code-generation-plan.md`(## 시험 전략과 커버리지: "성능 · 부하는 11/2 측정 몫", PD-18 · PD-17 넘김), `code-summary.md`(## 알려진 틈 · 추적 요약), `infra/RUNBOOK.md` 13절(11/2 측정 창).
> 명령 실행은 Step 9, 결과는 `test-results.md`다. 이 파일은 측정 방법 · 시나리오 · 판정 규칙과 '지금 돌릴 수 있는 것 · 없는 것'을 가른다.

## 1. 원칙

- **U1은 재게 만드는 단계다.** 지연 · 처리량 목표의 판정은 11/2 측정 창(OQ10)에서 실공급자 · 시연 EC2 · Nginx 경유 HTTPS/WSS로 한다. 미달은 실패가 아니라 해당 NFRx.y 행 목표만 고치는 조정 대상이다(performance-requirements ## 2). 단, 이 문서는 목표 값을 고치지 않는다.
- **가짜 공급자 값과 섞지 않는다.** 이 샌드박스에서 가짜 공급자 · 기기 시뮬레이터로 잰 값은 기록(기준선)일 뿐이며 11/2 목표의 판정에 쓰지 않는다(`first-bolt.md` 6.2 · performance-requirements 4.2).
- **표본 규칙(재점검 RD-13 · PD-17).** 표본이 100 이상이면 p95(nearest-rank: 오름차순 정렬 뒤 ⌈0.95 × N⌉번째 값), 10 ~ 99면 목표 이내 비율 90% 이상, 10 미만이면 판정하지 않고 값과 건수만 적는다. 분포 전체와 최댓값을 함께 적는다.
- **원음 · 전사 원문 · 토큰은 측정 기록에 넣지 않는다**(NFR9.8 · NFR12.2). 로그에서 옮기는 것은 사건 코드의 숫자 · 시각 · 열거값 attrs뿐이다.
- **실행 계획과의 관계.** 11/2 측정 목표를 명시적으로 맡을 뒤 단계(4.6 performance-validation)는 이번 실행 계획에서 SKIP이다. 그래서 아래 '지금 돌릴 수 없음' 목표는 Step 9에서 `Unverified`이고 뒤 단계로 넘겨 성공 처리할 수 없다(단계 정의 Step 9-4). 이 사실은 `build-and-test-summary.md` ## 알려진 한계에 남긴다.

## 2. 성능 목표 목록

| Target ID | 지표 | 목표 | 측정 환경 · 창 | 이 단계 |
|---|---|---|---|---|
| BT-NFR5.1 | 중계 증가분 = relayIn p95 + relayOut p95 | ≤ 0.5초 | 11/2, 실공급자 L3 | 불가 |
| BT-PD-20 | 설계 예산 relayIn p95 · relayOut p95 | ≤ 0.15초 · ≤ 0.25초(합 0.40초 = 경보선) | 11/2, L3 | 불가 |
| BT-NFR5.2 | 말 끝 t0 → 기기 첫 음성 t3 p95 | ≤ 2.5초(짧은 응답 기준) | 11/2, L3 · 시나리오 셋 | 불가 |
| BT-NFR5.3a | 서버 몫: 전사 확정 → 12 응답 포함 가능(CHILD 행) p95 | ≤ 0.5초 | 11/2, 빠른 폴러 100ms | 불가 |
| BT-NFR5.3b | 서버 몫을 잴 수 있는가(가짜 공급자 통합) | SESSION_ITEMS_SERVED 줄 · 12 items에 같은 행 | 로컬 검증 스택 | 가능(IT-02 C9 · C12) |
| BT-NFR5.5a | 보호자 · 기기 REST 서버 처리 p95 | ≤ 300ms | 11/2, L2 · L6(30쌍 · 세션 2 · ≤ 40 RPS) | 불가 |
| BT-NFR5.5b | 12 진행 조회(Realtime 중계 포함) p95 | ≤ 500ms | 11/2, L2 · L6 | 불가 |
| BT-NFR5.5c | 요청 타이머 라벨 위생 · 사본 폴백 카운터 | uri 경로 템플릿만, 폴백 · 실패 카운터 증가 | 로컬 검증 스택 | 가능(IT-06b · IT-06d) |
| BT-NFR5.6a | /device D0 코드 표시 p95 | ≤ 3초(와이파이) | 11/2, 실기 `?debug=1` | 불가 |
| BT-NFR5.6b | /parent 04 LCP | ≤ 2.5초(Android Chrome 중급기 4G) | 11/2, Lighthouse 모바일 · 실기 | 불가 |
| BT-NFR5.6c | T19 「켜기」 → HELLO_OK p95 | ≤ 2초 | 11/2, 실기 `?debug=1` | 불가 |
| BT-PD-14a | /device 초기 JS(gzip) | ≤ 150KB(판정 150,000바이트) | 정적 산정 + 로컬 스택 전송량 교차 확인 | 가능(PT-01) |
| BT-PD-14b | /device 지연 청크(「켜기」 뒤, gzip) | ≤ 80KB(제안, 판정 80,000바이트) | 정적 산정 | 가능(PT-01) |
| BT-PD-14c | /parent 첫 로드 JS(gzip) | ≤ 200KB(제안, 판정 200,000바이트) | 정적 산정 + 교차 확인 | 가능(PT-01) |
| BT-NFR7.1 | 세션 2개 동시 시 NFR5.1 · t3−t2 증가 | ≤ 20%(표본 100 미만: 동시 턴 90% 이상이 단독 p95의 1.2배 이내) | 11/2, L3 3회 + L4 3회 | 불가 |
| BT-NFR7.2 | 호스트 CPU 시나리오 평균 · 메모리 최댓값 | ≤ 60% · ≤ 70%(5분 평균 아님) | 11/2, L6 · L7, 5초 표본 | 불가 |
| BT-NFR7.5a | Outbox → Inbox 전달 지연 p95(정상 상태) | ≤ 2초 | 11/2, L5 · 세션 시작 중 | 불가 |
| BT-NFR7.6 | DB 풀 대기 100ms 초과 경고 카운터 | 지표 존재 · 주입 시 증가, 풀 합 ≤ 20 | 로컬(존재 · 설정) | 부분(IT-06b · 설정 확인) |

참고(판정 대상이 아닌 설계 예상치): 이벤트 전달 정상 약 0.6초 · 30건 버스트 약 1.3초(PD-10 표 2.7-A), 외부 폴링 약 16 RPS · DB 질의 약 45/초(scalability-design 표 2.6-A), 호스트 컨테이너 메모리 한도 합 5.075GiB(cicd 2.2.3 단언 3). 11/2 측정에서 실측과 비교해 설계 문서의 해당 표만 고친다.

## 3. 측정 방법

### 3.1 지연 — 계측 지점과 계산식

로그 정본은 observability-requirements 3.4(사건 코드 LATENCY_TURN · SESSION_ITEMS_SERVED)다. 모든 서버 시각은 같은 호스트 시계(앱 Clock, UTC)이고 프레임 단위 로그는 없다.

| 구간 | 계산 | 출처 키 |
|---|---|---|
| relayIn(NFR5.1) | 턴마다 `relayInMs`(그 턴 프레임 지연의 p95) → 턴 표본의 p95. 프레임 전체 분포는 `mujoong_relay_in_seconds` 히스토그램 | LATENCY_TURN.relayInMs |
| relayOut(NFR5.1) | `tFirstFrameSent − tSegmentConfirmed`(= `relayOutMs`) | LATENCY_TURN.relayOutMs |
| t0 → t3(NFR5.2) | t3(서버 시계) = `tDevicePlayStart − clockOffsetMs`, 오차 ± `rttMs ÷ 2`. t3 − `tSpeechEnd`. t0 출처는 `tSpeechEndSource` | LATENCY_TURN.tSpeechEnd · tDevicePlayStart · clockOffsetMs · rttMs |
| 구간 분해(NFR5.2) | t1 − t0, `providerResponseMs`, `quietWaitMs`(설정 800ms, 입력 재개 시 더 짧음), t2 − tSegmentConfirmed, t3 − t2 | LATENCY_TURN 각 키 |
| 서버 몫(NFR5.3) | `tViewItemReady − tTranscriptFinal`(speaker CHILD만). 폴링 몫은 `tViewItemFirstServed − tViewItemReady`로 따로 | SESSION_ITEMS_SERVED |
| 미관측 | 시각이 생기지 않은 턴은 키가 비고 사유(`unobservedReason`)가 남는다. 미관측 비율이 0%가 아니면 사유별 건수를 함께 적는다 | LATENCY_TURN |

- '한 턴 로그만으로 위 표를 계산하는 스크립트'(`turn-metrics`, OD-8)와 SLI 전체 계산(`sli-report`, OD-17)은 설계에만 있고 저장소에 없다(`git ls-files` 검색 0건). 11/2 전에 만들어야 하는 측정 준비물이며 NFR5.4 판정과 이어진다(`build-and-test-summary.md` BT-NFR5.4).

### 3.2 REST 처리 시간 (NFR5.5)

- 지표: 프레임워크 `http_server_requests_seconds`(uri = 경로 템플릿, method · status · outcome). SLO 버킷 100 · 300 · 500 · 1000ms가 두 앱 `application.yml`(`management.metrics.distribution.slo."[http.server.requests]"`)에 설정돼 있다.
- p95 ≤ 300ms ⇔ (`_bucket{le="0.3"}` ÷ `_count`) ≥ 0.95(엔드포인트별 · 전체). 12 조회는 같은 방식으로 `le="0.5"`.
- 내부 HTTP 제한(시작 3초 · 끝내기 2초 · 조회 1초)을 넘은 요청은 표본에서 빼지 않고 실패로 센다. 12가 사본으로 답한 횟수는 `mujoong_session_view_fallback_total`로 따로 센다.
- 재시작하면 지표가 0이 되므로 설계는 METRIC_SNAPSHOT(60초, `restLe300Ms` · `restTotal` · `viewLe500Ms` · `viewTotal`)으로 로그에 옮기게 했다. 지금 코드에 METRIC_SNAPSHOT이 없으면(`code-summary.md` ## 알려진 틈 2) 측정 중 재시작이 없게 하고 스크랩을 구간 시작 · 끝에 두 번 해 차이로 계산한다.

### 3.3 이벤트 전달 지연 (NFR7.5)

- `mujoong_event_delivery_seconds{event_type}`(받는 앱, `now − occurredAt` at PROCESSED)와 EVENT_DELIVERY(status PROCESSED)의 `durationMs`. 정상 상태 = 상대 앱이 살아 있고 미전달이 한 배치(20건) 이하.

### 3.4 호스트 자원 (NFR7.2, scalability-design 표 2.6-E)

- 측정 구간: L6 시작 1분 뒤부터 끝까지. CPU = `/proc/stat`의 5초 표본으로 1 − idle의 구간 산술 평균 ≤ 60%. 메모리 = (MemTotal − MemAvailable) ÷ MemTotal의 구간 최댓값 ≤ 70%(페이지 캐시는 사용으로 세지 않음). 보조로 `docker stats` 5초 표본과 앱 지표(힙 · GC · 풀).
- 호스트 수집 스크립트(`host-sample`)는 설계 · RUNBOOK 13절에만 있고 저장소에 없다(검색 0건).

### 3.5 첫 화면 (NFR5.6, PD-17)

- 04 LCP: Lighthouse 모바일 프로필 + Android Chrome 중급기 실측. D0 코드 표시 · 켜기 → HELLO_OK: `/device?debug=1` 상태 로그(BR9.6)의 시각. 항목마다 조건(캐시 · 쿠키 · 네트워크)을 고정하고 10회 이상 잰다(10회 중 9회 규칙).
- 이 샌드박스에는 실기 · 브라우저 · Lighthouse가 없다.

### 3.6 번들 예산 (PD-14)

- **초기 JS** = `.next/build-manifest.json`의 `rootMainFiles` ∪ 그 경로 `page_client-reference-manifest.js`의 `entryJSFiles` 전부(레이아웃 · 페이지). `polyfillFiles`는 `nomodule`이라 현대 브라우저가 받지 않으므로 빼고 따로 적는다.
- **지연 청크(/device)** = /device 진입 청크가 경로 문자열(`static/chunks/<이름>.js`)로 가리키지만 초기 집합에 없는 청크(「켜기」 뒤 `import("./runtime/deviceRuntime")` · `import("./mic/micPermission")`가 받는 것).
- **압축 기준**: Nginx와 같은 gzip 레벨 5(`infra/nginx/nginx.conf.tmpl`의 `gzip_comp_level 5`). **단위**: 더 엄격한 해석으로 1KB = 1,000바이트(150KB = 150,000바이트)로 판정하고 KiB 값도 함께 적는다.
- **교차 확인**: 로컬 검증 스택(IT-06)이 떠 있을 때 `/device` · `/parent` HTML의 `<script src>`(nomodule 제외) 목록이 정적 산정 집합과 같은지, Nginx를 지난 실제 전송 크기(`Accept-Encoding: gzip`)의 합을 함께 적는다.

## 4. 측정 도구 현황 (2026-10-11 저장소 검색)

| 도구 | 쓰임 | 상태 |
|---|---|---|
| LATENCY_TURN · SESSION_ITEMS_SERVED 로그(`LatencyRecorder`) | 지연 원자료 | 있음(검증 명령 C12가 키 존재를 판정) |
| `infra/bin/probe-three` | 3종 호출 프로브 · 배포 1초 샘플러 · `--summarize`(downtimeSec) | 있음 |
| `infra/bin/check-since-last` · `infra/bin/host-collect.sh` | 수동 점검표 · 호스트 수집(분 단위) | 있음 |
| `turn-metrics`(OD-8) · `sli-report`(OD-17) | 한 턴 · SLI 계산 | 없음 |
| `probe-daily`(RD-18) | 가용성 일일 집계 | 없음 |
| 부하 시험 전용 시드 도구(PD-18 · A6) · `compose.loadtest.yml` · `host-sample` | 30쌍 시드 · 측정 스택 · 호스트 5초 표본 | 없음(RUNBOOK 13절: "11/2 측정 준비(3.6 이후)의 산출물") |
| 부하 스크립트(기기 시뮬레이터 N개, 외부 폴링 ≤ 40 RPS) · 빠른 폴러(100ms) | L1 ~ L7 구동 · 서버 몫 측정 | 없음(J69: 자체 WSS 부하 스크립트, 이름은 3.5 확정이었으나 넘김) |
| 표본 판정 스크립트(PD-17 · SCD-6) | p95 · 90% 규칙 · 겹친 턴 세기 | 없음 |
| METRIC_SNAPSHOT(OD-6) | 재시작에 강한 SLI 원자료 | 사건 코드 · attrs 정의(`contract-model`의 `EventCode`)만 있고 core-api · realtime-activity에 60초 발행 코드가 없음(`code-summary.md` ## 알려진 틈 2, 이 문서를 쓰며 한 코드 검색) — IT-06b가 실제 발행 여부를 확인 |

'없음' 도구는 이 단계가 만들 수 없다(애플리케이션 · 도구 소스 수정 금지). 11/2 측정 준비로 넘기고 Step 9에서 해당 목표는 `Unverified`로 남긴다.

## 5. 부하 시나리오 L1 ~ L7 (performance-design 표 2.10-C)

| ID | 구성 | 목적 | 판정 | 공급자 | 전제 |
|---|---|---|---|---|---|
| L1 | 30 WSS 연결 대기(HEARTBEAT만) 10분 | 유휴 기준선(CPU · 메모리 · 하트비트 쓰기율) | NFR7.2 기준선 | 없음 | 시드 30세트 · 시뮬레이터 30 |
| L2 | D0 상태 확인 30 클라이언트(SQL로 넣은 bootstrap 쿠키, 2초) + 12 폴링 보호자 2명 ≈ 16 RPS → 40 RPS까지(간격 인자) | REST 처리 시간 | NFR5.5: REST p95 ≤ 300ms · 12 조회 p95 ≤ 500ms | 없음 | 부하 스크립트 · 시드(D0 폴링용 30행) |
| L3 | 세션 1개(실공급자, 합성 · 성인 음성) 20턴 × 3회, 나머지 29대 유휴 | 단독 기준 | NFR7.1 기준값(NFR5.1 · t3−t2), NFR5.1 · 5.2 · 5.3 표본 | 실공급자 | 실공급자 키(서버 환경 변수만) |
| L4 | 세션 2개 동시(실공급자) 20턴 × 3회, 나머지 28대 유휴 | 동시 비교 | NFR7.1(scalability-design 2.5 판정 규칙) | 실공급자 | 시드 세트 1 ~ 2 |
| L5 | 30 연결 동시 끊고 동시 재접속 | 일제 재접속 · 이벤트 버스트 | NFR7.5: DeviceStatusChanged 전달 p95 ≤ 2초, 풀 대기 경고 | 없음 | 시뮬레이터 30 |
| L6 | L2 + L4 동시 | 최대 부하 | NFR7.2: CPU 시나리오 평균 ≤ 60%, 메모리 최댓값 ≤ 70% | 실공급자(활성 2) | 위 전부 + 호스트 5초 수집 |
| L7 | L1 + L2를 30분 | 누수 · 메모리 최댓값 | NFR7.2 | 없음 | 위와 같음 |

공통 규칙(performance-design 2.10):
- 시드는 앱 밖 시험 도구가 core_app 역할 SQL로 core 스키마에만 넣는다(앱에 시드 빈 없음). 기기 토큰(256비트) · bootstrap 비밀(128비트 이상)은 실행 때 만들고 해시만 DB에, 원문은 저장소 밖 600 권한 파일에만 둔다. 끝나면 정리 모드로 결정적 UUID 행을 지우고 U1 표에 `LT-` 행 0건을 확인한다.
- 발급 한도(`bootstrap.rate-limit`)는 demo 값 20회/시간을 바꾸지 않는다. D0 폴링은 SQL로 넣은 쿠키로 상태 확인만 부른다. POST bootstrap을 시간당 20회 넘게 불러야 하는 시험만 dev + load-test 프로필(600회/시간)과 공개 진입을 닫은 측정 창에서 한다(load-test 단독 · demo/prod + load-test는 기동 거부).
- L3 · L4 · L6의 활성 세션 2개만 실공급자, 나머지는 가짜 공급자. 결과 기록에 구분한다. 외부 폴링 합계는 40 RPS로 제한한다.

## 6. 11/2 측정 창 절차 (infra/RUNBOOK.md 13절 요약)

| 단계 | 작업 |
|---|---|
| 0 | GitHub `AUTO_DEPLOY=false` |
| 1 | 상시 컨테이너 로그 보존 → `systemctl stop mujoong-compose`(정지 직전 백업 · `POWER_STOP` 자동) → `mj --measurement-window down`(`-v` 금지) |
| 2 | 계정 관리자가 보안 그룹 80 · 443 원본을 시험 PC /32로 좁힌다 |
| 3 | `compose.loadtest.yml`로 `mujoong-lt` 기동(같은 SHA, dev + load-test, 데이터 `/var/lib/mujoong/loadtest/`) → readiness 200 둘 |
| 4 | 시드 도구로 30쌍(토큰 원문은 `/var/lib/mujoong/loadtest/tokens/` root 600에만) |
| 5 | L1 ~ L7과 `host-sample` 5초 표본, 기록 파일은 측정 직후 내려받는다 |
| 6 | 측정 스택 정지 · 토큰 · 시험 데이터 삭제 → 보안 그룹 복원 → `systemctl start mujoong-compose` → `AUTO_DEPLOY` 복원 |

결과 기록 형식: 재현 조건(커밋 SHA · 설정 키 값 · 브라우저 · 네트워크) · 표본 수 · p50 · p95 · 최댓값 · 판정 · 실공급자/가짜 구분. 미달이면 해당 NFRx.y 행 목표만 사람 결정으로 고친다(측정 로그는 유지, NFR5.4).

## 7. 지금 돌릴 수 있는 것 (이 샌드박스, Step 9)

### PT-01 번들 예산 정적 산정 (BT-PD-14a · 14b · 14c)

`next build`(B-03 또는 IT-02 A2) 뒤:

```bash
mkdir -p build/b-and-t/PT-01
cat >build/b-and-t/PT-01/pt01.mjs <<'JS'
// PT-01 번들 예산 정적 산정(PD-14). node pt01.mjs <frontend 디렉터리> — 결과 JSON 한 줄(값은 바이트 수뿐)
import { readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import vm from "node:vm";
import path from "node:path";
const fe = process.argv[2] ?? "frontend";
const nx = path.join(fe, ".next");
const bm = JSON.parse(readFileSync(path.join(nx, "build-manifest.json"), "utf8"));
const root = new Set(bm.rootMainFiles);
const poly = new Set(bm.polyfillFiles);
function routeEntries(route) {
  const code = readFileSync(path.join(nx, "server", "app", route, "page_client-reference-manifest.js"), "utf8");
  const ctx = { globalThis: {} };
  vm.runInNewContext(code, ctx);
  const man = Object.values(ctx.globalThis.__RSC_MANIFEST)[0];
  return new Set(Object.values(man.entryJSFiles).flat());
}
const gz = (f) => gzipSync(readFileSync(path.join(nx, f)), { level: 5 }).length;
const total = (set) => [...set].reduce((s, f) => s + gz(f), 0);
const device = new Set([...root, ...routeEntries("device")]);
const parent = new Set([...root, ...routeEntries("parent")]);
const lazy = new Set();
for (const f of routeEntries("device")) {
  for (const m of readFileSync(path.join(nx, f), "utf8").matchAll(/static\/chunks\/[0-9A-Za-z_.-]+\.js/g)) {
    if (!device.has(m[0]) && !poly.has(m[0])) lazy.add(m[0]);
  }
}
const kib = (b) => Math.round((b / 1024) * 10) / 10;
const row = (set, budget) => ({ files: [...set].sort(), gzipBytes: total(set), gzipKiB: kib(total(set)), budgetBytes: budget, withinBudget: total(set) <= budget });
console.log(JSON.stringify({ gzipLevel: 5, polyfillExcluded: [...poly], device: row(device, 150000), deviceLazy: row(lazy, 80000), parent: row(parent, 200000) }));
JS
node build/b-and-t/PT-01/pt01.mjs frontend | tee build/b-and-t/PT-01/static.json | jq '{device: .device.gzipBytes, deviceLazy: .deviceLazy.gzipBytes, parent: .parent.gzipBytes}'
```

교차 확인(IT-06 스택이 떠 있을 때, 같은 셸 변수 `VC` · `B`):

```bash
for r in device parent; do
  $VC "$B/$r" | grep -oE '<script[^>]*src="/_next/static/chunks/[^"]+\.js"[^>]*>' | grep -vi nomodule | grep -oE '/_next/static/chunks/[^"]+\.js' | sort -u >"build/b-and-t/PT-01/html-$r.txt"
  sum=0; while read -r p; do n=$($VC -H 'Accept-Encoding: gzip' -o /dev/null -w '%{size_download}' "$B$p"); sum=$((sum + n)); done <"build/b-and-t/PT-01/html-$r.txt"
  echo "$r wireGzipBytes=$sum files=$(wc -l <"build/b-and-t/PT-01/html-$r.txt")"
done | tee build/b-and-t/PT-01/wire.txt
```

- **기대.** `device.gzipBytes` ≤ 150,000, `deviceLazy.gzipBytes` ≤ 80,000, `parent.gzipBytes` ≤ 200,000. HTML의 스크립트 목록이 정적 산정의 초기 집합(`device.files` · `parent.files`)과 같고, 전송 크기 합이 정적 값과 크게 어긋나면(±10% 밖) 두 값을 모두 적고 차이 원인을 적는다. 예산을 넘으면 `Not Met`이며 예산 값을 바꾸지 않는다.

### PT-02 한 턴 지연 기준선 기록 (판정 아님, J131)

IT-02가 끝난 직후(`build/verify/logs/compose.log`는 다음 검증 실행이 지운다) 또는 IT-06 스택에서:

```bash
mkdir -p build/b-and-t/PT-02
sed -n 's/^realtime-1 *| //p' build/verify/logs/compose.log | jq -Rc 'fromjson? | select(.event=="LATENCY_TURN" or .event=="SESSION_ITEMS_SERVED") | {event, attrs}' >build/b-and-t/PT-02/latency-it02.jsonl
grep -E '^ *C(6|9|10|11|12) ' build/b-and-t/IT-02/report.txt >build/b-and-t/PT-02/c-steps-it02.txt || true
# IT-06 스택이 떠 있으면 같은 추출: docker logs mujoong-verify-realtime-1 2>&1 | jq -Rc 'fromjson? | select(.event=="LATENCY_TURN" or .event=="SESSION_ITEMS_SERVED") | {event, attrs}'
```

- 적는 값: `relayInMs` · `relayOutMs` · `providerResponseMs` · `quietWaitMs` · `tSpeechEndSource` · `clockOffsetMs` · `rttMs`, (가능하면) t3 − t0, SESSION_ITEMS_SERVED(CHILD)의 `tViewItemReady − tTranscriptFinal`, C6(04 STABLE 도달) · C9 · C10 · C11 소요. 표본이 10 미만이고 가짜 공급자 값이므로 판정하지 않는다(RD-13). 다음 실행과 비교할 기준선으로만 쓴다.

### PT-03 이벤트 전달 지연 기준선 기록 (판정 아님)

```bash
sed -n 's/^\(core-api\|realtime\)-1 *| //p' build/verify/logs/compose.log | jq -Rc 'fromjson? | select(.event=="EVENT_DELIVERY") | {s: .attrs.status, t: .attrs.eventType, ms: .attrs.durationMs}' >build/b-and-t/PT-02/event-delivery-it02.jsonl
```

- PROCESSED 줄의 `durationMs` 분포와 FAILED · DEAD 건수(BT-SLO-05 참고값)를 적는다. 지표 `mujoong_event_delivery_seconds`의 존재는 IT-06b가 본다.

### PT-04 REST 계측 장치 확인 (BT-NFR5.5c의 일부)

- IT-06b 스크랩(`build/b-and-t/IT-06/prom-*.txt`)에서 `http_server_requests_seconds_bucket`에 `le="0.1"` · `le="0.3"` · `le="0.5"` · `le="1.0"` 버킷이 있는지, uri 라벨이 경로 템플릿뿐인지 본다:
  `grep -oE 'le="[0-9.]+"' build/b-and-t/IT-06/prom-core-api.txt | sort -u` · `cat build/b-and-t/IT-06/uri-labels.txt`.
- 로컬 값(`le="0.3"` 비율)은 기록만 하고 판정하지 않는다.

### PT-05 풀 · 설정 확인 (BT-NFR7.6의 일부)

```bash
grep -n 'maximum-pool-size' backend/core-api/src/main/resources/application.yml backend/realtime-activity/src/main/resources/application.yml
```

- **기대.** 두 앱 각각 10(합 20 이하). 기동 검증(`CorePropertiesValidationTest` · 합계 20 초과 시 기동 거부)은 유닛 명령 결과로 본다. 풀 대기 주입 시험(연결을 점유해 경고 카운터 증가)은 저장소에 없다 — 지표 존재만 IT-06b가 본다.

## 8. 지금 돌릴 수 없는 것과 소유

| Target ID | 막는 것 | 설계상 측정 시점 | 실행 계획의 소유 단계 |
|---|---|---|---|
| BT-NFR5.1 · BT-PD-20 · BT-NFR5.2 · BT-NFR5.3a | 실공급자 · 시연 EC2 · `turn-metrics` · 빠른 폴러 없음 | 11/2(L3) | 없음 — 4.6 performance-validation SKIP |
| BT-NFR5.5a · BT-NFR5.5b | 부하 스크립트 · 부하 시드 · EC2 없음 | 11/2(L2 · L6) | 없음 |
| BT-NFR5.6a · 5.6b · 5.6c | 실기 · 브라우저 · Lighthouse 없음 | 11/2 | 없음 |
| BT-NFR7.1 | 실공급자 · 시드 2세트 · 표본 판정 스크립트 없음 | 11/2(L3 · L4) | 없음 |
| BT-NFR7.2 | 부하 도구 · `host-sample` · EC2 없음 | 11/2(L6 · L7) | 없음 |
| BT-NFR7.5a | 시뮬레이터 30 · 부하 도구 없음 | 11/2(L5) | 없음 |

Step 9는 위 목표를 `Unverified`로 두고 '없음'을 그대로 적는다. 사다리 2단(단독 실행은 2단까지)에서 검토할 해결 후보는 ① 실행 계획에 4.6 performance-validation을 넣는 안(사람 결정. 드는 것: 측정 준비물 — 부하 시드 도구 · 부하 스크립트 · `turn-metrics` · `host-sample` · `compose.loadtest.yml` — 구현과 11/2 측정 창 운영, 실공급자 요금) ② 11/2 측정을 실행 계획 밖 일정으로 두고 이 목표들의 판정을 그때 받기로 사람이 정하는 안이다. 영향 추정(작업량 · 비용 · 위험)은 Step 9가 `test-results.md`에 적는다. 어느 쪽도 목표 값을 낮추지 않는다.

## 9. 회귀 감지 기준선

- 매 Build and Test 실행은 PT-01(번들 바이트)과 PT-02(가짜 공급자 한 턴 값)를 `test-results.md`에 남긴다. 다음 실행에서 같은 조건의 값과 나란히 적어 크게 늘어난 항목(예: 번들 10% 이상 증가)을 알려진 위험으로 올린다. 이것은 기록 규칙이며 새 판정 목표가 아니다.
- 11/2 측정 뒤에는 그 결과가 기준선이 되고, 같은 대본 · 같은 설정으로 다시 잰 값과 비교한다(performance-requirements 4.2).

## 10. 결과 기록

- `test-results.md`의 성능 절에 Target ID별 실제 값(바이트 · 건수 · ms) · 증거 경로(`build/b-and-t/PT-0n/…`) · 판정을 적는다. 기록만 하는 값(PT-02 · PT-03 · PT-04의 로컬 값)은 '기록(판정 아님)'으로 표시한다.
- 원음 · 전사 · 토큰 · 쿠키는 어떤 기록에도 옮기지 않는다.
