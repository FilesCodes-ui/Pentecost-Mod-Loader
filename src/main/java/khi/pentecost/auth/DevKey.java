package khi.pentecost.auth;

import khi.pentecost.boot.Log;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

public final class DevKey {
    private DevKey() {}

    private static final byte[] TAG = "pentecost-dev-proof-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final long RECHECK_MS = 10 * 60_000L;

    private static volatile PublicKey publicKey;
    private static volatile boolean publicTried;
    private static volatile PrivateKey privateKey;
    private static volatile String provider;
    private static volatile long checkedAt;
    private static volatile boolean held;

    public static PublicKey publicKey() {
        if (!publicTried) {
            publicTried = true;
            try (InputStream in = DevKey.class.getResourceAsStream("/pentecost/dev-key.cer")) {
                if (in != null) {
                    publicKey = CertificateFactory.getInstance("X.509").generateCertificate(in).getPublicKey();
                }
            } catch (Exception e) {
                Log.warn("Developer key: the public half could not be read: " + e);
            }
        }
        return publicKey;
    }

    public static byte[] proofMessage(byte[] challenge, UUID account, String purpose) {
        byte[] p = purpose.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(TAG.length + 4 + challenge.length + 16 + 4 + p.length);
        b.put(TAG).putInt(challenge.length).put(challenge)
                .putLong(account.getMostSignificantBits()).putLong(account.getLeastSignificantBits())
                .putInt(p.length).put(p);
        return b.array();
    }

    public static boolean verify(byte[] message, byte[] signature) {
        PublicKey k = publicKey();
        if (k == null || message == null || signature == null || signature.length == 0 || signature.length > 1024) {
            return false;
        }
        try {
            Signature v = Signature.getInstance("SHA256withRSA");
            v.initVerify(k);
            v.update(message);
            return v.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] challenge() {
        byte[] c = new byte[32];
        new SecureRandom().nextBytes(c);
        return c;
    }

    public static boolean held() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < RECHECK_MS) {
            return held;
        }
        checkedAt = now;
        held = false;
        privateKey = null;
        PublicKey pub = publicKey();
        if (pub == null || !System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return false;
        }
        try {
            KeyStore ks = KeyStore.getInstance("Windows-MY");
            ks.load(null, null);
            for (String alias : Collections.list(ks.aliases())) {
                Certificate c = ks.getCertificate(alias);
                if (c == null || !Arrays.equals(c.getPublicKey().getEncoded(), pub.getEncoded())) {
                    continue;
                }
                if (ks.getKey(alias, null) instanceof PrivateKey k) {
                    privateKey = k;
                    provider = ks.getProvider().getName();
                    byte[] test = proofMessage(challenge(), new UUID(0, 0), "self-check");
                    held = verify(test, sign(test));
                }
                break;
            }
        } catch (Exception e) {
            Log.warn("Developer key check failed: " + e);
        }
        if (!held) {
            privateKey = null;
        }
        return held;
    }

    public static byte[] sign(byte[] message) {
        PrivateKey k = privateKey;
        if (k == null) {
            return null;
        }
        try {
            Signature s = Signature.getInstance("SHA256withRSA", provider);
            s.initSign(k);
            s.update(message);
            return s.sign();
        } catch (Exception e) {
            Log.warn("Developer key could not sign: " + e);
            return null;
        }
    }

    public static byte[] prove(byte[] challenge, UUID account, String purpose) {
        return held() ? sign(proofMessage(challenge, account, purpose)) : null;
    }
}
