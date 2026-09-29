package com.example.passmanager.security;

import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Decides whether a scanned "Fill on computer" QR code came from a Ledger extension install
 * and which one, before the phone sends a login to it.
 *
 * v2 codes carry the extension install's ECDSA P-256 public key and a signature over the
 * session (room, AES key, timestamp). The signing key never leaves that browser's extension
 * storage, so a web page can't produce a valid code for a computer the phone already knows.
 * Old (v1) codes are unsigned.
 *
 * Pure Java (no Android APIs) so it runs in JVM unit tests. The matching JavaScript is in
 * Extension-pass/popup.js (signingMessage, checkWordsFor).
 */
public final class BridgeTrust {

    /** Codes older than this are rejected (a photo of someone's screen from earlier won't work). */
    public static final long MAX_AGE_MS = 15 * 60 * 1000;
    /** Allowance for a computer whose clock runs ahead of the phone. */
    public static final long MAX_FUTURE_SKEW_MS = 5 * 60 * 1000;

    public enum Status {
        /** Signed by an extension install and fresh. Check {@link Payload#fingerprint} against known computers. */
        SIGNED,
        /** Old extension: no signature, so the phone can't tell who made it. */
        UNSIGNED,
        /** Claims to be signed but the signature or timestamp doesn't hold up. Never send. */
        INVALID
    }

    public static final class Payload {
        public final String room;
        public final String key;
        public final byte[] publicKey;  // null for v1
        public final long timestamp;    // 0 for v1
        public final byte[] signature;  // null for v1
        public final int version;

        Payload(String room, String key, byte[] publicKey, long timestamp, byte[] signature, int version) {
            this.room = room;
            this.key = key;
            this.publicKey = publicKey;
            this.timestamp = timestamp;
            this.signature = signature;
            this.version = version;
        }

        /** Stable ID of the extension install (first 16 bytes of SHA-256 of its public key), or null for v1. */
        public String fingerprint() {
            return publicKey == null ? null : BridgeTrust.fingerprint(publicKey);
        }
    }

    private BridgeTrust() {}

    /** Parses a scanned code. Throws if it isn't a Ledger code at all. */
    public static Payload parse(String qrText) throws Exception {
        JSONObject json = new JSONObject(qrText);
        String room = json.getString("room");
        String key = json.getString("key");
        int version = json.optInt("v", 1);
        if (version < 2) {
            return new Payload(room, key, null, 0, null, 1);
        }
        Base64.Decoder b64 = Base64.getDecoder();
        return new Payload(room, key,
                b64.decode(json.getString("pk")),
                json.getLong("ts"),
                b64.decode(json.getString("sig")),
                version);
    }

    public static Status check(Payload payload, long nowMs) {
        if (payload.version < 2) return Status.UNSIGNED;
        long age = nowMs - payload.timestamp;
        if (age > MAX_AGE_MS || age < -MAX_FUTURE_SKEW_MS) return Status.INVALID;
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKeyFromRaw(payload.publicKey));
            verifier.update(signingMessage(payload.room, payload.key, payload.timestamp));
            return verifier.verify(rawToDer(payload.signature)) ? Status.SIGNED : Status.INVALID;
        } catch (Exception e) {
            return Status.INVALID;
        }
    }

    /** Must match popup.js signingMessage(). */
    public static byte[] signingMessage(String room, String key, long ts) {
        return ("ledger-qr-v2|" + room + "|" + key + "|" + ts).getBytes(StandardCharsets.UTF_8);
    }

    /** Three words from SHA-256(public key || room). Must match popup.js checkWordsFor(). */
    public static String[] checkWords(byte[] publicKey, String room, List<String> words) {
        if (words.size() != 256) throw new IllegalArgumentException("Check word list must have 256 words");
        byte[] roomBytes = room.getBytes(StandardCharsets.UTF_8);
        byte[] data = Arrays.copyOf(publicKey, publicKey.length + roomBytes.length);
        System.arraycopy(roomBytes, 0, data, publicKey.length, roomBytes.length);
        byte[] hash = sha256(data);
        return new String[]{words.get(hash[0] & 0xff), words.get(hash[1] & 0xff), words.get(hash[2] & 0xff)};
    }

    public static String fingerprint(byte[] publicKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(sha256(publicKey), 16));
    }

    // WebCrypto exports P-256 public keys "raw": 0x04 || X(32) || Y(32)
    static PublicKey publicKeyFromRaw(byte[] raw) throws Exception {
        if (raw == null || raw.length != 65 || raw[0] != 0x04) throw new IllegalArgumentException("Not an uncompressed P-256 key");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    // WebCrypto ECDSA signatures are raw r(32) || s(32); Java's Signature wants ASN.1 DER
    static byte[] rawToDer(byte[] raw) {
        if (raw == null || raw.length != 64) throw new IllegalArgumentException("Not a raw P-256 signature");
        byte[] r = derInteger(Arrays.copyOfRange(raw, 0, 32));
        byte[] s = derInteger(Arrays.copyOfRange(raw, 32, 64));
        byte[] der = new byte[2 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (r.length + s.length);
        System.arraycopy(r, 0, der, 2, r.length);
        System.arraycopy(s, 0, der, 2 + r.length, s.length);
        return der;
    }

    private static byte[] derInteger(byte[] value) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) start++; // minimal encoding
        boolean pad = (value[start] & 0x80) != 0;                        // keep it positive
        int length = value.length - start + (pad ? 1 : 0);
        byte[] out = new byte[2 + length];
        out[0] = 0x02;
        out[1] = (byte) length;
        System.arraycopy(value, start, out, 2 + (pad ? 1 : 0), value.length - start);
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
