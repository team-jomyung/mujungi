package kr.mujoong.core.report.web;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.report.*;
import kr.mujoong.core.report.pdf.ReportPdfRenderer;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** C5·BR8.4·BR10: 인증된 보호자 소유 리포트만 공개 DTO 또는 PDF로 반환한다. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService service;private final ReportRepository reports;private final ReportPdfRenderer pdf;private final GuardianRepository guardians;
    public ReportController(ReportService service,ReportRepository reports,ReportPdfRenderer pdf,GuardianRepository guardians){this.service=service;this.reports=reports;this.pdf=pdf;this.guardians=guardians;}
    @GetMapping("/preview") public ResponseEntity<ReportService.Preview> preview(@AuthenticationPrincipal GuardianPrincipal principal,@RequestParam String from,@RequestParam String to){return ApiResponses.json(200,service.preview(owner(principal),date(from),date(to)));}
    public record CreateBody(String from,String to,List<String> sessionIds,String idempotencyKey){}
    @PostMapping public ResponseEntity<ReportService.Created> create(@AuthenticationPrincipal GuardianPrincipal principal,@RequestBody CreateBody body){UUID owner=owner(principal);if(body.sessionIds()==null)throw invalid();var sessions=body.sessionIds().stream().map(s->ApiResponses.uuid(s).orElseThrow(ReportController::invalid)).toList();return ApiResponses.json(202,service.create(owner,date(body.from()),date(body.to()),sessions,body.idempotencyKey()));}
    @GetMapping("/{reportId}") public ResponseEntity<View> get(@AuthenticationPrincipal GuardianPrincipal principal,@PathVariable String reportId){var report=service.owned(owner(principal),id(reportId));var sections=List.of("READY","SAVED").contains(report.status())?reports.sections(report.id()).stream().map(s->new Section(s.sectionNo(),s.title(),s.text(),s.missing(),s.citations())).toList():List.<Section>of();return ApiResponses.json(200,new View(report.status(),report.level(),report.periodFrom(),report.periodTo(),report.activityCount(),report.citationCount(),new Child(report.childDisplayName(),report.childAge()),ReportService.NOTICE,sections));}
    @GetMapping("/{reportId}/pdf") public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal GuardianPrincipal principal,@PathVariable String reportId){var report=service.owned(owner(principal),id(reportId));byte[] bytes=pdf.render(report,reports.sections(report.id()));var format=DateTimeFormatter.ofPattern("MMdd");String filename="무중_상담용리포트_"+(report.childDisplayName()==null?"아이":report.childDisplayName())+"_"+format.format(report.periodFrom())+"-"+format.format(report.periodTo())+".pdf";return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+URLEncoder.encode(filename,StandardCharsets.UTF_8).replace("+","%20")).body(bytes);}
    @PostMapping("/{reportId}/saved") public ResponseEntity<Void> saved(@AuthenticationPrincipal GuardianPrincipal principal,@PathVariable String reportId){service.saved(owner(principal),id(reportId));return ResponseEntity.noContent().build();}
    @PostMapping("/{reportId}/discard") public ResponseEntity<Void> discard(@AuthenticationPrincipal GuardianPrincipal principal,@PathVariable String reportId){service.discard(owner(principal),id(reportId));return ResponseEntity.noContent().build();}
    public record Child(String displayName,Integer age){}
    public record Section(int sectionNo,String title,String text,boolean missing,List<ReportRepository.Citation> citations){}
    public record View(String status,String level,LocalDate periodFrom,LocalDate periodTo,int activityCount,int citationCount,Child child,String notice,List<Section> sections){}
    private UUID owner(GuardianPrincipal principal){return GuardianPrincipal.requireActive(principal,guardians);}
    private UUID id(String id){return ApiResponses.uuid(id).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));}
    private LocalDate date(String value){try{if(value==null||!value.matches("\\d{4}-\\d{2}-\\d{2}"))throw invalid();return LocalDate.parse(value);}catch(java.time.format.DateTimeParseException failure){throw invalid();}}
    private static CoreApiException invalid(){return CoreApiException.of(ErrorCode.VALIDATION_FAILED);}
}
