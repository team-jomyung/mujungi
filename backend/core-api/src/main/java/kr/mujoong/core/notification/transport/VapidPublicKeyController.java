package kr.mujoong.core.notification.transport;

import com.fasterxml.jackson.annotation.JsonInclude;

import kr.mujoong.core.security.GuardianPrincipal;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VapidPublicKeyController {
    private final PushVapidSettings settings;

    public VapidPublicKeyController(PushVapidSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/api/push-subscription/vapid-public-key")
    public ResponseEntity<PublicKey> get(@AuthenticationPrincipal GuardianPrincipal guardian) {
        GuardianPrincipal.require(guardian);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PublicKey(settings.enabled(), settings.publicKey()));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PublicKey(boolean enabled, String publicKey) {}
}
