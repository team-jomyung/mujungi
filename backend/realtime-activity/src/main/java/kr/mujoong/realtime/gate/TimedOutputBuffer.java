package kr.mujoong.realtime.gate;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Matches transcript deltas to the continuous PCM clock. Never releases audio beyond a transcript's end. */
public final class TimedOutputBuffer {
    private record Text(String value, long endByte, Instant at, long nanos) { }
    /** 어절 경계를 기다리며 이어 붙일 글자 상한(넘으면 경계가 없어도 낸다). 시간 값이 아니다. */
    static final int WORD_UNIT_MAX_CHARS = 40;
    private final Deque<Text> texts = new ArrayDeque<>();
    private final Deque<byte[]> audio = new ArrayDeque<>();
    private long firstByte;
    private long nextByte;
    private long lastEnd = -1;
    private int bufferedBytes;
    private int textCharacters;
    private boolean initialized;
    private boolean audioStarted;
    private String skippedText = "";
    private boolean failed;

    public Instant pendingSince() { return texts.isEmpty() ? null : texts.getFirst().at(); }

    public boolean hasPendingText() { return !texts.isEmpty(); }

    public boolean isOpen() { return initialized; }

    /** 이 응답을 단위별로 받기로 정했다(음성이나 빈 전사로 시작해도 응답이 끝날 때까지 이 경로로 받는다). */
    public void open() { initialized = true; }

    /** 단위 버퍼를 더 쓸 수 없게 된 까닭(로그 사유 코드용). */
    public enum FailReason { TEXT_LIMIT, AUDIO_ODD, AUDIO_LIMIT, AUDIO_GAP }

    private FailReason failReason;

    /**
     * 시간 정보가 있는 출력 전사 조각. 실제 공급자는 빈 조각 · 앞 조각과 겹치거나 길이가 0인 조각을 보내기도 한다: 빈 조각은 버리고, 앞 조각의 끝을 넘지
     * 않는 조각은 앞 조각(없으면 다음 단위)에 글자만 이어 붙이고, 앞 끝보다 앞에서 시작해 그 뒤로 이어지는 조각은 시작을 앞 끝으로 당겨 받는다. 개수 ·
     * 글자 상한을 넘을 때만 쓸 수 없게 한다.
     */
    public void transcript(String text, long startMs, long endMs, Instant at, long nanos) {
        if (failed) return;
        if (text == null || text.isBlank()) return;
        if (texts.size() >= 128 || textCharacters + text.length() > 8192 || endMs > Long.MAX_VALUE / 48) {
            fail(FailReason.TEXT_LIMIT);
            return;
        }
        boolean timed = startMs >= 0 && endMs > startMs;
        if (!timed || (lastEnd >= 0 && endMs <= lastEnd)) {
            // 시간 정보가 없거나 앞 조각 안에 드는 조각: 글자만 이어 붙인다
            textCharacters += text.length();
            Text last = texts.pollLast();
            if (last != null) {
                texts.addLast(new Text(last.value() + text, last.endByte(), last.at(), last.nanos()));
            } else {
                skippedText += text;
            }
            return;
        }
        initialized = true;
        texts.addLast(new Text(text, endMs * 48, at, nanos));
        textCharacters += text.length();
        lastEnd = endMs;
    }

    private void fail(FailReason reason) {
        failed = true;
        failReason = reason;
    }

    /** 쓸 수 없게 된 까닭(쓸 수 있으면 null). */
    public FailReason failReason() { return failReason; }

    public void audio(byte[] pcm, long startByte) {
        if (failed) return;
        if (pcm.length % 2 != 0) {
            fail(FailReason.AUDIO_ODD);
            return;
        }
        if (bufferedBytes + (long) pcm.length > SegmentBuffer.MAX_BYTES) {
            fail(FailReason.AUDIO_LIMIT);
            return;
        }
        if (audioStarted && nextByte != startByte) {
            fail(FailReason.AUDIO_GAP);
            return;
        }
        if (audio.isEmpty()) firstByte = startByte;
        audioStarted = true;
        audio.addLast(pcm.clone());
        nextByte = startByte + pcm.length;
        bufferedBytes += pcm.length;
    }

    public boolean failed() { return failed; }

    /** A delta is complete only when its entire corresponding audio has arrived. */
    public SegmentBuffer.Sealed poll(long providerGeneration, long deviceGeneration, Instant at, long nanos) {
        if (failed || audio.isEmpty()) return null;
        // OutputGeneration deliberately trims the instruction's old-audio margin. Keep its text in the first checked unit.
        while (!texts.isEmpty() && texts.getFirst().endByte() <= firstByte) {
            skippedText += texts.removeFirst().value();
        }
        Text text = texts.peekFirst();
        if (text == null || nextByte < text.endByte()) return null;
        int length = Math.toIntExact(text.endByte() - firstByte);
        int remaining = length;
        List<byte[]> chunks = new ArrayList<>();
        while (remaining > 0) {
            byte[] chunk = audio.removeFirst();
            int take = Math.min(remaining, chunk.length);
            chunks.add(Arrays.copyOf(chunk, take));
            if (take < chunk.length) audio.addFirst(Arrays.copyOfRange(chunk, take, chunk.length));
            remaining -= take;
        }
        firstByte = text.endByte();
        bufferedBytes -= length;
        texts.removeFirst();
        String transcript = skippedText + text.value();
        skippedText = "";
        textCharacters -= transcript.length();
        return new SegmentBuffer.Sealed(providerGeneration, transcript, chunks, length, deviceGeneration,
                text.at(), text.nanos(), at, nanos, false, true);
    }

    /**
     * 전사 먼저 방출용 단위: 검사를 받을 전사와 그 전사가 끝나는 PCM 위치. 음성은 들어 있지 않고, 검사를 통과하면 {@link #drainAudio}로 이 위치까지
     * 도착하는 대로 가져간다.
     *
     * @param sealed 전사만 든 단위(음성 조각 없음, 길이 0)
     * @param endByte 이 단위 전사가 끝나는 세션 PCM 누적 바이트 위치
     */
    private Text secondText() {
        var it = texts.iterator();
        it.next();
        return it.next();
    }

    /** 글이 어절 경계에서 끝나는가: 마지막 글자가 글자 · 숫자가 아니거나(공백 · 문장부호), 다음 조각이 글자 · 숫자로 시작하지 않는다. */
    static boolean atWordBoundary(String text, String next) {
        if (text.isEmpty()) return false;
        if (!Character.isLetterOrDigit(text.codePointBefore(text.length()))) return true;
        return next != null && !next.isEmpty() && !Character.isLetterOrDigit(next.codePointAt(0));
    }

    public record TextUnit(SegmentBuffer.Sealed sealed, long endByte) {
    }

    /**
     * 음성이 도착하길 기다리지 않고 맨 앞 전사 조각을 검사 단위로 낸다. 음성은 처음 한 조각이라도 도착한 뒤에야 낸다(그 뒤에는 앞 단위의 음성을 모두 내보낸 뒤에도 바로 낸다) — 지시 직후의 낡은 음성 여유 구간에
     * 통째로 든 전사를 걸러 다음 단위에 합치려면 음성이 시작하는 위치를 알아야 한다. 전사가 가리키는 음성은 아직 다 오지 않았을 수 있다.
     */
    public TextUnit pollText(long providerGeneration, long deviceGeneration, Instant at, long nanos) {
        if (failed || !audioStarted) return null;
        while (!texts.isEmpty() && texts.getFirst().endByte() <= firstByte) {
            skippedText += texts.removeFirst().value();
        }
        // 문장부호 · 공백만 든 조각(예: ",")은 따로 검사하지 않고 다음 조각과 합친다: 사전 검사가 빈 문장으로 폐기해 응답을 끊지 않게
        while (texts.size() > 1 && SeedDictionary.normalize(skippedText + texts.getFirst().value()).isEmpty()) {
            skippedText += texts.removeFirst().value();
        }
        Text text = texts.peekFirst();
        if (text == null || SeedDictionary.normalize(skippedText + text.value()).isEmpty()) return null;
        // 어절 단위로 낸다(2026-10-27): 공급자 전사 조각은 단어 중간에서 끊겨("개념" → "개" | "념") 조각 하나를 따로 검사하면 제브가 욕설로 오판한다.
        // 글이 어절 경계(공백 · 문장부호로 끝나거나 다음 조각이 공백 · 문장부호로 시작)에 닿을 때까지 다음 조각을 이어 붙이고, 아직 경계가 아니면
        // 다음 조각을 기다린다. 응답이 조용해지면 pollTrailingText가 남은 글을 낸다
        while (!atWordBoundary(skippedText + text.value(), texts.size() > 1 ? secondText().value() : null)
                && (skippedText + text.value()).length() < WORD_UNIT_MAX_CHARS) {
            if (texts.size() == 1) return null;
            Text first = texts.removeFirst();
            Text next = texts.removeFirst();
            text = new Text(first.value() + next.value(), next.endByte(), first.at(), first.nanos());
            texts.addFirst(text);
        }
        texts.removeFirst();
        String transcript = skippedText + text.value();
        skippedText = "";
        textCharacters -= transcript.length();
        SegmentBuffer.Sealed sealed = new SegmentBuffer.Sealed(providerGeneration, transcript, List.of(), 0, deviceGeneration,
                text.at(), text.nanos(), at, nanos, false, true);
        return new TextUnit(sealed, text.endByte());
    }

    /**
     * 응답이 조용해진 뒤 남은 마지막 전사가 구두점뿐(말끝 "?" 등)이면 그것을 단위로 낸다(FT-1023-e). {@link #pollText}는 구두점뿐인 조각을 다음
     * 조각과 합치려고 붙잡아 두는데, 응답 끝의 조각은 합칠 다음 조각이 없어 남는다 — 그대로 두면 말끝 음성이 나가지 않고 남은 전사 시한이 지나 출력 세대를
     * 올린다. 어절 경계를 기다리며 붙잡아 둔 마지막 낱말도 같은 까닭으로 여기서 낸다(2026-10-27). 보이지 않는 글자뿐인 조각은 꺼내지 않는다.
     */
    public TextUnit pollTrailingText(long providerGeneration, long deviceGeneration, Instant at, long nanos) {
        if (failed || !audioStarted) return null;
        while (!texts.isEmpty() && texts.getFirst().endByte() <= firstByte) {
            skippedText += texts.removeFirst().value();
        }
        if (texts.size() != 1) return null;
        Text text = texts.peekFirst();
        String transcript = skippedText + text.value();
        // 구두점뿐인 말끝(FT-1023-e)이나, 어절 경계를 기다리며 붙잡아 둔 마지막 낱말을 낸다. 보이지 않는 글자뿐인 조각은 내지 않는다
        if (!OutputGate.punctuationOnly(transcript) && SeedDictionary.normalize(transcript).isEmpty()) return null;
        texts.removeFirst();
        skippedText = "";
        textCharacters -= transcript.length();
        SegmentBuffer.Sealed sealed = new SegmentBuffer.Sealed(providerGeneration, transcript, List.of(), 0, deviceGeneration,
                text.at(), text.nanos(), at, nanos, false, true);
        return new TextUnit(sealed, text.endByte());
    }

    /**
     * 도착한 음성 중 {@code limitByte}까지를 순서대로 꺼낸다(통과한 단위의 끝 위치까지만 내보낸다). 아직 오지 않은 음성은 다음에 꺼낸다.
     *
     * @return 꺼낸 음성 조각들(없으면 빈 목록)
     */
    public List<byte[]> drainAudio(long limitByte) {
        List<byte[]> out = new ArrayList<>();
        if (failed) return out;
        while (!audio.isEmpty() && firstByte < limitByte) {
            byte[] chunk = audio.removeFirst();
            long room = limitByte - firstByte;
            int take = (int) Math.min(chunk.length, room);
            if (take <= 0) {
                audio.addFirst(chunk);
                break;
            }
            out.add(take == chunk.length ? chunk : Arrays.copyOf(chunk, take));
            if (take < chunk.length) audio.addFirst(Arrays.copyOfRange(chunk, take, chunk.length));
            firstByte += take;
            bufferedBytes -= take;
        }
        return out;
    }

    /** 아직 꺼내지 않은 전사 조각이 있는가(음성 시작을 기다리는 전사도 센다). */
    public boolean hasUnpolledText() { return !texts.isEmpty(); }

    public void reset() {
        texts.clear();
        audio.clear();
        firstByte = 0;
        nextByte = 0;
        lastEnd = -1;
        bufferedBytes = 0;
        textCharacters = 0;
        initialized = false;
        audioStarted = false;
        skippedText = "";
        failed = false;
        failReason = null;
    }
}
