// Unit tests for the relay's pure rules. Run: node --test relay/test
import { test } from "node:test";
import assert from "node:assert/strict";
import { isAllowedOrigin, roomFromPath, LIMITS } from "../src/index.js";

test("accepts the room IDs the extension makes", () => {
    assert.equal(roomFromPath("/v1/room/ABCDEFGHJKLMNPQR"), "ABCDEFGHJKLMNPQR");
    assert.equal(roomFromPath("/v1/room/23456789ABCDEFGH"), "23456789ABCDEFGH");
});

test("rejects anything else", () => {
    for (const path of [
        "/", "/v1/room/", "/v1/room/SHORT",
        "/v1/room/abcdefghjkmnpqrs",          // lowercase
        "/v1/room/ABCDEFGHIJKLMNOP",          // I and O are never used
        "/v1/room/ABCDEFGHJKLMNPQR/extra",
        "/v1/room/ABCDEFGHJKLMNPQRS",         // 17 characters
        "/v3/1?api_key=x",
    ]) {
        assert.equal(roomFromPath(path), null, path);
    }
});

test("only extensions and the phone may connect", () => {
    assert.equal(isAllowedOrigin(null), true);                                   // phone app (OkHttp)
    assert.equal(isAllowedOrigin("chrome-extension://abcdefghijklmnop"), true);
    assert.equal(isAllowedOrigin("moz-extension://1234-5678"), true);
    assert.equal(isAllowedOrigin("https://github-login.evil.com"), false);      // a web page
    assert.equal(isAllowedOrigin("null"), false);
});

test("limits stay small", () => {
    assert.ok(LIMITS.maxPeersPerRoom <= 4);
    assert.ok(LIMITS.maxMessageBytes <= 16 * 1024);
    assert.ok(LIMITS.roomLifetimeMs <= 10 * 60 * 1000);
});
