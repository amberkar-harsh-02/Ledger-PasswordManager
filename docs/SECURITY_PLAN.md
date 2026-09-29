# Ledger security log

This is a record of the security problems found in Ledger (the Android app, the browser extension and the relay) and how each one was solved.

Ledger was reviewed twice:
1. **First review:** the original app, before the redesign.
2. **Second review:** after the UI redesign, looking for problems the first round missed or the new design introduced.

Severity levels: **Critical** means passwords could leak with little effort. **High** means a realistic attack or a serious leak. **Medium** needs special conditions. **Low** is hardening.

---

## Summary

| # | Severity | Problem | Status |
|---|----------|---------|--------|
| 1.1 | Critical | The QR code (containing the encryption key) was drawn by an outside website | Fixed |
| 1.2 | Critical | Autofill could hand passwords to look-alike apps and sites | Fixed |
| 1.3 | Critical | Autofill filled passwords without asking for a fingerprint or PIN | Fixed |
| 1.4 | Critical | After Android restarted the app, the vault opened without the lock screen | Fixed |
| 1.5 | Critical | The 4-digit PIN could be guessed without limit and was weakly hashed | Fixed |
| 1.6 | High | 2FA secrets were stored unencrypted | Fixed |
| 1.7 | High | The vault was included in Android cloud backups | Fixed |
| 1.8 | High | Backup files couldn't be restored on a new phone and held 2FA secrets in plain text | Fixed |
| 1.9 | High | The extension filled any open tab without checking the site | Fixed |
| 1.10 | High | The username travelled unencrypted, and room IDs weren't truly random | Fixed |
| 1.11 | High | Usernames were written to the system log | Fixed |
| 1.12 | Medium | Copied passwords stayed on the clipboard forever | Fixed |
| 1.13 | Medium | Risky settings could be turned on with no warning | Fixed |
| 1.14 | Medium | The duress PIN didn't cover autofill and could equal the master PIN | Fixed |
| 1.15 | Medium | A database upgrade could silently wipe the vault | Fixed |
| 1.16 | Medium | Old API keys were left in the repository | Fixed |
| 1.17 | Medium | A dependency had a known vulnerability | Fixed |
| 1.18 | Low | Build, manifest and logging clean-ups | Fixed |
| 2.1 | High | A fake QR code on a web page could receive the password | Fixed |
| 2.2 | High | Auto-lock didn't cover the Add and Edit screens | Fixed |
| 2.3 | Medium | The autofill prompt didn't say which app would receive the password | Fixed |
| 2.4 | Medium | The PIN lockout could be skipped by changing the phone's clock | Fixed |
| 2.5 | Medium | A third-party relay key was shipped inside the app and the extension | Fixed |
| 2.6 | Medium | Login names and usernames are stored unencrypted in the database | Accepted for now |
| 2.7 | Low | Using the duress PIN left traces | Fixed |
| 2.8 | Low | The extension filled hidden (decoy) password fields | Fixed |
| 2.9 | Low | The "Hide screen contents" setting didn't really work | Fixed |
| 2.10 | Low | Release builds were easy to reverse-engineer | Fixed |

---

## First review

### 1.1 The QR code was drawn by an outside website (Critical)

**Problem:** To show the "scan with your phone" QR code, the extension sent its contents to a public QR-image service. Those contents include the one-time encryption key, so that service could have read the login sent afterwards.

**Solution:**
- The QR code is now drawn inside the extension by a bundled library. Nothing leaves the computer.
- The extension's security policy blocks images from anywhere else.
- Room IDs are now made with the browser's cryptographic random generator.

### 1.2 Autofill could hand passwords to look-alike apps (Critical)

**Problem:** Autofill matched logins loosely. "git" matched "GitHub", and an app could claim any website address. A phishing app or site could be offered real passwords.

**Solution:**
- Names must now match exactly, ignoring only case and punctuation.
- A website address is trusted only when it comes from a known browser.
- When nothing matches, a "Search Ledger" list lets the user choose a login by hand.

### 1.3 Autofill didn't ask for a fingerprint or PIN (Critical)

**Problem:** Tapping a Ledger suggestion in another app filled the password straight away, so anyone holding an unlocked phone could fill any password.

**Solution:**
- Every suggestion is now locked. Nothing is decrypted until the user passes a fingerprint or PIN check.
- **Accepted risk:** the encryption key itself is not tied to the fingerprint. Tying it would require a phone screen lock and re-encrypting the whole vault. On a rooted phone, the data could therefore be decrypted without the PIN.

### 1.4 Vault opened without the lock screen after a restart (Critical)

**Problem:** When Android closes an app in the background and later restores it, Ledger reopened directly on the vault and skipped the lock screen.

**Solution:** A restored vault screen now always sends the user to the lock screen first. The second review extended this to every screen (2.2).

### 1.5 The PIN could be guessed without limit (Critical)

**Problem:** The 4-digit PIN had no limit on wrong attempts, and it was stored as a single fast hash with the same salt for every user, so it was quick to crack.

**Solution:**
- PINs are now hashed with PBKDF2 (150,000 rounds) and a random salt, and checked in constant time.
- Existing PINs upgrade silently at the next unlock.
- After 5 wrong PINs, the app locks for 30 seconds, doubling each time up to 1 hour.
- The duress PIN can no longer be the same as the master PIN.

### 1.6 2FA secrets were stored unencrypted (High)

**Problem:** The secret keys behind the 2FA codes were saved as plain text in the database.

**Solution:** 2FA secrets are now encrypted the same way as passwords. Existing secrets were encrypted automatically, once, with the codes unchanged.

### 1.7 The vault was included in cloud backups (High)

**Problem:** Android's automatic backup could copy the vault database and the PIN hash to the cloud or to a new phone.

**Solution:** Android backups are turned off for Ledger, and every file is excluded. Ledger's own encrypted backup is the only way to move the vault.

### 1.8 Backups couldn't move to a new phone (High)

**Problem:** Backup files contained data still locked to the old phone's hardware key, so they couldn't be restored anywhere else. 2FA secrets were also inside them in plain text.

**Solution:**
- There is a new backup format, encrypted with the backup password: PBKDF2 with 600,000 rounds, then AES-256-GCM.
- It restores on any phone, and older backups still import.
- New backup passwords must be at least 10 characters.

### 1.9 The extension filled any tab (High)

**Problem:** The extension filled whatever tab was open, even if it wasn't the site the login belongs to.

**Solution:**
- Before filling, the extension shows which login goes into which site.
- If the site doesn't match the login's name, it warns and makes "Don't fill" the main button.

### 1.10 Username sent unencrypted; weak room IDs (High)

**Problem:** When sending a login to a computer, the username travelled outside the encrypted part of the message. Room IDs used a predictable random function.

**Solution:** The username, password and site name are now all inside the encrypted message, and room IDs use a cryptographic random generator.

### 1.11 Usernames written to the system log (High)

**Problem:** The autofill service wrote usernames to the phone's system log, which other tools can read.

**Solution:** That log line was removed, and every raw error print was replaced with a plain error log. The 2FA code generator logs nothing, because its error text could contain part of a secret.

### 1.12 Copied passwords stayed on the clipboard (Medium)

**Problem:** A copied password stayed on the clipboard indefinitely, and Android showed it in the clipboard preview.

**Solution:** Copied passwords and 2FA codes are marked as sensitive, so Android hides the preview, and the clipboard is cleared after 45 seconds.

### 1.13 Risky settings with no warning (Medium)

**Problem:** Auto-lock could be set to "Never", and screen protection could be switched off, without any warning.

**Solution:** Both now show a confirmation that explains what the user gives up.

### 1.14 Duress PIN gaps (Medium)

**Problem:** The duress PIN, which opens an empty decoy vault when someone forces you to unlock, didn't stop autofill from offering real passwords. It could also be set to the same value as the master PIN.

**Solution:** In a duress session, autofill offers nothing, and the two PINs must be different.

### 1.15 A database upgrade could wipe the vault (Medium)

**Problem:** The database was set to delete all data if an upgrade step was missing.

**Solution:** That setting was removed. A missing upgrade now fails loudly instead of silently erasing the vault.

### 1.16 Old API keys left in the repository (Medium)

**Problem:** A packaged copy of the old extension and old configuration files with API keys had been committed to the repository.

**Solution:**
- The package was deleted.
- Configuration files are now excluded from git, and the keys were rotated.
- The relay service was later replaced entirely (2.5), so none of those keys are used by Ledger any more.

### 1.17 Dependency with a known vulnerability (Medium)

**Problem:** The networking library pulled in a component with a published vulnerability (CVE-2023-3635).

**Solution:** The networking library was updated, and the other dependencies were updated and aligned to matching versions.

### 1.18 Clean-ups (Low)

- **Manifest:** an entry for a screen that didn't exist was removed.
- **Extension:** a broken background script that opened a second, orphaned relay connection was removed.
- **Relay connections:** the app now closes its connection to the relay as soon as the message is sent, instead of leaving it open.

---

## Second review (after the UI redesign)

### 2.1 A fake QR code could receive the password (High)

**Problem:** The phone sent the login to whatever Ledger QR code it scanned. A phishing page could display its own "Scan with Ledger" code and receive the password.

**Limit:** the extension's code is public. On a computer the phone has never seen, nothing can prove a QR code came from a real extension and not a perfect copy. That case needs a human check.

**Solution:**
- **Signed QR codes.** Each extension install creates its own signing key (ECDSA P-256). The key can't be exported, and web pages can't reach it. Every QR code is signed with it and expires after 15 minutes.
- **Known computers.** The phone can remember a computer it has used before. On a remembered computer, sending takes one tap, and a code from any other key shows up as a "New computer" warning.
- **Check words.** For a new computer, the phone and the extension's toolbar pop-up both show the same three words. The user continues only if they match. The words are derived from the signing key and the session, so a copied code can't reproduce them.
- **Always confirm.** The phone always asks before sending. A code with a bad signature, or an expired one, can't send anything.
- **Management.** Remembered computers can be renamed or forgotten in Security → Computers.

### 2.2 Auto-lock didn't cover every screen (High)

**Problem:** Auto-lock only checked the main vault screen. Leaving the app on the Edit screen and coming back later showed the decrypted password with no lock. A restored Add or Edit screen also skipped the lock screen.

**Solution:**
- The app now tracks when Ledger as a whole goes to the background, not just one screen.
- Every screen that shows vault data sends the user to the lock screen when the app wasn't unlocked in that session.
- After the auto-lock time, every such screen covers itself and asks for a fingerprint or PIN, and unsaved edits are kept.
- An open password sheet closes when the user leaves the app.
- Opening the file picker for backups or the share sheet doesn't trigger a lock.

### 2.3 The autofill prompt didn't name the receiving app (Medium)

**Problem:** Any app can call itself "GitHub". The unlock prompt only said "Unlock Ledger", so a user could approve a fill into a look-alike app without noticing.

**Solution:**
- The prompt now says exactly where the password goes, for example "Fill GitHub login? Into github.com in Chrome".
- For apps it shows the app's name with its package ID, for example "Into Totally Legit Game (com.evil.game)". An app can copy a name but not another app's package ID.
- Nothing is decrypted before the user approves.

### 2.4 The PIN lockout could be skipped by changing the clock (Medium)

**Problem:** The wrong-PIN lockout was timed with the phone's normal clock. Moving the date forward in Settings ended every wait, which made trying all 10,000 PINs practical.

**Solution:**
- The lockout now uses the time since the phone booted, which the user can't change.
- Rebooting doesn't help either: after a reboot the full remaining wait starts again.
- Lockouts from older app versions carry over.

### 2.5 A third-party relay key shipped in the app (Medium)

**Problem:** "Fill on computer" used a third-party relay service whose API key was built into the app and the extension. Anyone could extract it, use up the quota, cut off the service, or watch connection metadata. Logins themselves stayed safe, because they were end-to-end encrypted.

**Solution:** Ledger now has its own relay: a small Cloudflare Worker (`relay/`).
- It needs no API key. A random 16-character room ID, new for each pop-up, is the only address.
- It only forwards encrypted messages between the phone and the extension in the same room, and never sees the key.
- It refuses connections from web pages. Only the extension and the app can connect.
- **Limits:** 4 connections per room, 16 KB per message, 20 messages per connection, and every room closes after 10 minutes.
- The app accepts only secure (`wss://`) relay addresses and valid room IDs, so a crafted QR code can't redirect it.

### 2.6 Login names stored unencrypted (Medium, accepted for now)

**Problem:** Passwords and 2FA secrets are encrypted, but login names, usernames and the unlock history are stored as plain text in the app's private database. Someone with root access to the phone could see which accounts exist.

**Status:** accepted for now. The database is private to the app, and the sensitive values are encrypted. Full database encryption (SQLCipher) is the planned approach if this is revisited.

### 2.7 The duress PIN left traces (Low)

**Problem:** The unlock history showed "Unlocked with duress PIN", and the app's storage only contained a duress PIN entry when one had been set. Both revealed that a duress PIN existed or had been used.

**Solution:**
- Duress unlocks are recorded exactly like normal PIN unlocks, including older entries.
- The duress PIN slot is never empty. Without a duress PIN it holds a random decoy hash that no PIN can open, and it looks the same as a real one.

### 2.8 The extension filled hidden password fields (Low)

**Problem:** The extension filled every password field on the page, including invisible ones. A malicious page could hide a decoy field to collect the password silently. Unrelated password fields elsewhere on the page were also filled.

**Solution:**
- The extension now fills only fields a person can see and type into. It skips fields that are hidden, transparent, clipped, tiny or placed off-screen.
- It fills only the login form that contains the visible password field.
- It picks the username field nearest to the password field.
- `docs/test-pages/fill-test.html` contains a real login form, eight decoy fields and an unrelated form, to show that only the real fields are filled.

### 2.9 "Hide screen contents" didn't really work (Low)

**Problem:** Most screens forced screenshot blocking on by themselves, so turning the setting off only lasted until the next screen opened. The setting didn't do what it said.

**Solution:**
- One central switch now controls screenshot blocking and the blank recent-apps preview for every screen and pop-up in the app.
- It is on by default.
- Turning it off asks for confirmation.

### 2.10 Release builds were easy to reverse-engineer (Low)

**Problem:** The published app contained every class and method under its original, descriptive name, plus unused code and debug logs. That made it easy to study and modify.

**Solution:**
- Release builds are now shrunk and obfuscated with R8: unused code and resources are removed, and names are replaced with short meaningless ones. The release app is about 3.6 MB.
- Debug and info logs are removed from release builds.
- Obfuscation only slows an attacker down. Ledger's protection still comes from encryption, the Android Keystore and the PIN checks, not from hiding the code.
