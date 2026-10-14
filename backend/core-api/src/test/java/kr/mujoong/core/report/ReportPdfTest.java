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
@Tag("u6") class ReportPdfTest {
    EventLogger events=mock(EventLogger.class);ReportPdfRenderer renderer=new ReportPdfRenderer(events);
    ReportRepository.Report report(String name){var r=ReportTestData.report();return new ReportRepository.Report(r.id(),r.guardianId(),r.childId(),r.idempotencyKey(),r.requestHash(),"READY",r.periodFrom(),r.periodTo(),20,1,"PARTIAL",r.jobId(),name,7,r.createdAt(),r.createdAt(),null,null);}
    List<ReportRepository.Section> sections(){List<ReportRepository.Section> result=new ArrayList<>();for(int no=1;no<=10;no++)result.add(new ReportRepository.Section(no,ReportAssembler.TITLES.get(no-1),no==10?ReportAssembler.LIMITATION:"함께 블록을 쌓았어요.",false,false,List.of(new ReportRepository.Citation(UUID.randomUUID(),1L,"같이 블록 쌓자",ReportTestData.NOW,"CHILD_RECALL"))));return result;}
    String text(byte[] bytes)throws Exception{try(var doc=PDDocument.load(bytes)){return new PDFTextStripper().getText(doc);}}
    @Test void extractedTextContainsEverySectionHeaderNoticeAndFooter()throws Exception{String text=text(renderer.render(report("하늘"),sections()));assertThat(text).contains("무중 상담용 리포트","하늘","7세",ReportService.NOTICE,"쪽 1/");for(String title:ReportAssembler.TITLES)assertThat(text).contains(title);}
    @Test void koreanQuotesAndSourceLabelsAreExtractable()throws Exception{assertThat(text(renderer.render(report("하늘"),sections()))).contains("같이 블록 쌓자","아이 회상").doesNotContain("�");}
    @Test void privateFieldsNeverAppearInPdf()throws Exception{assertThat(text(renderer.render(report("하늘"),sections()))).doesNotContain("rationale","functional","IMMEDIATE","관심사","성공률","판정 라벨");}
    @Test void pdfIsTextAndEscapesMarkupInChildName()throws Exception{byte[] bytes=renderer.render(report("하늘 <태그> & 친구"),sections());assertThat(new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");assertThat(text(bytes)).contains("하늘 <태그> & 친구");}
    @Test void twentyActivityReportCreatesNoTemporaryFiles()throws Exception{Set<Path> before;try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){before=files.collect(java.util.stream.Collectors.toSet());}long started=System.nanoTime();byte[] pdf=renderer.render(report("하늘"),sections());Set<Path> after;try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){after=files.collect(java.util.stream.Collectors.toSet());}after.removeAll(before);assertThat(after).isEmpty();assertThat(pdf).isNotEmpty();System.out.println("U6_PDF elapsed_ms="+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+" bytes="+pdf.length);}
    @Test void rendererFailureHasFixedErrorAndCodeOnlyLog(){assertThatThrownBy(()->renderer.render(report("비밀 이름"),null)).isInstanceOf(CoreApiException.class);verify(events).appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"ReportPdfRenderingFailed");}
}
