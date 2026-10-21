package kr.mujoong.realtime.session;

import java.util.HashSet;
import java.util.Set;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent.InputTranscriptDelta;

/** 참여 전 전사 귀속. quiet는 발화의 신원을 새로 만들지 않는다. 원문은 보관하지 않는다. */
final class ParticipationInputBoundary {
    private static final int MAX_LEGACY_ITEMS = 256;
    private final Set<String> previousItems = new HashSet<>();
    private final Set<String> seenItems = new HashSet<>();
    private long observedEndMs = -1;
    private long previousInputEndMs = -1;
    private long windowStartMs = Long.MAX_VALUE;
    private boolean opened;
    private boolean legacyOverflow;

    void open(long sessionAudioClockMs, long receivedInputEndMs) {
        previousItems.addAll(seenItems);
        windowStartMs = sessionAudioClockMs;
        previousInputEndMs = Math.max(observedEndMs, receivedInputEndMs);
        opened = true;
    }

    boolean accepts(InputTranscriptDelta delta) {
        boolean timed = delta.startMs() >= 0 && delta.endMs() >= delta.startMs();
        boolean current = opened && (timed ? delta.startMs() >= windowStartMs && delta.startMs() > previousInputEndMs
                : delta.itemId() != null && !legacyOverflow && !previousItems.contains(delta.itemId()));
        if (timed) observedEndMs = Math.max(observedEndMs, delta.endMs());
        if (delta.itemId() != null) {
            if (seenItems.size() < MAX_LEGACY_ITEMS) seenItems.add(delta.itemId());
            else legacyOverflow = true;
            if (!current) remember(delta.itemId());
        }
        return current;
    }

    boolean acceptsLegacyDone(String itemId) {
        return opened && itemId != null && !legacyOverflow && !previousItems.contains(itemId);
    }

    void remember(String itemId) {
        if (itemId == null) return;
        if (previousItems.size() < MAX_LEGACY_ITEMS) previousItems.add(itemId);
        else legacyOverflow = true;
    }

    void clear() {
        previousItems.clear();
        seenItems.clear();
        opened = false;
        legacyOverflow = false;
    }
}
