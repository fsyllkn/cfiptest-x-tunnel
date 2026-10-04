# Reference APK baseline

This reconstruction treats the user-supplied newer APK as the authoritative
X-Tunnel implementation. Public source is used only as a Java/resource scaffold.

## Reference hashes

- APK: `com.x.tunnel-1.0-arm64-v8a-release-20260325_095153.apk`
  - SHA-256: `e190b526027b6e5726fdef6468f0ec34261634f1e06a409ed633b18505de0bb5`
- `libgojni.so`
  - SHA-256: `f2d46d06ba36c91925ac2e550abe9f7c52100322e2adfcbd55a2c6568afe0a65`
- `libhev-socks5-tunnel.so`
  - SHA-256: `776ca62f76817d0fc0f3fe395a223c016643cd82bf1b2b3c55176a67d15c98e1`

The build fails if either packaged X-Tunnel native library differs from these
hashes.

## `libgojni.so` build metadata

Extracted with `go version -m` from the reference APK:

- Go `1.25.0`
- `github.com/google/uuid v1.6.0`
- `github.com/gorilla/websocket v1.5.3`
- `github.com/xtaci/smux v1.5.35`
- `golang.org/x/mobile v0.0.0-20251126181937-5c265dc024c4`

The reconstruction uses that exact x/mobile revision only to generate Java
binding classes. The generated native library is discarded.

## Reference VPN chain

DEX tracing confirms this order in `TProxyService.startService()`:

1. Build Android `VpnService.Builder`.
2. Add IPv4/IPv6 addresses and default routes.
3. Apply global/per-app routing and disallow self when appropriate.
4. `builder.establish()`.
5. Write `tproxy.conf`.
6. Call original `TProxyStartService(config, tunFd)` from
   `libhev-socks5-tunnel.so`.
7. Normalize server URL:
   - preserve `wss://`
   - preserve `ws://`
   - otherwise prefix `wss://`
   - append `/` if URL path is absent.
8. Call `com.x.tunnel.tunnel.Tunnel.startSocksProxy(...)` with this exact order:
   - local SOCKS address (`address:port`)
   - server URL
   - WSS connection count
   - UDP blocked ports
   - ECH DNS
   - ECH domain
   - preferred IP string
   - token
   - disable ECH
   - IP preference
   - insecure TLS flag
9. Wait for ready and display success/failure toast.
10. Persist `Enable=true`.
11. Create notification channel and call `startForeground()`.

This sequence is intentionally *not* redesigned in the fresh build.

## Manifest change intentionally allowed

Reference APK contains:

```xml
<uses-permission
    android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
    android:minSdkVersion="34" />
```

Fresh build changes only that permission declaration to:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

No other X-Tunnel VPN lifecycle change is required by this reconstruction.

## JNI binding

Reference `libhev-socks5-tunnel.so` contains:

```text
com/x/tunnel/TProxyService
```

It must never be rebuilt with the stale `hev/sockstun/TProxyService` package.

## Profile copy behavior

DEX tracing confirms the newer APK has a Copy button. Behavior restored here:

- initial name: `<current>_副本`
- collision names: `_副本2`, `_副本3`, ...
- saves current edits first
- generates a new UUID profile
- copies exactly:
  - WssAddr
  - EchDns
  - EchDomain
  - PrefIp
  - IpsPref
  - UdpBlockPorts
  - Insecure
  - Token
  - WsConn
  - DisableEch
- switches current profile to the new copy.
