package com.example.passmanager.ui.main;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RelayAddressTest {

    private static final String RELAY = "wss://ledger-relay.example.workers.dev";

    @Test
    public void buildsTheRoomAddress() {
        assertEquals(RELAY + "/v1/room/ABCDEFGHJKLMNPQR", RelayAddress.roomUrl(RELAY, "ABCDEFGHJKLMNPQR"));
        assertEquals(RELAY + "/v1/room/ABCDEFGHJKLMNPQR", RelayAddress.roomUrl(RELAY + "/", "ABCDEFGHJKLMNPQR"));
    }

    @Test
    public void rejectsRoomsThatArentLedgerRooms() {
        assertNull(RelayAddress.roomUrl(RELAY, "SHORT"));
        assertNull(RelayAddress.roomUrl(RELAY, "ABCDEFGHIJKLMNOP"));         // I and O never appear
        assertNull(RelayAddress.roomUrl(RELAY, "../../admin?x=ABCDEF"));     // path tricks from a crafted QR
        assertNull(RelayAddress.roomUrl(RELAY, null));
    }

    @Test
    public void needsAnEncryptedRelay() {
        assertNull(RelayAddress.roomUrl("", "ABCDEFGHJKLMNPQR"));             // not set up
        assertNull(RelayAddress.roomUrl("ws://ledger-relay.example.workers.dev", "ABCDEFGHJKLMNPQR"));
        assertNull(RelayAddress.roomUrl("https://example.com", "ABCDEFGHJKLMNPQR"));
        assertNull(RelayAddress.roomUrl("ws://10.0.2.2:8787", "ABCDEFGHJKLMNPQR"));
    }
}
