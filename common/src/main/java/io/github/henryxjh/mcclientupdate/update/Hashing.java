package io.github.henryxjh.mcclientupdate.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class Hashing {
    private Hashing() {
    }

    public static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    public static String sha512(Path file) throws IOException {
        return digest(file, "SHA-512");
    }

    /** Calculates both manifest hashes while reading the file only once. */
    public static Hashes hashes(Path file) throws IOException {
        MessageDigest sha256 = newDigest("SHA-256");
        MessageDigest sha512 = newDigest("SHA-512");

        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                sha256.update(buffer, 0, read);
                sha512.update(buffer, 0, read);
            }
        }
        return new Hashes(
                HexFormat.of().formatHex(sha256.digest()),
                HexFormat.of().formatHex(sha512.digest()));
    }

    public static String normalizeSha256(String hash) {
        return normalize(hash, 64, "SHA-256");
    }

    public static String normalizeSha512(String hash) {
        return normalize(hash, 128, "SHA-512");
    }

    private static String digest(Path file, String algorithm) throws IOException {
        MessageDigest digest = newDigest(algorithm);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Every Java runtime must provide " + algorithm, impossible);
        }
    }

    private static String normalize(String hash, int hexLength, String algorithm) {
        String normalized = hash.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() != hexLength || !normalized.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("Invalid " + algorithm + ": " + hash);
        }
        return normalized;
    }

    public record Hashes(String sha256, String sha512) {
        public Hashes {
            if (sha256 == null && sha512 == null) {
                throw new IllegalArgumentException("At least one hash must be provided");
            }
            if (sha256 != null) {
                sha256 = normalizeSha256(sha256);
            }
            if (sha512 != null) {
                sha512 = normalizeSha512(sha512);
            }
        }

        public boolean hasSha256() {
            return sha256 != null;
        }

        public boolean hasSha512() {
            return sha512 != null;
        }
    }
}
