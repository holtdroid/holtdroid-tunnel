<h1 align="center">Holtdroid Tunnel</h1>

<p align="center">Sends all of an Android phone's traffic over USB to the host, no root needed.</p>

Holtdroid Tunnel is the network path for real phones in
[Holtdroid](https://github.com/kriakiku/holtdroid), the self-hosted portal for Android test
devices. It is a VpnService that captures every app's IPv4 and IPv6 traffic and hands it to a
SOCKS5 proxy on the phone's `127.0.0.1`, which `adb reverse` maps to a sing-box on the host. The
phone needs no Wi-Fi and no mobile data: internet and ADB share the USB cable, and the host
decides where the traffic exits (the session's VPN location).

```
app ──▶ tun (VpnService) ──▶ gVisor stack ──▶ SOCKS5 127.0.0.1:<port>   (phone)
                                                   │ adb reverse
                                                   ▼
                                        sing-box mixed inbound          (host)
                                                   ▼
                                           location outbound
```

TCP flows become SOCKS5 `CONNECT`s. UDP flows (DNS, QUIC) travel as sing
[UDP-over-TCP v2](https://sing-box.sagernet.org/configuration/shared/udp-over-tcp/), which
sing-box's `mixed` and `socks` inbounds accept without extra configuration. ICMP is not carried.

Apps can tell a VPN is active (`TRANSPORT_VPN`); that is the price of not needing root.

## Install

Download `holtdroid-tunnel-<version>.apk` from
[Releases](https://github.com/kriakiku/holtdroid-tunnel/releases). It carries the engine for
`arm64-v8a`, `armeabi-v7a` and `x86_64`.

```sh
adb install -r holtdroid-tunnel-0.1.0.apk
```

## Quick start

```sh
P=io.github.kriakiku.holtdroid.tunnel

adb shell appops set $P ACTIVATE_VPN allow     # pre-grant the VPN consent, once per install
adb reverse tcp:1080 tcp:18080                 # phone 127.0.0.1:1080 -> host 127.0.0.1:18080

adb shell am broadcast -n $P/.ControlReceiver -a $P.START --ei port 1080
adb shell am broadcast -n $P/.ControlReceiver -a $P.STATUS
adb shell am broadcast -n $P/.ControlReceiver -a $P.STOP
```

## Control contract

Everything is driven from the adb shell; nothing needs a tap once the consent is pre-granted.
The Holtdroid agent relies on what follows. Package and actions:

| | |
|---|---|
| package | `io.github.kriakiku.holtdroid.tunnel` |
| receiver | `io.github.kriakiku.holtdroid.tunnel/.ControlReceiver` |
| consent activity | `io.github.kriakiku.holtdroid.tunnel/.ConsentActivity` |
| actions | `io.github.kriakiku.holtdroid.tunnel.START`, `.STOP`, `.STATUS` |
| logcat tag | `HoltdroidTunnel` |

The receiver and the consent activity require `android.permission.DUMP`, which the adb shell
holds and apps cannot get, so the app under test cannot stop or redirect the tunnel. Always pass
the component with `-n`. The VpnService itself is guarded by `BIND_VPN_SERVICE` and cannot be
started with `am start-foreground-service`; `START` starts it.

### START

Starts the tunnel, or restarts it with the new settings if it is already running. The
broadcast returns once the tunnel is running or has failed, after 8 s at most.

| Extra | Type | Default | Meaning |
|---|---|---|---|
| `port` | int (`--ei`) or string | `1080` | SOCKS5 port on the phone, the device side of `adb reverse` |
| `host` | string | `127.0.0.1` | proxy address, an IP literal (`::1` works) |
| `udp` | bool (`--ez`) or string | `true` | carry UDP as UoT v2; `false` rejects all UDP, DNS included |
| `ipv6` | bool or string | `true` | add an IPv6 address and the `::/0` route |
| `dns` | string list | `172.19.0.2`, `fdfe:dcba:9876::2` | DNS servers announced to apps |
| `allow` | string list | | only these packages use the tunnel |
| `deny` | string list | | these packages bypass the tunnel |
| `mtu` | int or string | `9000` | tun MTU, 1280 to 65535 |

- Lists are a string separated by commas, spaces or semicolons (`--es deny a.b,c.d`) or a string
  array (`--esa deny a.b,c.d`). Booleans also accept `1`/`0`, `yes`/`no`, `on`/`off`.
- `allow` and `deny` cannot be combined. Without `allow`, every app except the tunnel itself goes
  through it. An `allow` package that is not installed fails the start. A missing `deny` package
  is skipped with a warning.
- Unknown extras are rejected, so a typo fails loudly instead of being ignored.
- The settings are saved and reused when the system restarts the service (process death,
  always-on VPN).

### STOP

Stops the tunnel and tears down the VPN. Returns once it is stopped.

### STATUS

Returns the current state. When a config is known it also probes the proxy with a SOCKS5
greeting, so the agent can tell a missing `adb reverse` from a host that is down.

### Results

`am broadcast` prints the receiver's result code and data on its last line:

```
Broadcast completed: result=-1, data="{"state":"running", ...}"
```

The data is the JSON between the first `data="` and the final `"` of that line (am does not
escape it). Result codes:

| result | meaning |
|---|---|
| `-1` | done: the tunnel is running after `START`, stopped after `STOP`, any state after `STATUS` |
| `1` | failed: bad extras, unknown action or the tunnel did not come up; see `error` |
| `2` | the VPN consent is missing; see [Consent](#consent) |

JSON fields (all present, `null` when unknown):

```json
{
  "state": "running",
  "version": "0.1.0",
  "consent": true,
  "since": 1792350199888,
  "error": null,
  "proxy": "ok",
  "config": {
    "host": "127.0.0.1", "port": 1080, "mtu": 9000, "ipv6": true, "udp": true,
    "dns": ["172.19.0.2", "fdfe:dcba:9876::2"], "allow": [], "deny": []
  },
  "traffic": {
    "tcp_active": 7, "tcp_total": 69, "udp_active": 6, "udp_total": 30,
    "uplink": 452241, "downlink": 26500844, "failures": 24,
    "last_error": "tcp [2001:db8::1]:443: socks5: request rejected, code=4"
  }
}
```

| Field | |
|---|---|
| `state` | `stopped`, `starting`, `running` or `failed` |
| `consent` | whether the app holds the VPN consent |
| `since` | when the tunnel came up, Unix ms |
| `error` | why the request or the tunnel failed; `revoked by the system or another VPN app` after a revoke |
| `proxy` | `ok`, `unreachable: ...` (nothing listens on the phone port: no `adb reverse`), `closed by peer: ...` (`adb reverse` is set but nothing listens on the host), `not a SOCKS5 proxy ...`, `proxy requires authentication ...` |
| `config` | the settings in effect, with the DNS servers resolved |
| `traffic` | counters since the tunnel came up; `failures` counts flows the proxy refused, `last_error` is the latest one |

On result `1` the `state` field still describes the tunnel as it is (a rejected `START` leaves
a running tunnel running).

Other ways to read the state:

```sh
adb shell dumpsys activity service io.github.kriakiku.holtdroid.tunnel/.TunnelService   # same JSON, last line
adb logcat -s HoltdroidTunnel                                                           # every request and its reply
```

### Consent

Android asks the user once before an app may run a VPN. The adb shell can record that answer:

```sh
adb shell appops set io.github.kriakiku.holtdroid.tunnel ACTIVATE_VPN allow
```

It survives `adb install -r` and is lost on uninstall. If a device refuses the app op, `START`
answers with result `2`. Then open the system dialog with the same extras; the tunnel starts as
soon as someone accepts it on the phone's screen, and accepting also records the consent:

```sh
adb shell am start -n io.github.kriakiku.holtdroid.tunnel/.ConsentActivity --ei port 1080
```

Only one VPN can run per user, so starting the tunnel revokes any other VPN app and vice versa.
The tunnel declares always-on support, so a device-owner app can pin it with lockdown.

## Host side

The host runs a sing-box with a `mixed` (or `socks`) inbound on loopback, no authentication,
and the port `adb reverse` points to. DNS from the phone arrives at `172.19.0.2:53` and
`[fdfe:dcba:9876::2]:53`, addresses nobody answers, so the route must hijack it and resolve with
the host's DNS settings:

```json
{
  "inbounds": [
    { "type": "mixed", "tag": "phone", "listen": "127.0.0.1", "listen_port": 18080 }
  ],
  "route": {
    "rules": [
      { "inbound": ["phone"], "port": 53, "action": "hijack-dns" }
    ]
  }
}
```

If the host has no IPv6 egress, IPv6 flows are refused at once (SOCKS5 reply 4) and apps fall
back to IPv4; pass `--ez ipv6 false` to skip IPv6 entirely. Android's private DNS probes
`172.19.0.2:853` and falls back to plain DNS when it is refused.

## Build

The toolchain is JDK 21 and Go (both pinned in `mise.toml`), the Android SDK with platform 37.2
and the NDK named by `ndkVersion` in `gradle.properties`.

```sh
./gradlew testDebugUnitTest lintDebug
./gradlew assembleDebug                          # app/build/outputs/apk/debug
./gradlew assembleDebug -PengineAbi=arm64-v8a    # one ABI, faster
./gradlew assembleRelease -PappVersion=1.2.3
(cd engine && go test -tags with_gvisor ./...)
```

`:app:bindEngine` builds `engine/` with gomobile into an AAR; `go` must be on `PATH`. Release
builds are signed when `SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD` are set, and with the debug key otherwise.

Why the engine is built this way, and the other design choices, are in
[docs/decisions.md](docs/decisions.md).

## Releases

Every push to `main` runs `.github/workflows/release.yml`. It reads the
[Conventional Commits](https://www.conventionalcommits.org) since the last tag: `feat` makes a
minor release, `fix` and `perf` a patch, a breaking change a major one; other types release
nothing. The workflow builds the APK, signs it with the release key when the
`SIGNING_KEYSTORE_BASE64` secret is set (the debug key otherwise), tags `vX.Y.Z` and publishes a
GitHub release with the APK and `SHA256SUMS`.

[Renovate](https://docs.renovatebot.com) keeps dependencies fresh (`renovate.json`). Gradle and
Go updates land as `fix(deps)` commits and ship as patch releases. The `sagernet` Go modules
move as one group and wait for review.

## License

Apache-2.0
