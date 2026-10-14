package kr.mujoong.core.report;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kr.mujoong.core.report.pdf.ReportPdfRenderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NFR12: 미리 데운 PDFBox 캐시가 결함을 가리지 않게 매번 새 JVM에서 검사한다. */
@Tag("u6")
class ReportPdfColdStartTest {
    @Test
    void firstRenderWritesNeitherFontCacheNorTemporaryPdf(@TempDir Path scratch) throws Exception {
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:-UsePerfData", "-Xmx768m", "-Djava.io.tmpdir=" + scratch,
                "-Duser.home=" + scratch, "-cp", System.getProperty("java.class.path"),
                ColdStart.class.getName()).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("새 JVM 렌더링 시한").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("COLD_PDF_OK");
            try (var paths = Files.list(scratch)) { assertThat(paths.toList()).isEmpty(); }
        } finally {
            process.destroyForcibly();
        }
    }

    public static class ColdStart {
        public static void main(String[] args) throws Exception {
            var r = ReportTestData.report();
            var ready = new ReportRepository.Report(r.id(), r.guardianId(), r.childId(),
                    r.idempotencyKey(), r.requestHash(), "READY", r.periodFrom(), r.periodTo(),
                    20, 0, "PARTIAL", r.jobId(), "하늘", 7, r.createdAt(), r.createdAt(), null, null);
            byte[] pdf = new ReportPdfRenderer(null).render(ready, List.of(
                    new ReportRepository.Section(1, "활동 개요", "함께 블록을 쌓았어요.", false, false, List.of())));
            try (var document = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                if (!text.contains("함께 블록을 쌓았어요.") || text.contains("�"))
                    throw new AssertionError("한글 텍스트 추출 실패");
            }
            System.out.println("COLD_PDF_OK");
        }
    }
}
