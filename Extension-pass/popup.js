// --- 1. CRYPTOGRAPHY ENGINE ---

// Generate a secure, random 256-bit (32 byte) AES key
function generateEphemeralKey() {
    const keyArray = new Uint8Array(32);
    window.crypto.getRandomValues(keyArray);
    // Convert the raw bytes into a Base64 string so it fits nicely in a QR code
    return btoa(String.fromCharCode.apply(null, keyArray));
}

// Generate a random 16-character Room ID from the CSPRNG
function generateRoomId() {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 32 chars, so byte % 32 has no bias
    const bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    return Array.from(bytes, b => alphabet[b % alphabet.length]).join("");
}

// The native Web Crypto engine to decrypt the Android payload
async function decryptPayload(base64Ciphertext, base64Iv, base64Key) {
    try {
        const keyBuffer = Uint8Array.from(atob(base64Key), c => c.charCodeAt(0));
        const ivBuffer = Uint8Array.from(atob(base64Iv), c => c.charCodeAt(0));
        const cipherBuffer = Uint8Array.from(atob(base64Ciphertext), c => c.charCodeAt(0));

        const cryptoKey = await window.crypto.subtle.importKey(
            "raw", keyBuffer, { name: "AES-GCM" }, false, ["decrypt"]
        );

        const decryptedBuffer = await window.crypto.subtle.decrypt(
            { name: "AES-GCM", iv: ivBuffer }, cryptoKey, cipherBuffer
        );

        return new TextDecoder().decode(decryptedBuffer);
    } catch (error) {
        console.error("Decryption Failed! The payload was tampered with or the key is wrong.", error);
        return null;
    }
}


// --- 1b. INSTALL IDENTITY (lets the phone recognise this computer) ---
//
// Each install creates its own ECDSA P-256 key pair once. The private key is non-extractable and
// lives in this extension's IndexedDB, which web pages cannot read. QR codes are signed with it, so
// the phone can tell a computer it has filled before from a phishing page showing a look-alike code.

function bytesToBase64(bytes) {
    let binary = "";
    for (const b of bytes) binary += String.fromCharCode(b);
    return btoa(binary);
}

function openIdentityDb() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open("ledger-identity", 1);
        request.onupgradeneeded = () => request.result.createObjectStore("keys");
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
}

async function getInstallIdentity() {
    const db = await openIdentityDb();
    const read = () => new Promise((resolve, reject) => {
        const req = db.transaction("keys", "readonly").objectStore("keys").get("signing");
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    });
    let pair = await read();
    if (!pair) {
        pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
        await new Promise((resolve, reject) => {
            const tx = db.transaction("keys", "readwrite");
            tx.objectStore("keys").put(pair, "signing");
            tx.oncomplete = resolve;
            tx.onerror = () => reject(tx.error);
        });
    }
    return pair;
}

// Exactly what gets signed; the phone rebuilds the same bytes (BridgeTrust.signingMessage)
function signingMessage(room, key, ts) {
    return new TextEncoder().encode(`ledger-qr-v2|${room}|${key}|${ts}`);
}

// Three check words from SHA-256(public key || room), shown here and on the phone
async function checkWordsFor(publicKeyBytes, room, words) {
    const roomBytes = new TextEncoder().encode(room);
    const data = new Uint8Array(publicKeyBytes.length + roomBytes.length);
    data.set(publicKeyBytes, 0);
    data.set(roomBytes, publicKeyBytes.length);
    const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
    return [words[hash[0]], words[hash[1]], words[hash[2]]];
}

async function loadCheckWordList() {
    const text = await (await fetch("lib/check_words.txt")).text();
    return text.split("\n").map(w => w.trim()).filter(Boolean);
}

// Signed v2 QR payload; falls back to the old unsigned form if the identity can't be loaded
async function buildQrPayload(room, key) {
    try {
        const identity = await getInstallIdentity();
        const publicKey = new Uint8Array(await crypto.subtle.exportKey("raw", identity.publicKey));
        const ts = Date.now();
        const signature = new Uint8Array(await crypto.subtle.sign(
            { name: "ECDSA", hash: "SHA-256" }, identity.privateKey, signingMessage(room, key, ts)));
        const words = await checkWordsFor(publicKey, room, await loadCheckWordList());
        return {
            json: JSON.stringify({ v: 2, room, key, pk: bytesToBase64(publicKey), ts, sig: bytesToBase64(signature) }),
            words
        };
    } catch (e) {
        console.warn("Ledger: couldn't sign the code; showing an unsigned one.", e);
        return { json: JSON.stringify({ room, key }), words: null };
    }
}


// --- 2. EXTENSION INITIALIZATION ---

// Lock in the session variables instantly
const roomId = generateRoomId();
const sessionKey = generateEphemeralKey();

// Ledger's own relay (relay/ in the app repo): no API key, the room ID is the address.
// The address comes from config.js (git-ignored; template in config.example.js). Returns null when
// config.js is missing, empty, still the placeholder, or not a wss:// address, so the popup can say so.
function relayUrlFor(room) {
    const base = (typeof LEDGER_CONFIG !== "undefined" && LEDGER_CONFIG && typeof LEDGER_CONFIG.RELAY_URL === "string")
        ? LEDGER_CONFIG.RELAY_URL.trim()
        : "";
    if (!/^wss:\/\/[^\s<>]+$/.test(base)) return null;
    return `${base.replace(/\/+$/, "")}/v1/room/${room}`;
}

const RELAY_URL = relayUrlFor(roomId);

const statusBox = document.getElementById('status-text');
const statusMessage = document.getElementById('status-message');
const statusIcon = document.getElementById('status-icon');
const qrImage = document.getElementById('qr-code');
const qrPlaceholder = document.getElementById('qr-placeholder');

// Material Symbols Rounded paths (Apache 2.0) for the status line
const STATUS_ICONS = {
    waiting: "M520-496v-144q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v159q0 8 3 15.5t9 13.5l132 132q11 11 28 11t28-11q11-11 11-28t-11-28L520-496ZM480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-400Zm0 320q133 0 226.5-93.5T800-480q0-133-93.5-226.5T480-800q-133 0-226.5 93.5T160-480q0 133 93.5 226.5T480-160Z",
    success: "m424-408-86-86q-11-11-28-11t-28 11q-11 11-11 28t11 28l114 114q12 12 28 12t28-12l226-226q11-11 11-28t-11-28q-11-11-28-11t-28 11L424-408Zm56 328q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z",
    error: "M480-280q17 0 28.5-11.5T520-320q0-17-11.5-28.5T480-360q-17 0-28.5 11.5T440-320q0 17 11.5 28.5T480-280Zm0-160q17 0 28.5-11.5T520-480v-160q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v160q0 17 11.5 28.5T480-440Zm0 360q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z"
};

// One place that changes the status line: state is "waiting", "success" or "error"
function setStatus(state, message) {
    if (!statusBox) return; // no popup DOM (e.g. popup.html changed); nothing to update
    statusBox.dataset.state = state;
    statusIcon.setAttribute("d", STATUS_ICONS[state]);
    statusMessage.textContent = message;
}

// No QR code can be shown: say why instead of leaving an empty box
function showNoCode(message) {
    if (qrPlaceholder) qrPlaceholder.textContent = "No code yet";
    setStatus("error", message);
}

setStatus("waiting", "Connecting…");

let socket = null;
if (!RELAY_URL) {
    showNoCode("Ledger isn't set up yet. Copy config.example.js to config.js, add your relay address, then reload the extension.");
} else {
    try {
        socket = new WebSocket(RELAY_URL);
    } catch (e) {
        console.error("Ledger: invalid relay address", e);
        showNoCode("The relay address in config.js isn't valid. Fix it, then reload the extension.");
    }
}

if (socket) {
    socket.onerror = function() {
        showNoCode("Couldn't connect. Check your internet connection, then close and reopen Ledger.");
    };

    socket.onopen = async function() {
        setStatus("waiting", "Waiting for your phone…");

        try {
            // Room ID, AES key and this install's signature go into the QR code.
            // Rendered locally (lib/qrcode.js) so the key never leaves this machine.
            const payload = await buildQrPayload(roomId, sessionKey);
            const qr = qrcode(0, 'M');
            qr.addData(payload.json);
            qr.make();
            qrImage.src = qr.createDataURL(4, 0);
            qrImage.hidden = false;
            qrPlaceholder.hidden = true;

            // The phone shows the same words; they only ever appear here, in the toolbar pop-up
            const checkWords = document.getElementById("check-words");
            if (payload.words) {
                document.getElementById("check-words-value").textContent = payload.words.join(" · ");
                checkWords.hidden = false;
            }
        } catch (e) {
            // e.g. the browser blocks the extension's storage, so the signing key can't be loaded
            console.error("Ledger: couldn't build the QR code", e);
            showNoCode("Couldn't create the code. Close and reopen Ledger; if it keeps happening, reload the extension.");
            socket.close();
        }
    };

    socket.onmessage = onRelayMessage;
}


// --- 3. THE SECURE LISTENER ---

async function onRelayMessage(event) {
    try {
        const incomingData = JSON.parse(event.data);

        // We now expect { room, payload (ciphertext), iv }; the payload decrypts to {"u": username, "p": password}
        if (incomingData.room === roomId && incomingData.payload && incomingData.iv) {

            setStatus("waiting", "Receiving your login…");

            // Feed the ciphertext and IV into the decryption engine using our optical key
            const decrypted = await decryptPayload(incomingData.payload, incomingData.iv, sessionKey);

            let username = incomingData.username; // Legacy app builds sent the username in plaintext
            let decryptedPassword = decrypted;
            let siteName = null;                  // Login name from the phone; newer app builds only
            if (decrypted) {
                try {
                    const bundle = JSON.parse(decrypted);
                    if (bundle && typeof bundle.p === "string") {
                        username = bundle.u;
                        decryptedPassword = bundle.p;
                        siteName = typeof bundle.t === "string" ? bundle.t : null;
                    }
                } catch (e) {
                    // Not JSON: legacy payload that holds only the password
                }
            }

            if (decryptedPassword) {
                chrome.tabs.query({active: true, currentWindow: true}, function(tabs) {
                    confirmAndFill(tabs[0], username, decryptedPassword, siteName);
                });
            } else {
                setStatus("error", "Couldn't read what your phone sent. Close and reopen Ledger, then scan the new code.");
            }
        }
    } catch (e) {
        console.error("JSON Parse Error", e);
    }
}


// --- 4. CONFIRM WHICH SITE ---

// Main name of a site, the same rule the app uses when naming saved logins (DomainFormatter):
// "accounts.google.com" -> "google", "www.bbc.co.uk" -> "bbc", "github-login.evil.com" -> "evil".
function siteNameFromHost(host) {
    const parts = host.toLowerCase().replace(/^www\./, "").split(".");
    if (parts.length < 2) return parts[0] || "";
    const last = parts.length - 1;
    const doubleTld = parts.length > 2 && parts[last].length <= 2 && ["co", "com", "org"].includes(parts[last - 1]);
    return doubleTld ? parts[last - 2] : parts[last - 1];
}

function normalizeName(name) {
    return (name || "").toLowerCase().replace(/[^a-z0-9]/g, "");
}

function hostOf(tab) {
    try {
        return new URL(tab.url).hostname;
    } catch (e) {
        return "";
    }
}

// Always ask before filling. If the login's name doesn't match the page, warn and make
// "Don't fill" the main button, but still let the user choose (custom login names exist).
function confirmAndFill(tab, username, password, siteName) {
    const host = hostOf(tab);
    const pageName = siteNameFromHost(host);
    const matches = siteName !== null && pageName !== "" && normalizeName(siteName) === normalizeName(pageName);
    const mismatch = siteName !== null && !matches;

    const pairing = document.getElementById("pairing");
    const panel = document.getElementById("confirm");
    const title = document.getElementById("confirm-title");
    const detail = document.getElementById("confirm-detail");
    const warning = document.getElementById("confirm-warning");
    const primary = document.getElementById("btn-primary");
    const secondary = document.getElementById("btn-secondary");

    const loginLabel = siteName ? `your ${siteName} login` : "this login";
    const who = username ? username : "no username";
    title.textContent = `Fill ${loginLabel}?`;
    detail.textContent = host ? `${who} into ${host}` : who;

    warning.hidden = !mismatch;
    if (mismatch) {
        warning.textContent = `This page is ${host || "not a website"}, not ${siteName}. Only fill it if you trust this page.`;
    }

    let fill, cancel;
    if (mismatch) {
        primary.textContent = "Don't fill";
        secondary.textContent = "Fill anyway";
        primary.onclick = () => cancel();
        secondary.onclick = () => fill();
    } else {
        primary.textContent = "Fill";
        secondary.textContent = "Cancel";
        primary.onclick = () => fill();
        secondary.onclick = () => cancel();
    }

    const close = () => {
        panel.hidden = true;
        password = null; // drop the secret as soon as we're done with it
    };

    fill = () => {
        const toFill = password;
        close();
        chrome.scripting.executeScript({
            target: {tabId: tab.id},
            func: injectCredentials,
            args: [username, toFill]
        }, function() {
            // e.g. browser pages (chrome://) and the web store can't be filled by extensions
            if (chrome.runtime.lastError) {
                setStatus("error", "Ledger can't fill this page. Open the site's login page and try again.");
            } else {
                setStatus("success", "Filled. Check the page, then sign in.");
            }
        });
    };

    cancel = () => {
        close();
        setStatus("waiting", "Not filled. Scan again from your phone when you're on the right page.");
        pairing.hidden = false;
    };

    pairing.hidden = true;
    panel.hidden = false;
    setStatus(mismatch ? "error" : "waiting", mismatch ? "Check the site before filling." : "Confirm to fill this page.");
    primary.focus();
}


// --- 5. FILLING THE PAGE ---

function injectCredentials(username, password) {

    // Only fill fields a person can actually see and type into. Pages can hide
    // decoy ("honeypot") password inputs to catch autofill, so hidden, zero-size,
    // transparent, clipped or off-page fields are skipped.
    function isFillable(input) {
        if (!input || !input.isConnected || input.disabled || input.readOnly) return false;
        if ((input.type || '').toLowerCase() === 'hidden') return false;
        if (input.closest('[hidden], [aria-hidden="true"], [inert]')) return false;

        for (let el = input; el && el.nodeType === 1; el = el.parentElement) {
            const style = window.getComputedStyle(el);
            if (style.display === 'none') return false;
            if (style.visibility === 'hidden' || style.visibility === 'collapse') return false;
            if (parseFloat(style.opacity) < 0.1) return false;
            if (style.clip === 'rect(0px, 0px, 0px, 0px)') return false;
            if (/inset\(\s*50%/.test(style.clipPath)) return false;
        }

        if (input.getClientRects().length === 0) return false;
        const rect = input.getBoundingClientRect();
        if (rect.width < 4 || rect.height < 4) return false;

        // Entirely outside the page (e.g. left: -9999px)
        const doc = document.documentElement;
        const left = rect.left + window.scrollX;
        const top = rect.top + window.scrollY;
        if (left + rect.width <= 0 || top + rect.height <= 0) return false;
        if (left >= Math.max(doc.scrollWidth, doc.clientWidth)) return false;
        if (top >= Math.max(doc.scrollHeight, doc.clientHeight)) return false;

        return true;
    }

    function typeOf(input) {
        return (input.type || 'text').toLowerCase();
    }

    function setValue(field, value) {
        field.value = value;
        field.dispatchEvent(new Event('input', { bubbles: true }));
        field.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function isUsernameType(input) {
        const type = typeOf(input);
        return type === 'text' || type === 'email' || type === 'tel';
    }

    function fillablePasswordFields() {
        return Array.from(document.querySelectorAll('input[type="password" i]')).filter(isFillable);
    }

    // Best username field within `scope`, before `beforeField` when given.
    // Prefers inputs the page marks as username/email.
    function findUsernameField(scope, beforeField) {
        let candidates = Array.from(scope.querySelectorAll('input'))
            .filter(input => isUsernameType(input) && isFillable(input));
        if (beforeField) {
            candidates = candidates.filter(input =>
                input.compareDocumentPosition(beforeField) & Node.DOCUMENT_POSITION_FOLLOWING);
        }
        if (candidates.length === 0) return null;

        const marked = candidates.filter(input => {
            const hint = (input.getAttribute('autocomplete') || '').toLowerCase();
            return hint.split(/\s+/).includes('username') || hint.split(/\s+/).includes('email');
        });
        const pool = marked.length > 0 ? marked : candidates;
        // Nearest one to the password field (or the first one on the page)
        return beforeField ? pool[pool.length - 1] : pool[0];
    }

    function firePayload() {
        const passFields = fillablePasswordFields();
        if (passFields.length === 0) return false;

        // Stay inside the login form: never fill password fields elsewhere on the page.
        const first = passFields[0];
        const scope = first.form || document;
        const targets = passFields.filter(field => (field.form || document) === scope);

        targets.forEach(field => setValue(field, password));

        const userField = findUsernameField(scope, first);
        if (userField) setValue(userField, username);
        return true;
    }

    // --- PHASE 1: Fill now if the login form is already visible ---
    // (No alert on the page: the Ledger popup already says "Filled".)
    if (firePayload()) {
        return;
    }

    // --- PHASE 2: Two-step logins (username first, password appears later) ---
    console.info("Ledger: no visible password field yet; waiting for one to appear.");

    const userField = findUsernameField(document, null);
    if (userField) {
        setValue(userField, username);
    }

    // Watch for a visible password field. Attribute changes matter too, since
    // pages often un-hide an existing field instead of adding a new one.
    let pending = false;
    const observer = new MutationObserver((mutations, obs) => {
        if (pending) return;
        pending = true;
        setTimeout(() => {
            pending = false;
            if (fillablePasswordFields().length > 0) {
                console.info("Ledger: password field appeared; filling it.");
                obs.disconnect();
                firePayload();
            }
        }, 50);
    });

    observer.observe(document.body, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['style', 'class', 'hidden', 'type', 'aria-hidden', 'disabled']
    });

    // Stop watching if the user never gets to the password step.
    setTimeout(() => {
        observer.disconnect();
        console.info("Ledger: stopped waiting for a password field after 15 seconds.");
    }, 15000);
}
