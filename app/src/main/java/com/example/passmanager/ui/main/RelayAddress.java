package com.example.passmanager.ui.main;

import java.util.regex.Pattern;

/**
 * Builds the WebSocket address of a room on Ledger's relay (relay/src/index.js):
 * {@code <RELAY_URL>/v1/room/<ROOM>}. The room comes from a scanned QR code, so it is checked
 * against the extension's alphabet before it goes into a URL.
 */
final class RelayAddress {

    // Same rule as the relay and the extension: 16 characters, A-Z without I and O, digits 2-9
    private static final Pattern ROOM = Pattern.compile("[A-HJ-NP-Z2-9]{16}");

    private RelayAddress() {}

    /** Returns null if the relay isn't configured or the room isn't a valid Ledger room ID. */
    static String roomUrl(String relayUrl, String room) {
        if (relayUrl == null || room == null) return null;
        String base = relayUrl.trim();
        if (!base.startsWith("wss://")) return null; // encrypted transport only (Android blocks cleartext anyway)
        if (!ROOM.matcher(room).matches()) return null;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/v1/room/" + room;
    }
}
