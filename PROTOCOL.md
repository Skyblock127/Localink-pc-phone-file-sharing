# Wire protocol and security design

Version 2. Implemented once, in `shared/src/fileshare/core/`, and compiled into both apps.

## Roles

The **phone listens**, the **laptop dials**, always — regardless of which way files are
about to move. On a hotspot or USB tether the phone is the laptop's default gateway, so
there is no discovery step, no mDNS, and nothing to configure.

The laptop collects every gateway candidate it can see (default routes from `route print`,
plus `.1` and `.129` on each private /24 it is sitting on) and dials them all in parallel.
Racing them is what makes hotspot and USB interchangeable, since with both up there are two
live paths on different subnets and no reliable way to know which one Windows prefers.
A wrong candidate is harmless: it fails the handshake.

## Identity

Each device holds a long-lived EC P-256 keypair and a self-signed certificate. The
certificate exists only because TLS needs one to carry a public key — no CA, hostname or
expiry is ever trusted. Identity is `SHA-256(SubjectPublicKeyInfo)`, pinned at pairing.

**Laptop** stores it as a PKCS#12 in `%LOCALAPPDATA%\FileShare`, locked to the user with
`icacls`.

**Phone** stores it encrypted with an AES-256-GCM key held in the hardware-backed Android
Keystore, marked `setUserAuthenticationRequired(true)` with a 300-second window covering
`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`. Gating only the UI would be cosmetic — a modified
build walks past it. Gating the key means that without your fingerprint or PIN the identity
cannot be decrypted, so the phone cannot authenticate and no transfer can occur.

`setInvalidatedByBiometricEnrollment` is deliberately **false**: otherwise enrolling a new
fingerprint silently destroys the pairing. Enrolling already requires the device PIN, which
is one of the factors that unlocks this key anyway.

## Transport

TLS 1.3 only, mutual authentication, cipher suites restricted to AES-GCM first with
ChaCha20-Poly1305 as fallback.

The TLS trust manager accepts any certificate **by design**. Trust is decided one layer up:
the peer's fingerprint is compared against the pinned value before a single byte of
anything else is exchanged. This avoids fighting JSSE over CA chains that mean nothing for
two devices that already know each other's exact public key.

## Handshake

```
laptop -> phone   HELLO         { version:i32, name:utf, mode:i32 }
```

The phone reads HELLO **before** inspecting the peer certificate: under TLS 1.3 the client
certificate arrives with the client's first flight after the server's Finished, so it is
only reliably available once something has been read.

**Normal mode (`mode = 0`):**

```
phone: look up fingerprint. Unknown -> close, no reply, no prompt.
phone -> laptop   HELLO_OK      { version:i32, name:utf }
laptop: verify phone fingerprint against pin (already done pre-HELLO).
```

An unknown device gets silence rather than a prompt. A prompt would be an
accept-by-reflex vector, and there is nothing useful for the user to decide.

**Pairing mode (`mode = 1`),** only while the pairing screen is open:

```
laptop -> phone   PAIR_PROOF_C  { proof:bytes }
phone -> laptop   PAIR_PROOF_S  { proof:bytes, name:utf }
```

where

```
psk    = PBKDF2-HMAC-SHA256(code, "fileshare-pair-v1", 120000, 256)
proof  = HMAC-SHA256(psk, "fileshare-pair-v1|" + role + "|"
                          + hex(SHA-256(clientCertDER)) + "|"
                          + hex(SHA-256(serverCertDER)))
```

The proof is **bound to both certificates**. A relaying attacker necessarily terminates TLS
on both sides and therefore presents different certificates than the endpoints see, so it
cannot produce a transcript that satisfies either end. This is what closes the
man-in-the-middle window that plain trust-on-first-use leaves open.

The code is 8 characters from a 32-symbol alphabet with no ambiguous glyphs (40 bits),
single-use, valid 90 seconds, and burned after one wrong attempt. There is no online
guessing to speak of. A typed code rather than a QR scan is a deliberate trade: it costs a
few seconds once, and the app never requests CAMERA permission.

## Rounds

Turn-based. The laptop offers, the phone answers and receives; then the phone
offers and the laptop receives. Either queue may be empty, and an empty round is
the heartbeat, so a dead path surfaces in about a second.

```
sender   -> OFFER        { n, [ id, name, size, destCode, sourceHint, mime ] }
receiver -> OFFER_REPLY  { n, [ id, accept, have ] }

  per accepted file:
sender   -> FILE_BEGIN   { id, offset, count }
sender   -> FILE_CHUNK   { bytes }      x N
sender   -> FILE_END     { id, sha256 }
receiver -> FILE_RESULT  { id, ok, message }
```

## Pausing, in band

File data is carried in **framed chunks** rather than a raw byte run, so between
any two chunks either end can interrupt without the connection being disturbed:

```
receiver -> PAUSE  { id }      or  CANCEL { id }
sender   -> FILE_PAUSED { id, done }   or  FILE_CANCELLED { id }
either   -> RESUME { id }
```

A reader thread owns the socket and splits arrivals into ordered protocol frames
and interrupt frames, which the engine polls between chunks. That queue is
**bounded**: an unbounded one lets the network outrun the disk and exhausts the
heap on a large file. Blocking the reader instead stalls the sender through TCP,
which is what TCP is for.

Version 1 stopped a transfer by dropping the socket. That meant every pause also
re-offered the whole queue and re-prompted for files already answered, and the two
ends could disagree about what was paused.

## Queue model

Whichever side is sending owns a plain queue.

* **Pause** removes the file from that queue.
* **Resume** puts it back at the end and sends `RESUME` so the other side agrees.
* **Cancel** removes it and discards the partial.

Because a paused file is not in the queue, it is never offered, so the receiving
side needs no notion of "paused" at all -- it only remembers what it has already
accepted or declined. That is what keeps the two devices from disagreeing.

## Destinations

The wire carries a **numeric code**, never a path string:

| code | folder | accepts |
|---|---|---|
| 0 | Downloads | anything |
| 1 | DCIM/Camera | image, video |
| 2 | Pictures | image |
| 3 | Movies | video |
| 4 | Music | audio |
| 5 | Documents | anything |

Destination path traversal is therefore structurally impossible rather than filtered. The
table is compile-time on both sides. The laptop validates MIME against the destination
before offering, because MediaStore rejects a mismatch at insert time and discovering that
after a multi-gigabyte transfer would be miserable.

## Filenames

The filename is the only attacker-influenced string that reaches a filesystem, so it goes
through a whitelist (`Sanitize.fileName`), applied **on the receiving side** because that is
the side that pays for getting it wrong. It handles, in order: directory separators and
traversal, drive letters and UNC paths, NUL and control characters, Unicode bidirectional
overrides, Windows reserved device names, NTFS alternate data streams, and the trailing
dots and spaces Windows silently strips.

Received files on the laptop get a `Zone.Identifier` alternate data stream so SmartScreen
still speaks up if you carry an installer across. Nothing is ever auto-opened.

## Resume

The receiver fsyncs every 64 MiB and records the committed offset alongside the partial. On
reopen it truncates back to that offset, so whatever survives is known-durable and a crash
costs at most one sync interval of re-sent data. No tail hashing, no chunk manifests.

The sender always reads its file from byte zero — it hashes the part it already sent but
only transmits from the resume point. Local storage is an order of magnitude faster than the
link, so this costs seconds and buys a genuine end-to-end integrity check.

Transfer ids are `SHA-256(absolutePath | size | mtime)` on the laptop, so editing a file
gives it a new id and a stale partial can never be appended to something that has changed.
Every file is verified against the sender's SHA-256 before being made visible; a mismatch
discards it.

On Android the partial is held with `IS_PENDING = 1`, which keeps it out of your gallery and
out of other apps until it is complete and verified. Partials older than seven days are
swept.

SHA-256 rather than BLAKE3: on ARMv8 and x86 it runs on hardware crypto instructions at over
1 GB/s, and a pure-Java BLAKE3 would be slower. It also means zero third-party dependencies.

## Source filtering

The phone accepts connections only from RFC1918 addresses, and explicitly refuses any
source that falls inside the Wi-Fi *client* network's subnets (read from
`ConnectivityManager`, no location permission needed). Pinned-key auth already stops anyone
else from getting anywhere, but there is no reason to even shake hands with the campus
network — and this is what makes leaving Wi-Fi switched on harmless rather than merely
untidy.

The laptop has **no listening port at all**; it only dials out. No inbound firewall rule is
required. Set the hotspot network to the **Public** profile in Windows.

## What is deliberately absent

No cloud relay, no account, no telemetry. No auto-update mechanism — a sideloaded app with a
self-update channel is a supply-chain backdoor you built yourself. No UPnP or port
forwarding, ever. No compression (the payload is already-compressed media and the CPU cost
would make it slower).
