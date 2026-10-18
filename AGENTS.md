# Holtdroid Tunnel

An Android VpnService that sends all of a phone's traffic to a SOCKS5 proxy on `127.0.0.1`,
which `adb reverse` maps to the host's sing-box. Read `README.md` for the adb control
contract and `docs/decisions.md` for why things are the way they are.

## Layout

- `engine/`: Go module bound with gomobile into an AAR (`:app:bindEngine`). sing-tun's gVisor
  stack reads the tun fd; TCP goes out as SOCKS5 CONNECT, UDP as sing UoT v2 over SOCKS5.
- `app/`: the Kotlin app. `Control` and `TunnelConfig` hold the contract and have no Android
  dependencies so the JVM unit tests cover them; `TunnelService`, `ControlReceiver` and
  `ConsentActivity` are the Android glue.

## Checks

```sh
(cd engine && gofmt -l . && go vet -tags with_gvisor ./... && go test -tags with_gvisor ./...)
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The toolchain is pinned in `mise.toml` (JDK and Go); the NDK version is `ndkVersion` in
`gradle.properties`.

## Rules

- Commit messages are one line in Conventional Commits form (`feat: ...`, `fix: ...`). No body,
  no trailers, no `Co-Authored-By`, no generated-by lines.
- Pull request descriptions stay empty; the title is the whole message.
- Write almost no code comments.
- Never put real domains or IP addresses in tracked files. Use `127.0.0.1`, `::1`, the tunnel's
  private ranges, documentation ranges (`192.0.2.0/24`, `2001:db8::/32`) and `example.com` /
  `com.example.*`.
- The control contract in `README.md` is consumed by the Holtdroid agent in Go. Changing an
  action, extra, result code or JSON field is a breaking change: update the README and the
  tests in the same commit.
- No root, and never touch other apps beyond including or excluding them from the VPN.
