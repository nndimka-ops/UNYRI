import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Independent golden digest of official Unicode 17 properties, without reading
 * the generated application resource. Run from the repository root after the
 * generator has downloaded its hash-verified inputs:
 * java android/tools/CheckDisplayNameUnicodeProperties.java
 */
public class CheckDisplayNameUnicodeProperties {
    public static void main(String[] args) throws Exception {
        var directory = Path.of("android/build/unicode17-source");
        var expected = Map.of(
            "UnicodeData.txt", "2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c",
            "DerivedCoreProperties.txt", "24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08",
            "SpecialCasing.txt", "efc25faf19de21b92c1194c111c932e03d2a5eaf18194e33f1156e96de4c9588",
            "PropList.txt", "130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd"
        );
        for (var input : expected.entrySet()) {
            var digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(input.getKey())));
            if (!HexFormat.of().formatHex(digest).equals(input.getValue())) throw new IllegalStateException("Input hash: " + input.getKey());
        }
        var flags = new byte[0x110000];
        var lower = new HashMap<Integer, int[]>();
        int rangeStart = 0;
        for (var line : Files.readAllLines(directory.resolve("UnicodeData.txt"), StandardCharsets.UTF_8)) {
            var fields = line.split(";", -1);
            int cp = Integer.parseInt(fields[0], 16);
            if (fields[1].endsWith(", First>")) { rangeStart = cp; continue; }
            int first = fields[1].endsWith(", Last>") ? rangeStart : cp;
            if (fields[2].startsWith("M")) Arrays.fill(flags, first, cp + 1, (byte) 1);
            if (!fields[13].isEmpty()) lower.put(cp, new int[] {Integer.parseInt(fields[13], 16)});
        }
        for (var source : Map.of("DerivedCoreProperties.txt", "Alphabetic", "PropList.txt", "White_Space").entrySet()) {
            int bit = source.getValue().equals("Alphabetic") ? 2 : 4;
            for (var line : Files.readAllLines(directory.resolve(source.getKey()), StandardCharsets.UTF_8)) {
                var fields = line.split("#", 2)[0].split(";", -1);
                if (fields.length < 2 || !fields[1].trim().equals(source.getValue())) continue;
                var bounds = fields[0].trim().split("\\.\\.");
                for (int cp = Integer.parseInt(bounds[0], 16); cp <= Integer.parseInt(bounds[bounds.length - 1], 16); cp++) flags[cp] |= bit;
            }
        }
        for (var line : Files.readAllLines(directory.resolve("SpecialCasing.txt"), StandardCharsets.UTF_8)) {
            var fields = line.split("#", 2)[0].split(";", -1);
            if (fields.length > 4 && fields[4].trim().isEmpty()) {
                lower.put(Integer.parseInt(fields[0].trim(), 16), Arrays.stream(fields[1].trim().split(" +")).mapToInt(x -> Integer.parseInt(x, 16)).toArray());
            }
        }
        var digest = MessageDigest.getInstance("SHA-256");
        var record = new byte[18];
        int scalars = 0;
        for (int cp = 0; cp < flags.length; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) continue;
            putInt(record, 0, cp);
            record[4] = flags[cp];
            var sequence = lower.getOrDefault(cp, new int[] {cp});
            record[5] = (byte) sequence.length;
            for (int i = 0; i < sequence.length; i++) putInt(record, 6 + i * 4, sequence[i]);
            digest.update(record, 0, 6 + sequence.length * 4);
            scalars++;
        }
        System.out.println(scalars + " scalars: " + HexFormat.of().formatHex(digest.digest()));
    }
    private static void putInt(byte[] output, int offset, int value) {
        output[offset] = (byte) (value >>> 24); output[offset + 1] = (byte) (value >>> 16);
        output[offset + 2] = (byte) (value >>> 8); output[offset + 3] = (byte) value;
    }
}
