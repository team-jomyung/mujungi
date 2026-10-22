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

    /** 참여 전 전사 한 조각의 판정 결과. 탈락 사유는 로그에 사유 코드로만 남는다(전사 원문 없음). */
    enum Verdict {
        ACCEPT,
        /** 참여 창이 아직 열리지 않았다(안내 재생 중). */
        NOT_OPEN,
        /** 시간 정보가 있고 창이 열릴 때의 세션 오디오 시계보다 먼저 시작했다. */
        BEFORE_WINDOW,
        /** 시간 정보가 있고 이미 본 입력의 끝보다 먼저 시작했다(옛 발화의 꼬리). */
        BEFORE_WATERMARK,
        /** 시간 정보가 없고 이미 본 항목이거나 항목 번호가 없다. */
        LEGACY_ITEM
    }

    boolean accepts(InputTranscriptDelta delta) {
        return judge(delta) == Verdict.ACCEPT;
    }

    Verdict judge(InputTranscriptDelta delta) {
        boolean timed = delta.startMs() >= 0 && delta.endMs() >= delta.startMs();
        Verdict verdict;
        if (!opened) {
            verdict = Verdict.NOT_OPEN;
        } else if (timed) {
            verdict = delta.startMs() < windowStartMs ? Verdict.BEFORE_WINDOW
                    : delta.startMs() <= previousInputEndMs ? Verdict.BEFORE_WATERMARK : Verdict.ACCEPT;
        } else {
            verdict = delta.itemId() != null && !legacyOverflow && !previousItems.contains(delta.itemId())
                    ? Verdict.ACCEPT : Verdict.LEGACY_ITEM;
        }
        if (timed) observedEndMs = Math.max(observedEndMs, delta.endMs());
        if (delta.itemId() != null) {
            if (seenItems.size() < MAX_LEGACY_ITEMS) seenItems.add(delta.itemId());
            else legacyOverflow = true;
            if (verdict != Verdict.ACCEPT) remember(delta.itemId());
        }
        return verdict;
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
