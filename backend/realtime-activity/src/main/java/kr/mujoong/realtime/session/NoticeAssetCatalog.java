package kr.mujoong.realtime.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.ws.message.StartSession;
import org.springframework.stereotype.Component;

/** 실제 말소리 검수 판과 앱 포함 파일 바이트를 묶는다. 운영 승인 행만으로 검수를 추정하지 않는다. */
@Component
public final class NoticeAssetCatalog {
    public static final String RESOURCE = "service-policy/notice-assets.json";
    public enum Verification { PLACEHOLDER, SPOKEN_VERIFIED }

    public record Asset(UUID contentVersionId, String contentCode, String audioUrl, String resource,
            String textSha256, String audioSha256, long frames, Verification verification, String reviewVersion,
            String policyVersion, boolean includesParticipationQuestion) {
        public Asset {
            Objects.requireNonNull(contentVersionId, "contentVersionId");
            Objects.requireNonNull(contentCode, "contentCode");
            Objects.requireNonNull(verification, "verification");
            if (audioUrl == null || !audioUrl.matches("/media/approved/[A-Za-z0-9._-]+\\.wav")
                    || resource == null || !resource.matches("approved-audio/[A-Za-z0-9._-]+\\.wav")
                    || textSha256 == null || !textSha256.matches("[a-f0-9]{64}")
                    || audioSha256 == null || !audioSha256.matches("[a-f0-9]{64}") || frames <= 0
                    || policyVersion == null || policyVersion.isBlank()) {
                throw new IllegalArgumentException("안내 음성 메타데이터가 올바르지 않다");
            }
        }
    }

    public record Manifest(List<Asset> assets) { }
    private record Loaded(Asset asset, byte[] audio) { }
    private final Map<UUID, Loaded> assets;

    public NoticeAssetCatalog() {
        this(loadManifest(), NoticeAssetCatalog::resource);
    }

    /** 시험은 합성 파일/검수 메타데이터를 명시적으로 주입한다. 운영 manifest는 변경하지 않는다. */
    public NoticeAssetCatalog(List<Asset> definitions, Function<String, byte[]> resources) {
        Map<UUID, Loaded> loaded = new HashMap<>();
        for (Asset asset : definitions) {
            byte[] bytes = Objects.requireNonNull(resources.apply(asset.resource()), "audio bytes").clone();
            if (!sha256(bytes).equals(asset.audioSha256()) || loaded.putIfAbsent(asset.contentVersionId(),
                    new Loaded(asset, bytes)) != null) {
                throw new IllegalArgumentException("안내 파일의 해시/판이 일치하지 않는다");
            }
        }
        assets = Map.copyOf(loaded);
    }

    public Optional<Asset> verified(SessionExecutor.ApprovedItem item, List<StartSession.ApprovedAudio> current,
            String policyVersion, Duration maxDuration) {
        if (item == null || item.text() == null) return Optional.empty();
        Loaded loaded = assets.get(item.contentVersionId());
        if (loaded == null) return Optional.empty();
        Asset asset = loaded.asset();
        boolean matching = asset.verification() == Verification.SPOKEN_VERIFIED
                && asset.reviewVersion() != null && !asset.reviewVersion().isBlank()
                && asset.policyVersion().equals(policyVersion) && asset.contentCode().equals(item.contentCode())
                && asset.textSha256().equals(sha256(item.text().getBytes(StandardCharsets.UTF_8)))
                && current.stream().anyMatch(audio -> audio.contentVersionId().equals(asset.contentVersionId())
                    && audio.contentCode().equals(asset.contentCode()) && audio.audioUrl().equals(asset.audioUrl()));
        return matching && validAudio(loaded, maxDuration) ? Optional.of(asset) : Optional.empty();
    }

    private static boolean validAudio(Loaded loaded, Duration maxDuration) {
        try (var audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(loaded.audio()))) {
            AudioFormat format = audio.getFormat();
            long frames = audio.getFrameLength();
            return AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) && format.getChannels() == 1
                    && format.getSampleSizeInBits() == 16 && !format.isBigEndian() && format.getSampleRate() == 24_000
                    && frames == loaded.asset().frames() && frames * 1_000L <= maxDuration.toMillis() * 24_000L
                    && audio.readAllBytes().length == frames * format.getFrameSize();
        } catch (IOException | javax.sound.sampled.UnsupportedAudioFileException invalid) {
            return false;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("필수 SHA-256 알고리즘이 없다", impossible);
        }
    }

    private static List<Asset> loadManifest() {
        Manifest manifest = JsonCodec.shared().read(new String(resource(RESOURCE), StandardCharsets.UTF_8), Manifest.class);
        return List.copyOf(Objects.requireNonNull(manifest.assets(), "assets"));
    }

    private static byte[] resource(String path) {
        try (InputStream in = NoticeAssetCatalog.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("승인 안내 자원이 없다");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("승인 안내 자원 읽기 실패", e);
        }
    }
}
