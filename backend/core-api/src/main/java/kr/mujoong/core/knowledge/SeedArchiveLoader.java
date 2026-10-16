package kr.mujoong.core.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.secret.SecretCompare;

/** BR2: 원본을 실행하거나 압축을 풀지 않고 허용된 세 자료만 읽는다. */
public final class SeedArchiveLoader {
    private static final String PREFIX = "FINAL_CLOSURE/04_GENERATION/generator/";
    private static final Set<String> REQUIRED = Set.of("skill_family_seeds.json", "prompts.py", "variation_plan.py");
    private static final long MAX_TOTAL = 64L * 1024 * 1024;
    private static final int MAX_SELECTED = 2 * 1024 * 1024;
    private final JsonCodec json = JsonCodec.shared();

    public SeedCatalog load(InputStream input, String version) {
        Map<String, String> files = read(input);
        if (!files.keySet().equals(REQUIRED)) throw new IllegalArgumentException("Missing seed source");
        var rules = familyRules(files.get("prompts.py"));
        Map<?, ?> root = json.read(files.get("skill_family_seeds.json"), Map.class);
        if (!(root.get("seeds") instanceof List<?> rows)) throw new IllegalArgumentException("Missing seeds");
        List<KnowledgeSeed> seeds = rows.stream().map(row -> seed((Map<?, ?>) row, rules)).toList();
        Set<String> ids = new HashSet<>();
        for (int i = 1; i <= 10; i++) ids.add("seed:S" + i);
        for (int i = 1; i <= 4; i++) ids.add("seed:X" + i);
        if (seeds.size() != 14 || !ids.equals(seeds.stream().map(KnowledgeSeed::seedId).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("Expected fourteen unique base seeds");
        }
        String canonical = REQUIRED.stream().sorted().map(name -> name + "\n" + files.get(name)).collect(java.util.stream.Collectors.joining("\n"));
        return new SeedCatalog(version, SecretCompare.sha256Hex(canonical), seeds);
    }

    private Map<String, String> read(InputStream input) {
        Map<String, String> files = new HashMap<>();
        long total = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(input, java.nio.charset.Charset.forName("CP437"))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10000) throw new IllegalArgumentException("Too many archive entries");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.matches("^[A-Za-z]:.*") || List.of(name.split("/")).contains("..")) {
                    throw new IllegalArgumentException("Unsafe archive path");
                }
                String relative = name.startsWith("social-comm-poc/") ? name.substring("social-comm-poc/".length()) : name;
                String selected = relative.startsWith(PREFIX) ? relative.substring(PREFIX.length()) : "";
                boolean keep = REQUIRED.contains(selected);
                var bytes = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_TOTAL || (keep && bytes.size() + n > MAX_SELECTED)) {
                        throw new IllegalArgumentException("Archive size limit");
                    }
                    if (keep) bytes.write(buffer, 0, n);
                }
                if (keep) {
                    String value = bytes.toString(StandardCharsets.UTF_8);
                    String previous = files.putIfAbsent(selected, value);
                    if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("Conflicting archive duplicate");
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unreadable seed archive");
        }
        return files;
    }

    private Map<String, String> familyRules(String source) {
        Map<String, String> rules = new HashMap<>();
        var pairs = Pattern.compile("'([a-z_]+)'\\s*:\\s*\\((.*?)\\),", Pattern.DOTALL).matcher(source);
        while (pairs.find()) {
            var strings = Pattern.compile("'([^']*)'").matcher(pairs.group(2));
            StringBuilder rule = new StringBuilder();
            while (strings.find()) rule.append(strings.group(1));
            if (!rule.isEmpty()) rules.put(pairs.group(1), rule.toString());
        }
        if (rules.size() != 7) throw new IllegalArgumentException("Invalid preservation rules");
        return rules;
    }

    private KnowledgeSeed seed(Map<?, ?> row, Map<String, String> rules) {
        Map<?, ?> core = (Map<?, ?>) row.get("core");
        Map<?, ?> context = (Map<?, ?>) row.get("base_context");
        if (!"LOW".equals(context.get("misread_cost")) || !Boolean.TRUE.equals(context.get("recoverable"))) {
            throw new IllegalArgumentException("Unsafe seed context");
        }
        String family = text(row, "skill_family");
        if (!rules.containsKey(family)) throw new IllegalArgumentException("Unknown skill family");
        String relationship = switch (text(context, "relationship_type")) {
            case "COWORKER" -> "CLASSMATE";
            case "SUPERVISOR" -> "TEACHER";
            default -> text(context, "relationship_type");
        };
        String situation = text(context, "situation_type");
        if ("WORKPLACE".equals(situation)) situation = "SCHOOL_GROUP";
        return new KnowledgeSeed("seed:" + text(row, "id"), KnowledgeSeed.Origin.POC, text(row, "label"),
                new StructureSignature(text(core, "target_skill"), text(core, "required_inference"),
                        text(core, "perspective_structure"), text(core, "decidable_by_evidence"), text(core, "direct_check_allowed")),
                text(row, "invariant_sentence"), family, rules.get(family), relationship, situation, true);
    }

    private static String text(Map<?, ?> map, String key) {
        if (map == null || !(map.get(key) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing seed field");
        }
        return text;
    }
}
