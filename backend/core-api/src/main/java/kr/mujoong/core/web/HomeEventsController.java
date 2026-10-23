package kr.mujoong.core.web;

import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.security.GuardianPrincipal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Same authenticated guardian/active-account boundary as GET /api/home. Identity is never client-selected. */
@RestController
public class HomeEventsController {
    private final GuardianRepository guardians;
    private final HomeEvents events;
    public HomeEventsController(GuardianRepository guardians, HomeEvents events) {
        this.guardians = guardians;
        this.events = events;
    }
    @GetMapping(value = "/api/home/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> events(@AuthenticationPrincipal GuardianPrincipal guardian) {
        var id = GuardianPrincipal.requireActive(guardian, guardians);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .body(events.subscribe(id));
    }
}
