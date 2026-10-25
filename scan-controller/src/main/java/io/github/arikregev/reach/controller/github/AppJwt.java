package io.github.arikregev.reach.controller.github;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * The GitHub App JWT (RS256, {@code iss} = App id, at most 10 minutes). GitHub issues PKCS#1 keys
 * ({@code BEGIN RSA PRIVATE KEY}); those are wrapped into PKCS#8 here so no extra library is needed.
 */
public final class AppJwt {

    /** DER of AlgorithmIdentifier {rsaEncryption, NULL}. */
    private static final byte[] RSA_ALGORITHM = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};

    private AppJwt() {
    }

    public static String sign(String appId, PrivateKey key, Instant now) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        // iat is backdated 60s for clock drift; exp stays under GitHub's 10-minute limit.
        String payload = b64.encodeToString(("{\"iat\":" + (now.getEpochSecond() - 60) + ",\"exp\":"
                + (now.getEpochSecond() + 540) + ",\"iss\":\"" + appId + "\"}").getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + payload;
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(key);
            rsa.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + b64.encodeToString(rsa.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign the GitHub App JWT", e);
        }
    }

    /** Reads a PEM RSA private key, PKCS#1 or PKCS#8. */
    public static PrivateKey parsePrivateKey(String pem) {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        if (pkcs1) {
            der = pkcs1ToPkcs8(der);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("reach.github.private-key is not an RSA private key", e);
        }
    }

    /** PrivateKeyInfo ::= SEQUENCE { version 0, AlgorithmIdentifier, OCTET STRING pkcs1 }. */
    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(new byte[] {0x02, 0x01, 0x00});
        inner.writeBytes(RSA_ALGORITHM);
        inner.write(0x04);
        inner.writeBytes(length(pkcs1.length));
        inner.writeBytes(pkcs1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.writeBytes(length(inner.size()));
        out.writeBytes(inner.toByteArray());
        return out.toByteArray();
    }

    private static byte[] length(int n) {
        if (n < 0x80) {
            return new byte[] {(byte) n};
        }
        if (n < 0x100) {
            return new byte[] {(byte) 0x81, (byte) n};
        }
        if (n < 0x10000) {
            return new byte[] {(byte) 0x82, (byte) (n >> 8), (byte) n};
        }
        return new byte[] {(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }
}
