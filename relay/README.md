# Ledger relay

"Fill on computer" sends a login from the phone to the browser extension through this relay. It is a small Cloudflare Worker with one Durable Object per room. It passes messages between the sockets in the same room and nothing else.

- **No API key.** Nothing secret ships in the app or the extension. The room ID (16 random characters, new for every pop-up) is the only address.
- **It only ever sees ciphertext.** The AES key exists only in the extension's QR code and on the phone.
- **Limits:**
  - 4 connections per room
  - 16 KB per message
  - 20 messages per connection
  - every room closes after 10 minutes
- **Web pages can't connect.** Browsers must send an extension origin (`chrome-extension://` or `moz-extension://`). The phone sends none.

## Deploy (once)

You need a free Cloudflare account and Node.js.

```bash
cd relay
npx wrangler login          # opens the browser to authorise
npx wrangler deploy         # prints the URL, e.g. https://ledger-relay.<account>.workers.dev
```

Then use the same address with `wss://` in both clients:

- **Android app:** `local.properties` needs `RELAY_URL=wss://ledger-relay.<account>.workers.dev` (template: `local.properties.example`). Then rebuild.
- **Extension:** copy `Extension-pass/config.example.js` to `config.js` and set `RELAY_URL: "wss://ledger-relay.<account>.workers.dev"`. Then reload the extension.

The extension's `manifest.json` allows `wss://*.workers.dev`. If you put the relay on your own domain, add that domain to `host_permissions` and to `connect-src` in the CSP.

## Test

```bash
cd relay
npm test                    # the pure rules: room IDs, allowed origins, limits
npx wrangler dev            # run locally
```

## Cost

The free Workers plan includes Durable Objects (SQLite-backed) and WebSocket hibernation. A room waiting for the phone costs almost nothing, and personal use stays well inside the free limits.
