package kr.mujoong.realtime.config;

import static kr.mujoong.realtime.config.RealtimeProperties.nonNegative;
import static kr.mujoong.realtime.config.RealtimeProperties.positive;

import java.time.Duration;
import org.springframework.util.unit.DataSize;

/** 한도. U3: 원음 기록 대기열 프레임 수(넘치면 버리고 센다). */
public record RealtimeLimitsProperties(
        DataSize wsTextFrameMax,
        DataSize internalEventBodyMax,
        Integer sessionInputQueueFrames,
        Integer sessionControlQueue,
        Integer deviceControlQueue,
        Integer recorderQueue,
        Integer recorderFlushConcurrency,
        Integer backgroundDbConcurrency,
        Duration dbLockTimeout,
        Duration dbStatementTimeout,
        Integer dbRetryAttempts,
        Integer recordingQueueFrames) {

    public RealtimeLimitsProperties {
        positive(wsTextFrameMax, "mujoong.limits.ws-text-frame-max");
        positive(internalEventBodyMax, "mujoong.limits.internal-event-body-max");
        positive(sessionInputQueueFrames, "mujoong.limits.session-input-queue-frames");
        positive(sessionControlQueue, "mujoong.limits.session-control-queue");
        positive(deviceControlQueue, "mujoong.limits.device-control-queue");
        positive(recorderQueue, "mujoong.limits.recorder-queue");
        positive(recorderFlushConcurrency, "mujoong.limits.recorder-flush-concurrency");
        positive(backgroundDbConcurrency, "mujoong.limits.background-db-concurrency");
        positive(dbLockTimeout, "mujoong.limits.db-lock-timeout");
        positive(dbStatementTimeout, "mujoong.limits.db-statement-timeout");
        nonNegative(dbRetryAttempts, "mujoong.limits.db-retry-attempts");
        positive(recordingQueueFrames, "mujoong.limits.recording-queue-frames");
    }
}
