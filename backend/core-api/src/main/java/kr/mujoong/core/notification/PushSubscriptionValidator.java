package kr.mujoong.core.notification;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;

/** 구독 입력의 형식 검증. DNS·외부 요청은 하지 않으며 전송 경계에서 공인 목적지를 다시 검증한다. */
public final class PushSubscriptionValidator {
    /** UNIQUE btree 키 크기를 넘지 않도록 주소를 ASCII 2KiB 안으로 제한한다(U7-J13). */
    public static final int MAX_ENDPOINT_LENGTH = 2048;
    private static final ECParameterSpec CURVE = curve();

    private PushSubscriptionValidator() {
    }

    public static void requireValid(PushSubscriptionRequest request) {
        if (request == null || request.keys() == null || request.platform() == null
                || request.platform().isBlank() || request.platform().length() > 40) {
            throw invalid();
        }
        requireEndpoint(request.endpoint());
        byte[] point = decode(request.keys().p256dh(), 65);
        decode(request.keys().auth(), 16);
        if (point[0] != 4) {
            throw invalid();
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        BigInteger prime = ((ECFieldFp) CURVE.getCurve().getField()).getP();
        if (x.compareTo(prime) >= 0 || y.compareTo(prime) >= 0
                || !y.multiply(y).mod(prime).equals(x.multiply(x).multiply(x)
                        .add(CURVE.getCurve().getA().multiply(x)).add(CURVE.getCurve().getB()).mod(prime))) {
            throw invalid();
        }
    }

    public static void requireEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_ENDPOINT_LENGTH
                || endpoint.chars().anyMatch(character -> character > 127 || Character.isISOControl(character))) {
            throw invalid();
        }
        try {
            URI uri = new URI(endpoint);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw invalid();
            }
        } catch (URISyntaxException e) {
            throw invalid();
        }
    }

    private static byte[] decode(String value, int requiredLength) {
        if (value == null || value.length() > ((requiredLength + 2) / 3) * 4
                || !value.matches("[A-Za-z0-9_-]+={0,2}")) {
            throw invalid();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != requiredLength) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ECParameterSpec curve() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 검증을 준비하지 못했다");
        }
    }

    private static CoreApiException invalid() {
        return CoreApiException.of(ErrorCode.VALIDATION_FAILED);
    }
}
