package com.auditlog.service.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ExportVerifierCli {
    private ExportVerifierCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: ExportVerifierCli <bundle-json-file>");
            System.exit(2);
            return;
        }

        String bundleJson = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        ExportVerifier verifier = new ExportVerifier(new CanonicalHashService());
        ExportVerifier.VerificationResult result = verifier.verify(bundleJson);

        System.out.println("valid=" + result.valid());
        System.out.println("recordsDigestStatus=" + result.recordsDigestStatus());
        System.out.println("bundleDigestStatus=" + result.bundleDigestStatus());
        for (String error : result.errors()) {
            System.out.println("error=" + error);
        }

        System.exit(result.valid() ? 0 : 1);
    }
}
