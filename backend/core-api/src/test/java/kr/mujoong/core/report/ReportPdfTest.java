package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.report.pdf.ReportPdfRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
@Tag("result-report") class ReportPdfTest {
    EventLogger events=mock(EventLogger.class);ReportPdfRenderer renderer=new ReportPdfRenderer(events);
    static ReportRepository.Report report(String name){var r=ReportTestData.report();return new ReportRepository.Report(r.id(),r.guardianId(),r.childId(),r.idempotencyKey(),r.requestHash(),"READY",r.periodFrom(),r.periodTo(),20,1,"PARTIAL",r.jobId(),name,7,r.createdAt(),r.createdAt(),null,null);}
    static List<ReportRepository.Section> sections(){List<ReportRepository.Section> result=new ArrayList<>();for(int no=1;no<=10;no++)result.add(new ReportRepository.Section(no,ReportAssembler.TITLES.get(no-1),no==10?ReportAssembler.LIMITATION:"함께 블록을 쌓았어요.",false,false,List.of(new ReportRepository.Citation(UUID.randomUUID(),1L,"같이 블록 쌓자",ReportTestData.NOW,"CHILD_RECALL"))));return result;}
    String text(byte[] bytes)throws Exception{try(var doc=PDDocument.load(bytes)){return new PDFTextStripper().getText(doc);}}
    @Test void extractedTextContainsEverySectionHeaderNoticeAndFooter()throws Exception{String text=text(renderer.render(report("하늘"),sections()));assertThat(text).contains("무중 상담용 리포트","하늘","7세",ReportService.NOTICE,"쪽 1/");for(String title:ReportAssembler.TITLES)assertThat(text).contains(title);}
    @Test void koreanQuotesAndSourceLabelsAreExtractable()throws Exception{assertThat(text(renderer.render(report("하늘"),sections()))).contains("같이 블록 쌓자","아이 회상").doesNotContain("�");}
    @Test void privateFieldsNeverAppearInPdf()throws Exception{assertThat(text(renderer.render(report("하늘"),sections()))).doesNotContain("rationale","functional","IMMEDIATE","관심사","성공률","판정 라벨");}
    @Test void pdfIsTextAndEscapesMarkupInChildName()throws Exception{byte[] bytes=renderer.render(report("하늘 <태그> & 친구"),sections());assertThat(new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");assertThat(text(bytes)).contains("하늘 <태그> & 친구");}
    /**
     * 공유 /tmp 전후 목록을 비교하면 같은 시각에 도는 다른 프로세스(병렬 모듈 시험의 Tomcat, Docker)가 만든 파일이 섞여 흔들렸다.
     * 새 JVM에 전용 임시 디렉터리를 주고 그 디렉터리가 비어 있는지 본다. 어떤 파일이든 생기면 실패하므로 단언은 그대로 엄격하다.
     */
    @Test void twentyActivityReportCreatesNoTemporaryFiles(@org.junit.jupiter.api.io.TempDir Path scratch)throws Exception{
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-XX:-UsePerfData","-Xmx768m","-Djava.io.tmpdir="+scratch,"-Duser.home="+scratch,
                "-cp",System.getProperty("java.class.path"),RenderTwentyActivity.class.getName()).redirectErrorStream(true).start();
        try{
            assertThat(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).as("새 JVM 렌더링 시한").isTrue();
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("U6_PDF elapsed_ms=");
            assertThat(output).doesNotContain("bytes=0");
            try(var files=Files.list(scratch)){assertThat(files.toList()).isEmpty();}
            System.out.println(output.lines().filter(line->line.startsWith("U6_PDF")).findFirst().orElse(""));
        }finally{process.destroyForcibly();}
    }

    /** 위 시험이 전용 임시 디렉터리 아래에서 돌리는 렌더링. 20 활동 리포트를 한 번 만든다. */
    public static class RenderTwentyActivity{
        public static void main(String[] args)throws Exception{
            long started=System.nanoTime();
            byte[] pdf=new ReportPdfRenderer(null).render(report("하늘"),sections());
            System.out.println("U6_PDF elapsed_ms="+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+" bytes="+pdf.length);
        }
    }
    @Test void rendererFailureHasFixedErrorAndCodeOnlyLog(){assertThatThrownBy(()->renderer.render(report("비밀 이름"),null)).isInstanceOf(CoreApiException.class);verify(events).appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"ReportPdfRenderingFailed");}
}
