import com.google.crypto.tink.*;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.Base64;

// Synthetic, public test keys only. Uses the same Tink Android version and
// template as AttachmentCryptoV2.kt; no application/account data is read.
class Generate {
  public static void main(String[] args) throws Exception {
    StreamingAeadConfig.register();
    Path root = Path.of(args[0]); Files.createDirectories(root);
    for (int size : new int[]{0, 1, 1048520, 1048521, 2097161}) {
      KeysetHandle key = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB"));
      ByteArrayOutputStream serialized = new ByteArrayOutputStream();
      CleartextKeysetHandle.write(key, BinaryKeysetWriter.withOutputStream(serialized));
      byte[] plain = new byte[size];
      for (int i = 0; i < size; i++) plain[i] = (byte)(i % 251);
      ByteArrayOutputStream sink = new ByteArrayOutputStream();
      try (OutputStream encrypted = key.getPrimitive(StreamingAead.class).newEncryptingStream(sink, new byte[0])) {
        encrypted.write(plain);
      }
      byte[] ciphertext = sink.toByteArray();
      Files.write(root.resolve(size + ".bin"), ciphertext);
      Files.writeString(root.resolve(size + ".keyset"), Base64.getEncoder().encodeToString(serialized.toByteArray()));
      Files.writeString(root.resolve(size + ".sha256"), Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(ciphertext)));
    }
  }
}
