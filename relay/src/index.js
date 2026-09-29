// Ledger relay: a Cloudflare Worker that passes end-to-end encrypted messages between the
// Ledger browser extension and the Ledger phone app. It never sees a key or a password: the
// extension shows the AES key only in its QR code, and the phone encrypts with it before sending.
//
// URL:  wss://<your-worker>/v1/room/<ROOM>   ROOM = 16 characters from A-Z (no I, O) and 2-9
//
// There is no API key. The room ID (80 random bits, made fresh by the extension for every pop-up)
// is the only way to reach a room, and anything sent there is ciphertext. Abuse is limited per room:
// a few connections, small messages, a handful of messages each, and every room closes after 10 min.

const ROOM_PATTERN = /^\/v1\/room\/([A-HJ-NP-Z2-9]{16})$/;

export const LIMITS = {
    maxPeersPerRoom: 4,          // the pop-up, the phone, and a little slack for a retry
    maxMessageBytes: 16 * 1024,  // a login bundle is well under 1 KB
    maxMessagesPerPeer: 20,
    roomLifetimeMs: 10 * 60 * 1000,
};

// Browsers send an Origin header. Only extension pages may connect from a browser, so a web page
// (for example a phishing page) can't use this relay. The phone app sends no Origin.
export function isAllowedOrigin(origin) {
    if (!origin) return true;
    return origin.startsWith("chrome-extension://") || origin.startsWith("moz-extension://");
}

export function roomFromPath(pathname) {
    const match = ROOM_PATTERN.exec(pathname);
    return match ? match[1] : null;
}

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        const room = roomFromPath(url.pathname);
        if (!room) return new Response("Not found", { status: 404 });
        if (request.headers.get("Upgrade") !== "websocket") {
            return new Response("Expected a WebSocket", { status: 426 });
        }
        if (!isAllowedOrigin(request.headers.get("Origin"))) {
            return new Response("Forbidden", { status: 403 });
        }
        // One Durable Object per room: every socket in a room meets in the same place
        const stub = env.ROOMS.get(env.ROOMS.idFromName(room));
        return stub.fetch(request);
    },
};

export class Room {
    constructor(ctx) {
        this.ctx = ctx;
    }

    async fetch() {
        if (this.ctx.getWebSockets().length >= LIMITS.maxPeersPerRoom) {
            return new Response("Room is full", { status: 429 });
        }

        const pair = new WebSocketPair();
        const [client, server] = Object.values(pair);
        // Hibernation API: the room costs nothing while it waits for the phone
        this.ctx.acceptWebSocket(server);
        server.serializeAttachment({ messages: 0 });

        if ((await this.ctx.storage.getAlarm()) === null) {
            await this.ctx.storage.setAlarm(Date.now() + LIMITS.roomLifetimeMs);
        }
        return new Response(null, { status: 101, webSocket: client });
    }

    async webSocketMessage(ws, message) {
        const size = typeof message === "string" ? new TextEncoder().encode(message).length : message.byteLength;
        if (size > LIMITS.maxMessageBytes) {
            ws.close(1009, "Message too big");
            return;
        }
        const state = ws.deserializeAttachment() || { messages: 0 };
        if (state.messages >= LIMITS.maxMessagesPerPeer) {
            ws.close(1008, "Too many messages");
            return;
        }
        state.messages++;
        ws.serializeAttachment(state);

        // Forward to everyone else in the room, never back to the sender
        for (const peer of this.ctx.getWebSockets()) {
            if (peer === ws) continue;
            try {
                peer.send(message);
            } catch (e) {
                // that peer already went away
            }
        }
    }

    async webSocketClose(ws) {
        try {
            ws.close(1000, "Bye");
        } catch (e) {
            // already closed
        }
    }

    async webSocketError(ws) {
        try {
            ws.close(1011, "Error");
        } catch (e) {
            // already closed
        }
    }

    // Room lifetime is up: close whatever is still connected and forget the room
    async alarm() {
        for (const ws of this.ctx.getWebSockets()) {
            try {
                ws.close(1001, "Room expired");
            } catch (e) {
                // already closed
            }
        }
        await this.ctx.storage.deleteAll();
    }
}
