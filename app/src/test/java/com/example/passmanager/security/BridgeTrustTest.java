package com.example.passmanager.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

public class BridgeTrustTest {

    // Vector produced by the extension's own popup.js functions under Node WebCrypto
    // (signingMessage / checkWordsFor / bytesToBase64), with a fresh P-256 key.
    private static final String VECTOR_QR = "{\"v\":2,\"room\":\"ABCDEFGHJKLMNPQR\",\"key\":\"q0YI8xPl5T6s0wz5z0jVv7l0bVnKZ3rJb1dM2m9hX4c=\",\"pk\":\"BGX3w5AlP5ktorjVXTyvIU224XEArBDLoKEnWlg9hTF8yjSD91VVHVP71EPJRkfNh6KYd2alkE4972AYQR30dao=\",\"ts\":1790640000000,\"sig\":\"ruCOvhH0TbHg1hJOfGpub8rq6wjdXB41o5C8QinHmXUY/bLWZ4qylQEKKrri0bw2dxx7loS38vvpxX29fbvSNg==\"}";
    private static final long VECTOR_TS = 1790640000000L;
    private static final String[] VECTOR_WORDS = {"copper", "wolf", "hollow"};

    @Test
    public void verifiesTheExtensionsSignature() throws Exception {
        BridgeTrust.Payload payload = BridgeTrust.parse(VECTOR_QR);
        assertEquals(2, payload.version);
        assertEquals(BridgeTrust.Status.SIGNED, BridgeTrust.check(payload, VECTOR_TS + 60_000));
    }

    @Test
    public void derivesTheSameCheckWordsAsTheExtension() throws Exception {
        BridgeTrust.Payload payload = BridgeTrust.parse(VECTOR_QR);
        assertArrayEquals(VECTOR_WORDS, BridgeTrust.checkWords(payload.publicKey, payload.room, appWords()));
    }

    @Test
    public void rejectsATamperedRoomKeyOrTimestamp() throws Exception {
        long now = VECTOR_TS + 60_000;
        assertEquals(BridgeTrust.Status.INVALID, BridgeTrust.check(BridgeTrust.parse(withField("room", "ZZZZZZZZZZZZZZZZ")), now));
        assertEquals(BridgeTrust.Status.INVALID, BridgeTrust.check(BridgeTrust.parse(withField("key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")), now));
        assertEquals(BridgeTrust.Status.INVALID, BridgeTrust.check(BridgeTrust.parse(withField("ts", VECTOR_TS + 1)), now));
    }

    @Test
    public void rejectsAnotherKeyClaimingTheSignature() throws Exception {
        // A phishing page can copy the signature, but not with its own public key
        byte[] otherKey = BridgeTrust.parse(VECTOR_QR).publicKey.clone();
        otherKey[10] ^= 0x01;
        String forged = withField("pk", java.util.Base64.getEncoder().encodeToString(otherKey));
        assertEquals(BridgeTrust.Status.INVALID, BridgeTrust.check(BridgeTrust.parse(forged), VECTOR_TS + 60_000));
    }

    @Test
    public void rejectsOldOrFutureCodes() throws Exception {
        BridgeTrust.Payload payload = BridgeTrust.parse(VECTOR_QR);
        assertEquals(BridgeTrust.Status.INVALID, BridgeTrust.check(payload, VECTOR_TS + BridgeTrust.MAX_AGE_MS + 1));
        assertEquals(BridgeTrust.Status.INVALID, BridgeTrust.check(payload, VECTOR_TS - BridgeTrust.MAX_FUTURE_SKEW_MS - 1));
        assertEquals(BridgeTrust.Status.SIGNED, BridgeTrust.check(payload, VECTOR_TS - 60_000)); // small clock skew is fine
    }

    @Test
    public void oldExtensionCodesAreUnsigned() throws Exception {
        BridgeTrust.Payload payload = BridgeTrust.parse("{\"room\":\"ROOM\",\"key\":\"KEY\"}");
        assertEquals(1, payload.version);
        assertNull(payload.fingerprint());
        assertEquals(BridgeTrust.Status.UNSIGNED, BridgeTrust.check(payload, System.currentTimeMillis()));
    }

    @Test
    public void notALedgerCode() {
        assertThrows(Exception.class, () -> BridgeTrust.parse("https://example.com"));
        assertThrows(Exception.class, () -> BridgeTrust.parse("{\"hello\":1}"));
    }

    @Test
    public void fingerprintIsStablePerInstall() throws Exception {
        BridgeTrust.Payload a = BridgeTrust.parse(VECTOR_QR);
        BridgeTrust.Payload b = BridgeTrust.parse(withField("room", "ANOTHERROOM12345"));
        assertEquals(a.fingerprint(), b.fingerprint());
        assertNotEquals(a.fingerprint(), BridgeTrust.fingerprint(new byte[65]));
    }

    @Test
    public void appAndExtensionShareTheSameWordList() throws Exception {
        List<String> app = appWords();
        List<String> extension = readWords(projectFile("Extension-pass", "lib", "check_words.txt"));
        assertEquals(256, app.size());
        assertEquals(app, extension);
    }

    // --- helpers ---

    private static String withField(String name, Object value) throws Exception {
        JSONObject json = new JSONObject(VECTOR_QR);
        json.put(name, value);
        return json.toString();
    }

    private static List<String> appWords() throws Exception {
        return readWords(projectFile("app", "src", "main", "res", "raw", "check_words.txt"));
    }

    private static List<String> readWords(Path path) throws Exception {
        List<String> words = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) words.add(line.trim());
        }
        return words;
    }

    // Unit tests run with the module (app/) as working directory
    private static Path projectFile(String... parts) {
        Path root = Paths.get("").toAbsolutePath();
        if (root.getFileName() != null && root.getFileName().toString().equals("app")) root = root.getParent();
        return Paths.get(root.toString(), parts);
    }
}
