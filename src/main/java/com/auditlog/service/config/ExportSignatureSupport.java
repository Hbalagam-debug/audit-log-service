package com.auditlog.service.config;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class ExportSignatureSupport {
    public static final String SUPPORTED_SIGNATURE_ALGORITHM = "Ed25519";
    private static final byte[] KEY_VALIDATION_MESSAGE = new byte[]{
        0x45, 0x78, 0x70, 0x6f, 0x72, 0x74, 0x2d, 0x53, 0x69, 0x67, 0x6e, 0x69, 0x6e, 0x67
    };

    private ExportSignatureSupport() {
    }

    public static String encodePublicKeyBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public static PrivateKey decodePrivateKey(String base64Value, String fieldName) {
        byte[] encoded = decodeBase64(base64Value, fieldName);
        try {
            return KeyFactory.getInstance(SUPPORTED_SIGNATURE_ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception ex) {
            throw new IllegalStateException(fieldName + " must be valid Base64 PKCS#8 Ed25519 private key material", ex);
        }
    }

    public static PublicKey decodePublicKey(String base64Value, String fieldName) {
        byte[] encoded = decodeBase64(base64Value, fieldName);
        try {
            return KeyFactory.getInstance(SUPPORTED_SIGNATURE_ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception ex) {
            throw new IllegalStateException(fieldName + " must be valid Base64 X.509 Ed25519 public key material", ex);
        }
    }

    public static void validateKeyPair(PrivateKey privateKey, PublicKey publicKey) {
        byte[] signatureBytes = signBytes(KEY_VALIDATION_MESSAGE, privateKey);
        if (!verifyBytes(KEY_VALIDATION_MESSAGE, signatureBytes, publicKey)) {
            throw new IllegalStateException("audit.export.signing public/private key configuration must represent the same Ed25519 key pair");
        }
    }

    public static String signDigestToBase64(String digestHex, PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(signBytes(decodeSha256Hex(digestHex), privateKey));
    }

    public static boolean verifyDigestSignature(String digestHex, String signatureBase64, PublicKey publicKey) {
        byte[] signatureBytes = decodeBase64(signatureBase64, "signature.value");
        return verifyBytes(decodeSha256Hex(digestHex), signatureBytes, publicKey);
    }

    public static byte[] decodeSha256Hex(String digestHex) {
        if (digestHex == null || !digestHex.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("bundleDigest must be a 64-character lowercase hexadecimal SHA-256 value");
        }
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) {
            int index = i * 2;
            result[i] = (byte) Integer.parseInt(digestHex.substring(index, index + 2), 16);
        }
        return result;
    }

    private static byte[] signBytes(byte[] input, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(SUPPORTED_SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(input);
            return signature.sign();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create Ed25519 signature", ex);
        }
    }

    private static boolean verifyBytes(byte[] input, byte[] signatureBytes, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance(SUPPORTED_SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(input);
            return signature.verify(signatureBytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to verify Ed25519 signature", ex);
        }
    }

    private static byte[] decodeBase64(String base64Value, String fieldName) {
        try {
            return Base64.getDecoder().decode(base64Value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(fieldName + " must be valid Base64", ex);
        }
    }
}
