package com.auditlog.service.service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public final class ExportSigningKeyGeneratorCli {
    private ExportSigningKeyGeneratorCli() {
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        System.out.println("WARNING: These Ed25519 keys are for local testing only. Do not use them as production signing keys.");
        System.out.println("AUDIT_EXPORT_SIGNING_PRIVATE_KEY_BASE64=" + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        System.out.println("AUDIT_EXPORT_SIGNING_PUBLIC_KEY_BASE64=" + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }
}
