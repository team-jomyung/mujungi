package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("knowledge-graph")
class SeedArchiveLoaderTest {
    static final String PREFIX = "FINAL_CLOSURE/04_GENERATION/generator/";
    static Path archive() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path file = current.resolve("aidlc/spaces/default/knowledge/documents/social-comm-poc.zip");
            if (Files.isRegularFile(file)) return file;
            current = current.getParent();
        }
        throw new IllegalStateException("Seed archive not found");
    }
    static Map<String, String> sources() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (var zip = new ZipFile(archive().toFile(), java.nio.charset.Charset.forName("CP437"))) {
            for (String name : new String[]{"skill_family_seeds.json", "prompts.py", "variation_plan.py"}) {
                files.put(PREFIX + name, new String(zip.getInputStream(zip.getEntry(PREFIX + name)).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
    static byte[] zipped(Map<String, String> files) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    static SeedCatalog load(Map<String, String> files) throws Exception {
        return new SeedArchiveLoader().load(new ByteArrayInputStream(zipped(files)), "poc-v1");
    }
    @Test void loadsAllFourteenAndMapsAdultContext() throws Exception {
        SeedCatalog catalog;
        try (var input = Files.newInputStream(archive())) {
            catalog = new SeedArchiveLoader().load(input, "poc-v1");
        }
        assertThat(catalog.seeds()).hasSize(14);
        assertThat(catalog.seeds().stream().filter(s -> s.seedId().equals("seed:X2")).findFirst().orElseThrow().relationship()).isEqualTo("CLASSMATE");
        assertThat(catalog.seeds()).allSatisfy(s -> assertThat(s.preservationRule()).isNotBlank());
    }
    @Test void identicalPrefixedDuplicatesHaveSameDigest() throws Exception {
        var files = sources();
        String digest = load(files).digest();
        new LinkedHashMap<>(files).forEach((key, value) -> files.put("social-comm-poc/" + key, value));
        assertThat(load(files).digest()).isEqualTo(digest);
    }
    @Test void rejectsConflictingDuplicate() throws Exception {
        var files = sources();
        files.put("social-comm-poc/" + PREFIX + "prompts.py", "different");
        assertThatThrownBy(() -> load(files)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsPathEscape() throws Exception {
        var files = sources();
        files.put("../escape", "bad");
        assertThatThrownBy(() -> load(files)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsAbsolutePath() throws Exception {
        var files = sources();
        files.put("C:/escape", "bad");
        assertThatThrownBy(() -> load(files)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsOversizedSelectedSource() throws Exception {
        var files = sources();
        files.put(PREFIX + "prompts.py", "x".repeat(2 * 1024 * 1024 + 1));
        assertThatThrownBy(() -> load(files)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMissingAndUnknownVocabulary() throws Exception {
        var files = sources();
        files.put(PREFIX + "skill_family_seeds.json", files.get(PREFIX + "skill_family_seeds.json").replace("FRIEND_CASUAL", "STRANGER"));
        assertThatThrownBy(() -> load(files)).isInstanceOf(IllegalArgumentException.class);
        files.remove(PREFIX + "prompts.py");
        assertThatThrownBy(() -> load(files)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preservesDifferentSkillsWithEqualSignatures() throws Exception {
        var seeds = load(sources()).seeds();
        var s4 = seeds.stream().filter(s -> s.seedId().equals("seed:S4")).findFirst().orElseThrow();
        var s5 = seeds.stream().filter(s -> s.seedId().equals("seed:S5")).findFirst().orElseThrow();
        assertThat(s4.signature()).isEqualTo(s5.signature());
        assertThat(s4.family()).isNotEqualTo(s5.family());
    }
}
