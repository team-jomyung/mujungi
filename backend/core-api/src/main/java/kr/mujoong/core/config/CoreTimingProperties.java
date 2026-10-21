package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.invalid;
import static kr.mujoong.core.config.CoreProperties.positive;
import static kr.mujoong.core.config.CoreProperties.positiveList;
import static kr.mujoong.core.config.CoreProperties.required;

import java.time.Duration;
import java.util.List;

/**
 * 계약 7절 시간 키와 J80이 허용한 7절 밖 시간 키(cache).
 *
 * <p>{@code mujoong.timing.*} 아래 묶음을 한 파일에 모았다. 루트 설정 레코드 {@link CoreProperties}가 이 타입을 {@code timing}으로 받는다.
 */
public record CoreTimingProperties(
        SessionTiming session,
        CoreTiming core,
        BootstrapTiming bootstrap,
        DeviceTiming device,
        LoginTiming login,
        CacheTiming cache,
        AccountTiming account,
        PracticeTiming practice,
        GoalTiming goal,
        ResultTiming result,
        ReportTiming report,
        PushTiming push,
        KnowledgeTiming knowledge) {

    public CoreTimingProperties {
        required(knowledge, "mujoong.timing.knowledge");
        required(session, "mujoong.timing.session");
        required(core, "mujoong.timing.core");
        required(bootstrap, "mujoong.timing.bootstrap");
        required(device, "mujoong.timing.device");
        required(login, "mujoong.timing.login");
        required(cache, "mujoong.timing.cache");
        required(account, "mujoong.timing.account");
        required(practice, "mujoong.timing.practice");
        required(goal, "mujoong.timing.goal");
        required(result, "mujoong.timing.result");
        required(report, "mujoong.timing.report");
        required(push, "mujoong.timing.push");
    }

    /** 작업 재시도 · 실행 · 누락 판정의 시간 계약. */
    public record ResultTiming(Integer jobAttempts, List<Duration> jobRetryBackoff, Duration jobTick,
            Duration attemptTimeout, Duration reconcileInterval, Duration undecidedLimit) {
        public ResultTiming {
            positive(jobAttempts, "mujoong.timing.result.job-attempts");
            positiveList(jobRetryBackoff, "mujoong.timing.result.job-retry-backoff");
            // Existing explicit U6 configurations remain readable; U14 production default is four total attempts.
            if ((jobAttempts != 3 && jobAttempts != 4) || jobRetryBackoff.size() != jobAttempts - 1) {
                throw invalid("mujoong.timing.result 시도 수와 재시도 간격이 계약과 다르다");
            }
            jobRetryBackoff = List.copyOf(jobRetryBackoff);
            positive(jobTick, "mujoong.timing.result.job-tick");
            positive(attemptTimeout, "mujoong.timing.result.attempt-timeout");
            positive(reconcileInterval, "mujoong.timing.result.reconcile-interval");
            positive(undecidedLimit, "mujoong.timing.result.undecided-limit");
        }
    }

    /** 리포트 절대 마감과 미저장 보존. */
    public record ReportTiming(Duration readyTarget, Duration unsavedRetention) {
        public ReportTiming {
            positive(readyTarget, "mujoong.timing.report.ready-target");
            positive(unsavedRetention, "mujoong.timing.report.unsaved-retention");
        }
    }

    public record PushTiming(Duration dispatchPoll, List<Duration> retryBackoff,
            Duration sendTimeout, Duration deviceStatusCheck) {
        public PushTiming {
            positive(dispatchPoll, "mujoong.timing.push.dispatch-poll");
            positiveList(retryBackoff, "mujoong.timing.push.retry-backoff");
            positive(sendTimeout, "mujoong.timing.push.send-timeout");
            positive(deviceStatusCheck, "mujoong.timing.push.device-status-check");
            retryBackoff = List.copyOf(retryBackoff);
        }
    }

    /** 지식 그래프 일반 업무 상한과 서버 시작 준비의 별도 전체 마감(timing.md 13절). */
    public record KnowledgeTiming(Duration graphBudget, Duration graphInitializationBudget) {
        public KnowledgeTiming {
            positive(graphBudget, "mujoong.timing.knowledge.graph-budget");
            positive(graphInitializationBudget, "mujoong.timing.knowledge.graph-initialization-budget");
        }
    }

    /** U5a C10 상황 생성 전체 예산(생성 · 재생성 · 기술 재시도 포함, 요청 선점 시한도 같다)(timing.md 9절). */
    public record PracticeTiming(Duration scenarioRequestTimeout,Duration scenarioHistoryTimeout) {
        public PracticeTiming(Duration scenarioRequestTimeout) { this(scenarioRequestTimeout,scenarioRequestTimeout); }
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public PracticeTiming {
            positive(scenarioRequestTimeout, "mujoong.timing.practice.scenario-request-timeout");
            positive(scenarioHistoryTimeout, "mujoong.timing.practice.scenario-history-timeout");
        }
    }

    /** U5a 목표 삭제 뒤 서버가 복구를 받아 주는 시한(C4 · timing.md 9절). */
    public record GoalTiming(Duration restoreWindow) {
        public GoalTiming {
            positive(restoreWindow, "mujoong.timing.goal.restore-window");
        }
    }

    /**
     * U2 계정 삭제 지휘 주기 · 진행 세션 끝 대기(AC1.6.3) · 구글 서명 열쇠 캐시 바닥 · 상한(2차 리뷰 4 · 3차 리뷰 3)(timing.md 8절). 열쇠 캐시는
     * 응답의 max-age만큼 두되 바닥보다 짧게 두지 않고 상한을 넘지 않는다.
     */
    public record AccountTiming(
            Duration deletionTick, Duration deletionSessionWait, Duration googleKeysMinAge, Duration googleKeysMaxAge) {
        public AccountTiming {
            positive(deletionTick, "mujoong.timing.account.deletion-tick");
            positive(deletionSessionWait, "mujoong.timing.account.deletion-session-wait");
            positive(googleKeysMinAge, "mujoong.timing.account.google-keys-min-age");
            positive(googleKeysMaxAge, "mujoong.timing.account.google-keys-max-age");
            if (googleKeysMinAge.compareTo(googleKeysMaxAge) > 0) {
                throw new IllegalArgumentException(
                        "mujoong.timing.account.google-keys-min-age가 mujoong.timing.account.google-keys-max-age보다 길다");
            }
        }
    }

    /** ① PREPARING 10초 점검. */
    public record SessionTiming(Duration preparingTimeout) {
        public SessionTiming {
            positive(preparingTimeout, "mujoong.timing.session.preparing-timeout");
        }
    }

    /** 사본 신선도 기준. */
    public record CoreTiming(Duration deviceStatusStale) {
        public CoreTiming {
            positive(deviceStatusStale, "mujoong.timing.core.device-status-stale");
        }
    }

    /** 코드 TTL(bootstrap 쿠키 Max-Age도 이 값) · IP 발급 한도. */
    public record BootstrapTiming(Duration codeTtl, RateLimit rateLimit) {
        public BootstrapTiming {
            positive(codeTtl, "mujoong.timing.bootstrap.code-ttl");
            required(rateLimit, "mujoong.timing.bootstrap.rate-limit");
            rateLimit.validate("mujoong.timing.bootstrap.rate-limit");
        }
    }

    /**
     * 등록 실패 한도 · 기기 쿠키 수명. {@code statusReportInterval}은 Realtime이 소유하는
     * 계약 7절 값이고 Core는 I-6 기동 검증에만 읽는다.
     */
    public record DeviceTiming(RateLimit registerFailureLimit, Duration cookieMaxAge, Duration statusReportInterval) {
        public DeviceTiming {
            required(registerFailureLimit, "mujoong.timing.device.register-failure-limit");
            registerFailureLimit.validate("mujoong.timing.device.register-failure-limit");
            positive(cookieMaxAge, "mujoong.timing.device.cookie-max-age");
            positive(statusReportInterval, "mujoong.timing.device.status-report-interval");
        }
    }

    /** 보호자 세션 유휴 만료. */
    public record LoginTiming(Duration sessionIdle) {
        public LoginTiming {
            positive(sessionIdle, "mujoong.timing.login.session-idle");
        }
    }

    /** PD-7 승인 문구 · 정책 버전 캐시(7절 밖 시간 키). */
    public record CacheTiming(Duration approvedContentTtl, Duration policyVersionTtl) {
        public CacheTiming {
            positive(approvedContentTtl, "mujoong.timing.cache.approved-content-ttl");
            positive(policyVersionTtl, "mujoong.timing.cache.policy-version-ttl");
        }
    }

    /** 미끄러지는 창 한도(max 회 / window). */
    public record RateLimit(Integer max, Duration window) {
        void validate(String key) {
            positive(max, key + ".max");
            positive(window, key + ".window");
        }
    }
}
