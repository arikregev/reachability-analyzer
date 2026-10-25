package io.github.arikregev.reach.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.arikregev.reach.controller.github.AppJwt;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The GitHub App JWT and the PKCS#1 key format GitHub issues. */
class AppJwtTest {

    @Test
    void signsWithAPkcs1KeyAsGitHubIssuesIt() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        // A 2048-bit PKCS#8 key is a 26-byte header around the PKCS#1 key.
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
        String pem = "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs1)
                + "\n-----END RSA PRIVATE KEY-----\n";

        PrivateKey key = AppJwt.parsePrivateKey(pem);
        assertThat(key.getEncoded()).isEqualTo(pkcs8);

        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        String[] jwt = AppJwt.sign("12345", key, now).split("\\.");
        assertThat(new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8))
                .isEqualTo("{\"iat\":1799999940,\"exp\":1800000540,\"iss\":\"12345\"}");
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(pair.getPublic());
        rsa.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(rsa.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
    }

    @Test
    void readsPkcs8() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        PrivateKey key = gen.generateKeyPair().getPrivate();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----";

        assertThat(AppJwt.parsePrivateKey(pem).getEncoded()).isEqualTo(key.getEncoded());
    }
}
