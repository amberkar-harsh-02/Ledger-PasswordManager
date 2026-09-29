# Third-party notices

Ledger is licensed under the GPL-3.0 (see [LICENSE](LICENSE)). It includes or depends on the following components, each under its own license.

## Bundled in this repository

| Component | Where | License |
|-----------|-------|---------|
| QR Code Generator for JavaScript, © 2009 Kazuhiko Arase | `Extension-pass/lib/qrcode.js` | MIT (header in the file) |
| Atkinson Hyperlegible Next, © Braille Institute of America | app fonts | SIL Open Font License 1.1 ([docs/licenses/AtkinsonHyperlegibleNext-OFL.txt](docs/licenses/AtkinsonHyperlegibleNext-OFL.txt)) |

## Android dependencies (fetched by Gradle)

| Component | License |
|-----------|---------|
| AndroidX (AppCompat, Activity, ConstraintLayout, Room, Lifecycle, Biometric) | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| ZXing core | Apache-2.0 |
| ZXing Android Embedded (JourneyApps) | Apache-2.0 |
| OkHttp / Okio (Square) | Apache-2.0 |
| Material Tap Target Prompt (Samuel Wall) | Apache-2.0 |

## Relay

The relay (`relay/`) has no runtime dependencies. It is built and deployed with Cloudflare's `wrangler` CLI.
