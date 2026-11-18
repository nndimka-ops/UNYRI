import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/** Run from the repository root with JDK 17:
 * java android/tools/GenerateDisplayNameUnicode.java
 *
 * Downloads only missing official Unicode 17 inputs into android/build/, checks
 * every SHA-256, and deterministically regenerates the policy and test resources.
 * No Gradle task or runtime network dependency is introduced.
 */
public class GenerateDisplayNameUnicode {
    private static final Path CACHE = Path.of("android/build/unicode17-source");
    private static final Path MAIN = Path.of("android/app/src/main/resources/unicode");
    private static final Path TEST = Path.of("android/app/src/test/resources/unicode");
    private static final Map<String, String> INPUTS = Map.of(
        "UnicodeData.txt", "2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c",
        "DerivedCoreProperties.txt", "24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08",
        "DerivedNormalizationProps.txt", "71fd6a206a2c0cdd41feb6b7f656aa31091db45e9cedc926985d718397f9e488",
        "SpecialCasing.txt", "efc25faf19de21b92c1194c111c932e03d2a5eaf18194e33f1156e96de4c9588",
        "PropList.txt", "130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd",
        "NormalizationTest.txt", "5019ffd530751a741900c849c0e010332f142a3612234639bd200b82138a87db",
        "LICENSE.txt", "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96"
    );

    private record Decomposition(boolean compatibility, int[] scalars) {}

    public static void main(String[] args) throws Exception {
        Files.createDirectories(CACHE);
        Files.createDirectories(MAIN);
        Files.createDirectories(TEST);
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (var entry : new TreeMap<>(INPUTS).entrySet()) {
            var path = CACHE.resolve(entry.getKey());
            if (!Files.exists(path)) {
                var url = entry.getKey().equals("LICENSE.txt") ? "https://www.unicode.org/license.txt"
                    : "https://www.unicode.org/Public/17.0.0/ucd/" + entry.getKey();
                var response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + entry.getKey());
                Files.write(path, response.body());
            }
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            if (!actual.equals(entry.getValue())) throw new IOException("SHA-256 mismatch: " + entry.getKey());
        }

        var marks = new TreeSet<Integer>();
        var numeric = new TreeSet<Integer>();
        var combiningClasses = new TreeMap<Integer, Integer>();
        var lowercase = new TreeMap<Integer, int[]>();
        var decompositions = new TreeMap<Integer, Decomposition>();
        String[] rangeStart = null;
        for (var line : Files.readAllLines(CACHE.resolve("UnicodeData.txt"), StandardCharsets.UTF_8)) {
            var fields = line.split(";", -1);
            int scalar = hex(fields[0]);
            if (fields[1].endsWith(", First>")) { rangeStart = fields; continue; }
            int first = scalar;
            if (fields[1].endsWith(", Last>")) {
                if (rangeStart == null) throw new IllegalStateException("Unpaired UnicodeData range");
                first = hex(rangeStart[0]);
                rangeStart = null;
            }
            for (int cp = first; cp <= scalar; cp++) {
                if (fields[2].startsWith("M")) marks.add(cp);
                if (fields[2].startsWith("N")) numeric.add(cp);
                int ccc = Integer.parseInt(fields[3]);
                if (ccc != 0) combiningClasses.put(cp, ccc);
            }
            if (!fields[13].isEmpty()) lowercase.put(scalar, new int[] {hex(fields[13])});
            if (!fields[5].isEmpty()) {
                boolean compatibility = fields[5].startsWith("<");
                var mapping = compatibility ? fields[5].substring(fields[5].indexOf('>') + 1).trim() : fields[5];
                decompositions.put(scalar, new Decomposition(compatibility, scalars(mapping)));
            }
        }
        for (var line : Files.readAllLines(CACHE.resolve("SpecialCasing.txt"), StandardCharsets.UTF_8)) {
            var body = line.split("#", 2)[0].trim();
            if (body.isEmpty()) continue;
            var fields = body.split(";", -1);
            // Rust char::to_lowercase applies scalar mappings, never contextual casing.
            if (fields[4].trim().isEmpty()) lowercase.put(hex(fields[0].trim()), scalars(fields[1].trim()));
        }
        lowercase.entrySet().removeIf(entry -> entry.getValue().length == 1 && entry.getValue()[0] == entry.getKey());
        var alphabetic = property("DerivedCoreProperties.txt", "Alphabetic");
        var whitespace = property("PropList.txt", "White_Space");
        var exclusions = property("DerivedNormalizationProps.txt", "Full_Composition_Exclusion");
        var compositions = new TreeMap<Long, Integer>();
        for (var entry : decompositions.entrySet()) {
            var mapping = entry.getValue();
            if (!mapping.compatibility && mapping.scalars.length == 2 && !exclusions.contains(entry.getKey())) {
                long pair = ((long) mapping.scalars[0] << 21) | mapping.scalars[1];
                if (compositions.put(pair, entry.getKey()) != null) throw new IllegalStateException("Duplicate composition");
            }
        }

        var numericText = new StringBuilder("# Unicode 17 General_Category Nd/Nl/No; generated by android/tools/GenerateDisplayNameUnicode.java\n");
        Integer firstNumeric = null, lastNumeric = null;
        for (int cp : numeric) {
            if (lastNumeric != null && cp != lastNumeric + 1) {
                numericText.append(Integer.toHexString(firstNumeric)).append("..").append(Integer.toHexString(lastNumeric)).append('\n');
                firstNumeric = null;
            }
            if (firstNumeric == null) firstNumeric = cp;
            lastNumeric = cp;
        }
        if (firstNumeric != null) numericText.append(Integer.toHexString(firstNumeric)).append("..").append(Integer.toHexString(lastNumeric)).append('\n');
        Files.writeString(MAIN.resolve("numeric-17.txt"), numericText, StandardCharsets.UTF_8);

        var output = MAIN.resolve("display-name-17.bin.gz");
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(output)))) {
            out.writeInt(0x554E3137); // UN17
            ranges(out, marks);
            ranges(out, alphabetic);
            ranges(out, whitespace);
            out.writeInt(combiningClasses.size());
            for (var entry : combiningClasses.entrySet()) { out.writeInt(entry.getKey()); out.writeInt(entry.getValue()); }
            out.writeInt(lowercase.size());
            for (var entry : lowercase.entrySet()) { out.writeInt(entry.getKey()); sequence(out, entry.getValue()); }
            out.writeInt(decompositions.size());
            for (var entry : decompositions.entrySet()) {
                out.writeInt(entry.getKey()); out.writeBoolean(entry.getValue().compatibility); sequence(out, entry.getValue().scalars);
            }
            out.writeInt(compositions.size());
            for (var entry : compositions.entrySet()) { out.writeLong(entry.getKey()); out.writeInt(entry.getValue()); }
        }
        try (var out = new GZIPOutputStream(Files.newOutputStream(TEST.resolve("NormalizationTest-17.0.0.txt.gz")))) {
            Files.copy(CACHE.resolve("NormalizationTest.txt"), out);
        }
        Files.copy(CACHE.resolve("LICENSE.txt"), MAIN.resolve("LICENSE.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Unicode 17 policy data: " + Files.size(output) + " bytes; " + marks.size() + " marks; "
            + alphabetic.size() + " alphabetic scalars; " + lowercase.size() + " lowercase mappings; "
            + decompositions.size() + " decompositions; " + compositions.size() + " compositions");
    }

    private static int hex(String value) { return Integer.parseInt(value, 16); }
    private static int[] scalars(String value) {
        return java.util.Arrays.stream(value.split(" +")).mapToInt(GenerateDisplayNameUnicode::hex).toArray();
    }
    private static TreeSet<Integer> property(String file, String property) throws IOException {
        var result = new TreeSet<Integer>();
        for (var line : Files.readAllLines(CACHE.resolve(file), StandardCharsets.UTF_8)) {
            var body = line.split("#", 2)[0].trim();
            if (body.isEmpty()) continue;
            var fields = body.split(";", -1);
            if (!fields[1].trim().equals(property)) continue;
            var bounds = fields[0].trim().split("\\.\\.");
            int first = hex(bounds[0]), last = hex(bounds[bounds.length - 1]);
            for (int cp = first; cp <= last; cp++) result.add(cp);
        }
        return result;
    }
    private static void sequence(DataOutputStream out, int[] sequence) throws IOException {
        out.writeInt(sequence.length);
        for (int scalar : sequence) out.writeInt(scalar);
    }
    private static void ranges(DataOutputStream out, TreeSet<Integer> values) throws IOException {
        var ranges = new ArrayList<int[]>();
        int start = -1, last = -2;
        for (int cp : values) {
            if (cp != last + 1) { if (start >= 0) ranges.add(new int[] {start, last}); start = cp; }
            last = cp;
        }
        if (start >= 0) ranges.add(new int[] {start, last});
        out.writeInt(ranges.size());
        for (var range : ranges) { out.writeInt(range[0]); out.writeInt(range[1]); }
    }
}
