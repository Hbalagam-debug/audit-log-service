package com.auditlog.service.config;

import java.security.PrivateKey;
import java.security.PublicKey;

public interface SigningKeyProvider {
    PrivateKey loadPrivateKey();

    PublicKey loadPublicKey();

    String getKeyId();
}
