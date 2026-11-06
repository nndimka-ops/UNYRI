using System;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;

namespace CocoAdmin
{
    internal static class CryptoAdmin
    {
        public const string Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

        public static string NormalizePaperCode(string value)
        {
            if (value == null) return "";
            var builder = new StringBuilder(value.Length);
            foreach (var ch in value.ToUpperInvariant())
            {
                if (ch == '-' || char.IsWhiteSpace(ch)) continue;
                builder.Append(ch);
            }
            return builder.ToString();
        }

        public static bool ValidatePaperCode(string value, out string normalized)
        {
            normalized = NormalizePaperCode(value);
            if (normalized.Length != 32) return false;
            foreach (var ch in normalized)
                if (Alphabet.IndexOf(ch) < 0) return false;
            var first30 = normalized.Substring(0, 30);
            byte[] hash;
            using (var sha = SHA256.Create())
                hash = sha.ComputeHash(Encoding.UTF8.GetBytes("COCO-ADMIN-CHECK-V1\0" + first30));
            var c0 = Alphabet[(hash[0] & 0xff) >> 3];
            var c1 = Alphabet[((hash[0] & 0x07) << 2) | ((hash[1] & 0xff) >> 6)];
            return normalized[30] == c0 && normalized[31] == c1;
        }

        public static byte[] SeedFromPaperCode(string normalized)
        {
            using (var sha = SHA256.Create())
                return sha.ComputeHash(Encoding.UTF8.GetBytes("COCO-ADMIN-SEED-V1\0" + normalized));
        }

        public static byte[] PublicKey(byte[] seed)
        {
            var key = new Org.BouncyCastle.Crypto.Parameters.Ed25519PrivateKeyParameters(seed, 0);
            return key.GeneratePublicKey().GetEncoded();
        }

        public static string PublicKeyBase64(string paperCode)
        {
            string normalized;
            if (!ValidatePaperCode(paperCode, out normalized)) throw new ArgumentException("Paper code checksum is invalid.");
            return Base64Url(PublicKey(SeedFromPaperCode(normalized)));
        }

        public static byte[] Sign(byte[] seed, byte[] message)
        {
            var key = new Org.BouncyCastle.Crypto.Parameters.Ed25519PrivateKeyParameters(seed, 0);
            var signer = new Org.BouncyCastle.Crypto.Signers.Ed25519Signer();
            signer.Init(true, key);
            signer.BlockUpdate(message, 0, message.Length);
            return signer.GenerateSignature();
        }

        public static string SignBase64(string paperCode, string message)
        {
            string normalized;
            if (!ValidatePaperCode(paperCode, out normalized)) throw new ArgumentException("Paper code checksum is invalid.");
            return Base64Url(Sign(SeedFromPaperCode(normalized), Encoding.UTF8.GetBytes(message)));
        }

        public static string GeneratePaperCode()
        {
            var symbols = new char[30];
            var bytes = new byte[30];
            using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(bytes);
            for (var i = 0; i < symbols.Length; i++) symbols[i] = Alphabet[bytes[i] % Alphabet.Length];
            var first30 = new string(symbols);
            byte[] hash;
            using (var sha = SHA256.Create())
                hash = sha.ComputeHash(Encoding.UTF8.GetBytes("COCO-ADMIN-CHECK-V1\0" + first30));
            var c0 = Alphabet[(hash[0] & 0xff) >> 3];
            var c1 = Alphabet[((hash[0] & 0x07) << 2) | ((hash[1] & 0xff) >> 6)];
            return first30 + c0 + c1;
        }

        public static string GenerateTotp(string base32Secret)
        {
            var secret = Base32Decode(base32Secret);
            var counter = (long)(DateTimeOffset.UtcNow.ToUnixTimeSeconds() / 30);
            var message = new byte[8];
            for (var i = 7; i >= 0; i--) { message[i] = (byte)(counter & 0xff); counter >>= 8; }
            using (var hmac = new HMACSHA1(secret))
            {
                var hash = hmac.ComputeHash(message);
                var offset = hash[hash.Length - 1] & 0x0f;
                var binary = ((hash[offset] & 0x7f) << 24) | (hash[offset + 1] << 16) |
                             (hash[offset + 2] << 8) | hash[offset + 3];
                return (binary % 1000000).ToString("D6");
            }
        }

        private static byte[] Base32Decode(string value)
        {
            var cleaned = value.Trim().TrimEnd('=').Replace(" ", "").ToUpperInvariant();
            const string rfc4648 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            var bits = new List<int>();
            foreach (var ch in cleaned)
            {
                var index = rfc4648.IndexOf(ch);
                if (index < 0) throw new ArgumentException("Invalid base32 secret.");
                for (var bit = 4; bit >= 0; bit--) bits.Add((index >> bit) & 1);
            }
            var bytes = new byte[bits.Count / 8];
            for (var i = 0; i < bytes.Length; i++)
            {
                var b = 0;
                for (var j = 0; j < 8; j++) b = (b << 1) | bits[i * 8 + j];
                bytes[i] = (byte)b;
            }
            return bytes;
        }

        public static string Base64Url(byte[] bytes)
        {
            return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        }

        public static string ProofMessage(string serverId, string userId, string authSession, string challengeId,
            string purpose, string publicKey, string nonce)
        {
            return "COCO-ADMIN-PROOF-V1\n" + serverId + "\n" + userId + "\n" + authSession + "\n" +
                   challengeId + "\n" + purpose + "\n" + publicKey + "\n" + nonce;
        }
    }
}
