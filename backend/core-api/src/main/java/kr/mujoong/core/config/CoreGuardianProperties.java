package kr.mujoong.core.config;

import static kr.mujoong.core.config.CoreProperties.positive;
import static kr.mujoong.core.config.CoreProperties.required;
import static kr.mujoong.core.config.CoreProperties.secureUrl;

import java.net.URI;
import java.time.Duration;

/** U2 보호자 계정 설정(구글 OAuth, U2-J1). */
public record CoreGuardianProperties(Google google) {
    public CoreGuardianProperties {
        required(google, "mujoong.guardian.google");
    }

    /**
     * 구글 OAuth 클라이언트(U2-J1 · U2-J2). client-id · client-secret · redirect-uri는 환경 변수 {@code GOOGLE_OAUTH_CLIENT_ID} ·
     * {@code GOOGLE_OAUTH_CLIENT_SECRET} · {@code GOOGLE_OAUTH_REDIRECT_URI}에서만 채우고 비어 있어도 기동은 막지 않는다(로그인이 401).
     * 토큰 엔드포인트와 서명 열쇠(JWKS) 주소는 https만 받고 평문 http는 루프백 시험 주소만 받는다 — 어긋나면 기동을 거부한다.
     * 문자열 표현에 비밀 값을 내지 않는다.
     */
    public record Google(
            String clientId, String clientSecret, String redirectUri, URI tokenUri, URI jwksUri, Duration exchangeTimeout) {

        public Google {
            clientId = clientId == null ? "" : clientId.trim();
            clientSecret = clientSecret == null ? "" : clientSecret;
            redirectUri = redirectUri == null ? "" : redirectUri.trim();
            secureUrl(tokenUri, "mujoong.guardian.google.token-uri");
            secureUrl(jwksUri, "mujoong.guardian.google.jwks-uri");
            positive(exchangeTimeout, "mujoong.guardian.google.exchange-timeout");
        }

        /** 세 값이 모두 있어 로그인을 받을 수 있는가. */
        public boolean configured() {
            return !clientId.isEmpty() && !clientSecret.isEmpty() && !redirectUri.isEmpty();
        }

        @Override
        public String toString() {
            return "Google[clientId=" + (clientId.isEmpty() ? "<empty>" : "<set>")
                    + ", clientSecret=" + (clientSecret.isEmpty() ? "<empty>" : "<redacted>")
                    + ", redirectUri=" + (redirectUri.isEmpty() ? "<empty>" : "<set>")
                    + ", tokenUri=" + tokenUri + ", jwksUri=" + jwksUri + ", exchangeTimeout=" + exchangeTimeout + "]";
        }
    }
}
