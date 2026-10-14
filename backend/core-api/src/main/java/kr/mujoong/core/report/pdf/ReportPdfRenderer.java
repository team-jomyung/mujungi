package kr.mujoong.core.report.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.report.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** BR10.6·BR10.7: 클래스패스 글꼴과 메모리 스트림만 사용해 텍스트 PDF를 만든다. */
@Component
public class ReportPdfRenderer {
    private final EventLogger events;
    public ReportPdfRenderer(EventLogger events){this.events=events;EmbeddedFontMapper.install();}
    public byte[] render(ReportRepository.Report report,List<ReportRepository.Section> sections){
        if(!List.of("READY","SAVED").contains(report.status()))throw CoreApiException.of(ErrorCode.REPORT_NOT_READY);
        try(ByteArrayOutputStream output=new ByteArrayOutputStream()){
            String template;try(InputStream input=new ClassPathResource("report/template.html").getInputStream()){template=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
            String name=report.childDisplayName()==null?"아이":report.childDisplayName();StringBuilder body=new StringBuilder();
            DateTimeFormatter quoteTime=DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ReportService.ZONE);
            for(var section:sections){body.append("<div class=\"section\"><h2>").append(section.sectionNo()).append(". ").append(escape(section.title())).append("</h2><p>").append(escape(section.text())).append("</p>");
                for(var citation:section.citations()){
                    body.append("<p class=\"citation\">");if(citation.quote()!=null)body.append("&quot;").append(escape(citation.quote())).append("&quot;");if(citation.at()!=null)body.append(" (").append(quoteTime.format(citation.at())).append(")");
                    if(section.sectionNo()==5)body.append(" <span class=\"source\">").append(citation.source().equals("GUARDIAN_INPUT")?"보호자 입력":"아이 회상").append("</span>");body.append("</p>");
                }
                body.append("</div>");
            }
            String header=name+" · "+(report.childAge()==null?"나이 없음":report.childAge()+"세")+" · "+report.periodFrom().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))+" ~ "+report.periodTo().format(DateTimeFormatter.ofPattern("MM.dd"))+" · 만든 날 "+report.createdAt().atZone(ReportService.ZONE).format(DateTimeFormatter.ofPattern("MM.dd"));
            // 사용자 입력을 CSS 문자열로 끼우지 않는다. 작은따옴표·역슬래시 등은 CSS 코드점 이스케이프다.
            String html=template.replace("{{footerName}}",css(name)).replace("{{header}}",escape(header)).replace("{{notice}}",escape(ReportService.NOTICE)).replace("{{sections}}",body);
            PdfRendererBuilder builder=new PdfRendererBuilder();builder.useFastMode();
            builder.useFont(()->font("Regular"),"NotoKR",400,BaseRendererBuilder.FontStyle.NORMAL,true);
            builder.useFont(()->font("Bold"),"NotoKR",700,BaseRendererBuilder.FontStyle.NORMAL,true);
            builder.withHtmlContent(html,null);builder.toStream(output);builder.run();return output.toByteArray();
        }catch(Exception failure){events.appError(EventLog.Component.CORE_MAINTENANCE,"ReportPdfRenderingFailed");throw CoreApiException.of(ErrorCode.INTERNAL_ERROR);}
    }
    private static InputStream font(String weight){try{return new ClassPathResource("report/fonts/NotoSansKR-"+weight+".ttf").getInputStream();}catch(IOException failure){throw new UncheckedIOException(failure);}}
    private static String css(String value){StringBuilder result=new StringBuilder();value.codePoints().forEach(c->result.append('\\').append(Integer.toHexString(c)).append(' '));return result.toString();}
    private static String escape(String value){if(value==null)return "";return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
