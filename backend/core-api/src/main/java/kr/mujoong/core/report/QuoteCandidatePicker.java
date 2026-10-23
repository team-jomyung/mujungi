package kr.mujoong.core.report;

import static kr.mujoong.core.result.ResultData.*;

import java.time.Instant;
import java.util.*;
import kr.mujoong.core.result.ResultData;
import org.springframework.stereotype.Component;

/** BR9.7: 최근 활동부터 의미 있는 확정 아이 발화를 우선하여 활동당 5개 · 항목당 30개. */
@Component
public class QuoteCandidatePicker {
    public record Quote(int activityNo, UUID sessionId, long utteranceSeq, String quote, Instant at, String source) {
        public ReportRepository.Citation citation() {
            return new ReportRepository.Citation(sessionId, utteranceSeq, quote, at, source);
        }

        @Override
        public String toString() {
            return "Quote[activityNo=" + activityNo + ", utteranceSeq=" + utteranceSeq + "]";
        }
    }

    public List<Quote> pick(List<ReportInput> inputs) {
        List<Quote> result = new ArrayList<>();
        var ordered = inputs.stream()
                .filter(ReportInput::contentAllowed)
                .sorted(Comparator.comparing((ReportInput i) -> i.record().listedAt()).reversed().thenComparingInt(ReportInput::activityNo))
                .toList();
        for (var input : ordered) {
            var utterances = transcript(input.record().transcriptJson()).stream()
                    .filter(u -> "CHILD".equals(u.get("speaker")) && !Boolean.TRUE.equals(u.get("uncertain")))
                    .sorted(Comparator.comparing((Map<String, Object> u) -> !ResultData.meaningful(u)).thenComparingLong(u -> number(u, "seq")))
                    .limit(5)
                    .toList();
            for (var u : utterances) {
                if (result.size() == 30) {
                    return List.copyOf(result);
                }
                result.add(quote(input, u, "TRANSCRIPT"));
            }
        }
        return List.copyOf(result);
    }

    /** 저장 전사의 발화 한 줄을 인용으로 바꾼다. 발화 시각을 읽을 수 없으면 활동 목록 시각을 쓴다. */
    static Quote quote(ReportInput input, Map<String, Object> utterance, String source) {
        return new Quote(input.activityNo(), input.record().sessionId(), number(utterance, "seq"), text(utterance, "text"),
                at(utterance, input.record().listedAt()), source);
    }

    private static Instant at(Map<String, Object> u, Instant fallback) {
        try {
            return Instant.parse(text(u, "startedAt"));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
