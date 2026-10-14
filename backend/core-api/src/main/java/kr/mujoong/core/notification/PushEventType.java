package kr.mujoong.core.notification;

public enum PushEventType {
    VOICE_PROBLEM, DEVICE_STATUS_UNKNOWN, ENDED_BY_CONNECTION, ACTIVITY_COMPLETED;

    public boolean technical() {
        return this == VOICE_PROBLEM || this == DEVICE_STATUS_UNKNOWN;
    }
}
