<h1 align="center">Holtdroid Tunnel</h1>

<p align="center">Sends all of an Android phone's traffic over USB to the host, no root needed.</p>

Holtdroid Tunnel is the network path for real phones in
[Holtdroid](https://github.com/holtdroid/holtdroid), the self-hosted portal for Android test
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
[Releases](https://github.com/holtdroid/holtdroid-tunnel/releases). It carries the engine for
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
When the app is the device owner it pins itself as the always-on VPN, which also grants the
consent (see [Device owner](#device-owner)).

## Device owner

The same app can be the phone's device owner (DPC) and lock a shared test phone down: no
accounts, no factory reset, no new users, no outgoing calls or SMS, no VPN or private DNS
changes, no traffic outside the tunnel, system updates postponed, and a lock-task "soft kiosk"
per session. It never turns adb off.

### Provisioning

Device owner can only be set while the phone has no accounts and a single user:

```sh
adb shell dpm set-device-owner io.github.kriakiku.holtdroid.tunnel/.AdminReceiver
```

On success the app applies the default [LOCKDOWN](#lockdown) at once and re-applies the saved
one on every boot. `STATUS` reports it under `owner`.

Read this before provisioning a phone you care about:

- **Signing key.** A device owner can only be updated by an APK signed with the same key, and
  only `RELEASE` (or a factory reset) removes it. Provision with the release-signed APK, never
  with a debug build or a release built without the `SIGNING_*` secrets, or the phone is tied to
  the debug key.
- **Never install a build without `AdminReceiver` over it** (v0.1.x). Android then drops the
  active admin while keeping the owner record: every policy call fails and only `RELEASE` gets
  the phone out. `adb install -r -d` must not downgrade below the version that provisioned it.
- The device owner cannot be uninstalled (`DELETE_FAILED_DEVICE_POLICY_MANAGER`) or cleared with
  `pm clear`; keep it out of any cleanup lists anyway.
- Block the microphone and camera **before** provisioning (`cmd sensor_privacy enable 0 microphone`,
  `... camera`): the toggle restrictions freeze the toggles in whatever state they are in, for
  the adb shell too.

### Actions

All actions go to the same receiver as the tunnel's and follow the same rules: `-n` component,
`DUMP` permission, a synchronous result and JSON data.

| Action | Needs owner | Extras | Does |
|---|---|---|---|
| `LOCKDOWN` | yes | see below | applies the restrictions and policies, replacing the previous lockdown |
| `RELEASE` | yes | none | clears every restriction and policy, unhides packages, ends lock task, removes the always-on VPN, then gives up device owner |
| `CLEAR_PASSWORD` | yes | none | removes a lock screen PIN, pattern or password someone set, and disables the keyguard again |
| `SESSION_START` | yes | see below | starts lock task with the session app in front |
| `SESSION_END` | yes | none | ends lock task |
| `WALLPAPER` | no | `rows` or `rows_b64` | draws the Holtdroid wallpaper on the home and lock screens |

Result codes: `-1` done, `1` failed (see `error`), `3` the app is not the device owner. The data
is:

```json
{ "error": null, "notes": ["..."], "owner": { ... } }
```

`notes` carries things that worked partly: a keyguard that stays on because a password is set,
a reset password token that waits for the next unlock, `RELEASE` steps that failed. `owner` is
the same object `STATUS` returns.

### LOCKDOWN

| Extra | Default | Meaning |
|---|---|---|
| `restrictions` | all in the table below | the user restrictions to apply, replacing the default set |
| `skip` | | restrictions to leave out of the default set; cannot be combined with `restrictions` |
| `hide` | updaters and messaging apps | packages to hide with `setApplicationHidden`; packages hidden by an earlier lockdown and not listed are unhidden |
| `vpn` | `lockdown` | `lockdown`: the tunnel is the always-on VPN and nothing leaves outside it; `on`: always-on without lockdown; `off`: not always-on |
| `updates` | `postpone` | `postpone`: OTA installs are postponed (30 days per update, as Android allows); `none`: no policy |
| `camera` | `true` | disable every camera with `setCameraDisabled` |
| `keyguard` | `true` | disable the keyguard (no lock screen) |

Default restrictions (`UserManager` keys; ones newer than the device are skipped):

| Key | Agent commands checked against it (Android 16 emulator) |
|---|---|
| `no_modify_accounts`, `no_factory_reset`, `no_safe_boot`, `no_network_reset`, `no_add_user`, `no_remove_user`, `no_add_managed_profile` | none affected |
| `no_outgoing_calls`, `no_sms` | none affected; emergency calls still go through |
| `no_config_wifi` | `svc wifi`, `cmd wifi set-wifi-enabled`, `cmd wifi list-networks`, `cmd wifi forget-network` work |
| `no_config_mobile_networks` | `svc data enable/disable` works |
| `no_bluetooth`, `no_config_bluetooth` | Bluetooth is forced off; `cmd bluetooth_manager disable` prints `Failed with status=-1` and changes nothing |
| `no_config_tethering`, `no_usb_file_transfer`, `no_physical_media` | adb is unaffected |
| `no_config_vpn` | the tunnel's START/STOP work |
| `disallow_config_private_dns` | `settings put global private_dns_mode` is silently ignored |
| `no_install_unknown_sources_globally` | `adb install -r -d -g`, `cmd package install-existing` work |
| `disallow_microphone_toggle`, `disallow_camera_toggle` | `cmd sensor_privacy enable/disable` is refused (the toggle is frozen); apply after blocking the sensors |

Also checked and unaffected: `pm clear`, `pm suspend/unsuspend`, `cmd locale set-app-locales`,
`cmd alarm set-time`, `settings put global http_proxy`, `cmd connectivity airplane-mode`,
`cmd location set-location-enabled`, `wm dismiss-keyguard`, `locksettings set-disabled`.
`setCameraDisabled` shows up as `no_camera` in the restrictions list.

`no_debugging_features` is refused because it turns adb off. To change the sensor toggles later,
run `LOCKDOWN --es skip disallow_microphone_toggle,disallow_camera_toggle`, change them, then
`LOCKDOWN` again.

### SESSION_START

| Extra | Default | Meaning |
|---|---|---|
| `package` | required | the app under test; it is launched in lock task |
| `allow` | | more packages to allow |
| `deny` | | launcher apps to keep out |
| `features` | `system_info,notifications,home,overview,global_actions` | lock task features; `none` for none, `overview` needs `home` |

The allowlist is every launcher-visible app, minus a denylist (Settings, contacts, Play Store,
Galaxy Store, Samsung account, Google Play services, Smart Switch, the cameras, the updaters and
messaging apps), plus `allow`, minus `deny`, plus the session app, the default home app and this
app. The phone, telecom, dialer, in-call, emergency and permission-controller packages are always
allowed when installed and cannot be denied or hidden: they carry emergency calls and runtime
permission prompts.

In lock task, starting an activity of a package outside the allowlist fails (`am start` returns
error 101), including from the agent. Home goes to the launcher, apps on the allowlist start from
it, Recents switches between them. That was verified with the Pixel launcher on the emulator;
One UI's launcher is not verified yet.

`global_actions` (the power menu) is on by default because One UI's power menu has the Emergency
call entry. Pass `features` without it to remove the power menu.

### Emergency calls

Device policy cannot block emergency calls: `no_outgoing_calls` still lets the dialer call
emergency numbers. This app does not try to work around that either. It never hides or denies the dialer, phone, in-call or emergency
packages. Holtdroid's own cleanup suspends `com.android.emergency`,
`com.sec.android.emergencylauncher` and `com.samsung.android.emergency` with `pm suspend`; that
disables Samsung's SOS features and is not something the device owner does. Consider taking
them off that list.

### STOP under lockdown

With `vpn=lockdown` (the default) Android blocks every app's traffic while the tunnel is down.
`STOP` still stops the tunnel, so the phone is offline (connections fail with "Permission
denied") until the next `START`. A `STOP` is remembered: when the system restarts the always-on
VPN (process death, reboot) the tunnel stays down until `START`. `vpn=off` restores the old
behaviour, where `STOP` hands the phone back to its own networks.

### Password

`LOCKDOWN` registers a reset password token. If a PIN exists at that moment, the token only
becomes active after the phone is unlocked once with it (`password_token_active` in `STATUS`).
`CLEAR_PASSWORD` then removes any PIN, pattern or password without knowing it, and turns the
keyguard off again.

### WALLPAPER

The same drawing as Holtdroid's emulator wallpaper: the logo on the home screen, the logo and the
rows on the lock screen, with `android`, `abi` and `display` rows appended. `rows` takes a JSON
array of `[label, value]` pairs or Holtdroid's `{"rows": [...]}` (`agent.Wallpaper` as
marshalled). Shell quoting of JSON is fragile, so `rows_b64` takes the same JSON base64-encoded
(standard alphabet):

```sh
adb shell am broadcast -n $P/.ControlReceiver -a $P.WALLPAPER --es rows_b64 eyJyb3dzIjpbWyJzZXNzaW9uIiwiczEiXV19
```

It does not need device owner.

### Owner status

`STATUS` gains an `owner` object (`{"device_owner": false, "lock_task": "none"}` when the app is not
the owner):

```json
{
  "device_owner": true,
  "restrictions": ["disallow_camera_toggle", "no_camera", "no_config_vpn", "..."],
  "hidden": ["com.google.android.apps.messaging"],
  "camera_disabled": true,
  "keyguard_disabled": true,
  "password_set": false,
  "password_token_active": true,
  "always_on_vpn": "io.github.kriakiku.holtdroid.tunnel",
  "vpn_lockdown": true,
  "system_update_policy": "postpone",
  "lock_task": "locked",
  "session": "com.example.app",
  "lock_task_packages": ["com.example.app", "..."],
  "lock_task_features": ["system_info", "notifications", "home", "overview", "global_actions"]
}
```

`hidden` lists only packages this app hid and that are present. `lock_task` is `none`, `locked`
or `pinned`, and is read when the reply is built, so right after `SESSION_START` it can still say
`none`.

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

Releases from v0.2.1 on are signed with the release key; v0.2.0 and earlier carry the debug key.
The release certificate's SHA-256 is
`FB:A4:C1:7B:E5:36:97:99:D8:D2:CC:C2:0F:CD:F6:07:96:CE:4E:96:55:3F:33:2A:E6:D9:78:C7:95:D6:3C:80`;
check an APK before provisioning a phone with it:

```sh
apksigner verify --print-certs holtdroid-tunnel-0.2.1.apk | grep SHA-256
```

[Renovate](https://docs.renovatebot.com) keeps dependencies fresh (`renovate.json`). Gradle and
Go updates land as `fix(deps)` commits and ship as patch releases. The `sagernet` Go modules
move as one group and wait for review.

## License

Apache-2.0
