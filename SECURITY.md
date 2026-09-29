# Security policy

Ledger is a personal project that handles passwords, so security reports are very welcome.

## Reporting a vulnerability

Please **don't open a public issue**. Report it privately through GitHub instead:
**Security → Report a vulnerability** on this repository (a private security advisory).

Include:
- the affected part: the Android app, the browser extension or the relay
- steps to reproduce, and what an attacker gains
- the app or extension version, and your device or browser

You'll get a reply as soon as I can manage. Fixes are released on the `Passmanager` branch and noted in [docs/SECURITY_PLAN.md](docs/SECURITY_PLAN.md).

## Supported versions

Only the latest code on the default branch is supported.

## Scope and known limits

The threat model, the accepted risks, and every issue fixed so far are documented in [docs/SECURITY_PLAN.md](docs/SECURITY_PLAN.md). For example, the vault key isn't bound to biometric authentication, by design. Reports about the accepted risks are still welcome if you can show a practical attack.
