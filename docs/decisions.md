# Decisions

## 1. Engine: sing-tun + sing SOCKS5 + UoT v2 in a small Go module, not libbox, not hev-socks5-tunnel

The phone has no network of its own. Its only path to the host is `adb reverse`, which carries
TCP and nothing else, so UDP (DNS, QUIC, games, WebRTC) has to ride inside a TCP stream that the
host's sing-box understands.

- **hev-socks5-tunnel** is small (a few hundred KB of C) but carries UDP either as SOCKS5
  `UDP ASSOCIATE` (real UDP datagrams to the proxy, impossible over `adb reverse`) or as its own
  UDP-in-TCP extension (`FWD UDP`, command `0x05`), which only hev-socks5-server speaks. A sing-box
  mixed inbound rejects it. Using it would mean running a second proxy on the host.
- **libbox** (sing-box's mobile library) speaks the right protocol, but its Android surface is the
  whole sing-box client: a 30-method `PlatformInterface` (Tailscale, SSH shells, bridges, WIFI
  state, notifications) that changes with every release, a command server, and ~40-60 MB of
  native code per ABI with the upstream build tags.
- **This module** (`engine/`, ~250 lines) uses the same libraries libbox uses for exactly this
  path: sing-tun's gVisor stack reads the tun fd, every TCP flow becomes a SOCKS5 `CONNECT`, every
  UDP flow becomes a SOCKS5 `CONNECT` to `sp.v2.udp-over-tcp.arpa` carrying sing UoT v2 frames.
  That is byte for byte what a sing-box `socks` outbound with `udp_over_tcp: true` sends, and
  sing-box's `socks` and `mixed` inbounds decode it natively (`uot.NewRouter`), so the host needs
  no extra configuration for UDP. The Kotlin side sees three classes (`Engine.start`, `Session`,
  `Stats`). The native library is ~9 MB per ABI (~3.5 MB compressed).

Costs: we track sing-tun's pre-release pseudo-versions, so the `sing` group in Renovate is not
auto-merged; pin it to what a sing-box release tag uses. Everything that is not TCP or UDP
(ICMP) is rejected, so `ping` fails while the tunnel is up.

## 2. Control over a broadcast receiver guarded by `android.permission.DUMP`

The VpnService must be declared with `android.permission.BIND_VPN_SERVICE`, which only the
system holds, so `am start-foreground-service` from the adb shell cannot reach it. Control goes
through `ControlReceiver` instead. `am broadcast` waits for the receiver and prints its result
code and data, which gives the Holtdroid agent a synchronous answer with the final state (the
receiver waits up to 8 s for the tunnel to settle).

The receiver and `ConsentActivity` require `android.permission.DUMP`. The adb shell user holds
it; ordinary apps cannot get it, so the app under test cannot stop the tunnel or point it
elsewhere. The launcher activity only shows status.

Starting the foreground service from a receiver in the background is allowed because the app
holds the `ACTIVATE_VPN` app op (`REASON_OP_ACTIVATE_VPN` in the background-start exemptions).

## 3. Consent through `appops set <pkg> ACTIVATE_VPN allow`

`VpnService.prepare()` returns `null` when the app holds the `ACTIVATE_VPN` app op, which is
exactly what the consent dialog records. The adb shell can set it, so no tap is needed. If a
device refuses it (some OEM builds), `START` answers with result `2` and the agent opens
`ConsentActivity` with the same extras; the system dialog appears on the phone's screen and the
tunnel starts after it is accepted.

## 4. DNS resolves on the host

The tun announces `172.19.0.2` and `fdfe:dcba:9876::2` as DNS servers. Queries to them travel
like any other UDP, so they reach the host's sing-box addressed to a private IP nobody answers.
The host's route has to hijack them (`"port": 53, "action": "hijack-dns"` on the phone's inbound),
so names resolve with the session location's DNS settings and never leak. The `dns` extra
replaces the announced servers when the host prefers a real resolver reached through the
location outbound.

## 5. Working without an underlying network

With Wi-Fi and mobile data off the VPN has no underlying network, and Android then reports no
usable connectivity to apps. As gnirehtet does, the service sets the VPN's own network as its
underlying network once it appears, so apps see a connected network. The service excludes its
own package (`addDisallowedApplication`) and dials the proxy on loopback, so the proxy connection
never loops into the tun.

## 6. Foreground service type `specialUse`

Android 14 requires a type on every foreground service. `systemExempted` would fit a VPN but
has runtime prerequisites that differ between releases; `specialUse` (subtype `vpn`) has none
and Play review does not apply to this app. The notification channel is `IMPORTANCE_MIN`.

## 7. No AndroidX

The app is a service, a receiver and two tiny activities, all on framework APIs. That keeps the
APK down to the engine plus a few kilobytes of Kotlin.

## 8. A client's half-close never reaches the host

`adb reverse` has no TCP half-close: when one side shuts down writing, adbd closes the whole
stream. A client that sends its request and then calls `shutdown(SHUT_WR)` (netcat, some
HTTP/1.0 clients) would lose the response if the engine passed the half-close on, as sing's
`CopyConn` does. The engine's `relay` keeps the upstream open after the client half-closes,
reads the response until the server closes, and gives up after 2 minutes without data.
