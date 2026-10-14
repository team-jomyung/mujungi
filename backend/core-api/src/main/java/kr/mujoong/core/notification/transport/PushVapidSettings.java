package kr.mujoong.core.notification.transport;

import com.the13haven.push2u.LocalEcVapidSigner;
import com.the13haven.push2u.VapidKeys;

import org.springframework.core.env.Environment;

/** 비밀은 전용 환경 변수 이름으로만 읽고 공통 설정 트리에 바인딩하지 않는다. */
public final class PushVapidSettings {
    private final VapidKeys keys;
    private final String publicKey;
    private final String subject;

    private PushVapidSettings(VapidKeys keys, String publicKey, String subject) {
        this.keys = keys;
        this.publicKey = publicKey;
        this.subject = subject;
    }

    public static PushVapidSettings load(Environment environment) {
        String pub = environment.getProperty("MUJOONG_PUSH_VAPID_PUBLIC_KEY");
        String priv = environment.getProperty("MUJOONG_PUSH_VAPID_PRIVATE_KEY");
        String subject = environment.getProperty("MUJOONG_PUSH_VAPID_SUBJECT");
        if (blank(pub) && blank(priv) && blank(subject))
            return new PushVapidSettings(null, null, null);
        try {
            if (blank(pub)
                    || blank(priv)
                    || blank(subject)
                    || !subject.matches(
                            "mailto:[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}")) {
                throw new IllegalArgumentException();
            }
            VapidKeys keys = VapidKeys.fromBase64(pub, priv);
            new LocalEcVapidSigner(keys); // 서명 검증으로 공개 키와 비밀 키가 한 쌍인지 확인한다.
            return new PushVapidSettings(
                    keys, VapidKeys.encodePublicKey(keys.publicKey()), subject);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Web Push VAPID 설정 오류: MUJOONG_PUSH_VAPID_PUBLIC_KEY,"
                        + " MUJOONG_PUSH_VAPID_PRIVATE_KEY, MUJOONG_PUSH_VAPID_SUBJECT");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public boolean enabled() {
        return keys != null;
    }

    public String publicKey() {
        return publicKey;
    }

    VapidKeys keys() {
        return keys;
    }

    String subject() {
        return subject;
    }

    @Override
    public String toString() {
        return "PushVapidSettings[enabled=" + enabled() + ", credentials=REDACTED]";
    }
}
