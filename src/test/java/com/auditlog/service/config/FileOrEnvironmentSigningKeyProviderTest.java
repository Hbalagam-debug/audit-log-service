package com.auditlog.service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("FileOrEnvironmentSigningKeyProvider")
class FileOrEnvironmentSigningKeyProviderTest {
    private static final String TEST_KEY_ID = "provider-test-key";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Loads signing keys from configured files")
    void testLoadsKeysFromFiles() throws Exception {
        KeyPair keyPair = generateKeyPair();
        Path privateKeyFile = tempDir.resolve("export-private.key");
        Path publicKeyFile = tempDir.resolve("export-public.key");
        Files.writeString(privateKeyFile, Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()), StandardCharsets.UTF_8);
        Files.writeString(publicKeyFile, Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()), StandardCharsets.UTF_8);

        FileOrEnvironmentSigningKeyProvider provider = new FileOrEnvironmentSigningKeyProvider(
            validSigningPropertiesBuilder()
                .setPrivateKeyFile(privateKeyFile.toString())
                .setPublicKeyFile(publicKeyFile.toString())
                .build()
        );

        assertDoesNotThrow(provider::validateConfiguration);
        assertNotNull(provider.loadPrivateKey());
        assertNotNull(provider.loadPublicKey());
        assertTrue(provider.getKeyId().equals(TEST_KEY_ID));
    }

    @Test
    @DisplayName("Falls back to Base64 configuration when file paths are absent")
    void testFallsBackToBase64Configuration() throws Exception {
        KeyPair keyPair = generateKeyPair();
        FileOrEnvironmentSigningKeyProvider provider = new FileOrEnvironmentSigningKeyProvider(
            validSigningPropertiesBuilder()
                .setPrivateKeyBase64(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()))
                .setPublicKeyBase64(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()))
                .build()
        );

        assertDoesNotThrow(provider::validateConfiguration);
        assertNotNull(provider.loadPrivateKey());
        assertNotNull(provider.loadPublicKey());
    }

    @Test
    @DisplayName("Rejects invalid Base64 without exposing the supplied value")
    void testRejectsInvalidBase64Safely(CapturedOutput output) {
        String secretLookingValue = "very-secret-private-key-value";
        FileOrEnvironmentSigningKeyProvider provider = new FileOrEnvironmentSigningKeyProvider(
            validSigningPropertiesBuilder()
                .setPrivateKeyBase64(secretLookingValue)
                .setPublicKeyBase64(Base64.getEncoder().encodeToString(generateKeyPairUnchecked().getPublic().getEncoded()))
                .build()
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, provider::validateConfiguration);

        assertTrue(exception.getMessage().contains(FileOrEnvironmentSigningKeyProvider.PRIVATE_KEY_BASE64_ENV));
        assertFalse(exception.getMessage().contains(secretLookingValue));
        assertFalse(stackTrace(exception).contains(secretLookingValue));
        assertFalse((output.getOut() + output.getErr()).contains(secretLookingValue));
    }

    @Test
    @DisplayName("Rejects invalid key format without exposing key material")
    void testRejectsInvalidKeyFormatSafely(CapturedOutput output) {
        String notAKeyBase64 = Base64.getEncoder().encodeToString("not-a-real-ed25519-key".getBytes(StandardCharsets.UTF_8));
        FileOrEnvironmentSigningKeyProvider provider = new FileOrEnvironmentSigningKeyProvider(
            validSigningPropertiesBuilder()
                .setPrivateKeyBase64(notAKeyBase64)
                .setPublicKeyBase64(notAKeyBase64)
                .build()
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, provider::validateConfiguration);

        assertTrue(exception.getMessage().contains(FileOrEnvironmentSigningKeyProvider.PRIVATE_KEY_FILE_ENV));
        assertFalse(exception.getMessage().contains(notAKeyBase64));
        assertFalse(stackTrace(exception).contains(notAKeyBase64));
        assertFalse((output.getOut() + output.getErr()).contains(notAKeyBase64));
    }

    @Test
    @DisplayName("Rejects missing key configuration with a safe startup message")
    void testRejectsMissingKeyConfiguration() {
        KeyPair keyPair = generateKeyPairUnchecked();
        FileOrEnvironmentSigningKeyProvider provider = new FileOrEnvironmentSigningKeyProvider(
            validSigningPropertiesBuilder()
                .setPrivateKeyBase64("")
                .setPublicKeyBase64(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()))
                .build()
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, provider::validateConfiguration);
        assertTrue(exception.getMessage().contains(FileOrEnvironmentSigningKeyProvider.PRIVATE_KEY_FILE_ENV));
        assertTrue(exception.getMessage().contains(FileOrEnvironmentSigningKeyProvider.PRIVATE_KEY_BASE64_ENV));
    }

    @Test
    @DisplayName("Loaded keys support signing and verification round trip")
    void testSigningVerificationRoundTrip() throws Exception {
        KeyPair keyPair = generateKeyPair();
        FileOrEnvironmentSigningKeyProvider provider = new FileOrEnvironmentSigningKeyProvider(
            validSigningPropertiesBuilder()
                .setPrivateKeyBase64(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()))
                .setPublicKeyBase64(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()))
                .build()
        );

        provider.validateConfiguration();
        String digestHex = "a".repeat(64);
        String signatureBase64 = ExportSignatureSupport.signDigestToBase64(digestHex, provider.loadPrivateKey());

        assertTrue(ExportSignatureSupport.verifyDigestSignature(digestHex, signatureBase64, provider.loadPublicKey()));
    }

    private SigningPropertiesBuilder validSigningPropertiesBuilder() {
        return new SigningPropertiesBuilder().setKeyId(TEST_KEY_ID);
    }

    private static KeyPair generateKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static KeyPair generateKeyPairUnchecked() {
        try {
            return generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate Ed25519 key pair for test", ex);
        }
    }

    private String stackTrace(Exception exception) {
        StringWriter stringWriter = new StringWriter();
        exception.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    private static final class SigningPropertiesBuilder {
        private String keyId;
        private String privateKeyFile = "";
        private String privateKeyBase64 = "";
        private String publicKeyFile = "";
        private String publicKeyBase64 = "";

        private SigningPropertiesBuilder setKeyId(String keyId) {
            this.keyId = keyId;
            return this;
        }

        private SigningPropertiesBuilder setPrivateKeyFile(String privateKeyFile) {
            this.privateKeyFile = privateKeyFile;
            return this;
        }

        private SigningPropertiesBuilder setPrivateKeyBase64(String privateKeyBase64) {
            this.privateKeyBase64 = privateKeyBase64;
            return this;
        }

        private SigningPropertiesBuilder setPublicKeyFile(String publicKeyFile) {
            this.publicKeyFile = publicKeyFile;
            return this;
        }

        private SigningPropertiesBuilder setPublicKeyBase64(String publicKeyBase64) {
            this.publicKeyBase64 = publicKeyBase64;
            return this;
        }

        private ExportProperties.SigningProperties build() {
            ExportProperties.SigningProperties signingProperties = new ExportProperties.SigningProperties();
            signingProperties.setEnabled(true);
            signingProperties.setAlgorithm(ExportSignatureSupport.SUPPORTED_SIGNATURE_ALGORITHM);
            signingProperties.setKeyId(keyId);
            signingProperties.setPrivateKeyFile(privateKeyFile);
            signingProperties.setPrivateKeyBase64(privateKeyBase64);
            signingProperties.setPublicKeyFile(publicKeyFile);
            signingProperties.setPublicKeyBase64(publicKeyBase64);
            return signingProperties;
        }
    }
}
