# X-Tunnel APK-baseline + CFIP

Fresh reconstruction after abandoning the previous patch stack.

## Design rule

**Do not rebuild or replace X-Tunnel's transport core.**

The X-Tunnel Java/TUN flow is reconstructed to match the supplied newer APK.
At build time, GitHub Actions downloads the public v.13 Android APK only as a
carrier for the known-good native libraries and verifies them against hashes
taken from the supplied reference APK:

- `libgojni.so` SHA-256:
  `f2d46d06ba36c91925ac2e550abe9f7c52100322e2adfcbd55a2c6568afe0a65`
- `libhev-socks5-tunnel.so` SHA-256:
  `776ca62f76817d0fc0f3fe395a223c016643cd82bf1b2b3c55176a67d15c98e1`

If either hash differs, the build fails.

The only Android compatibility change in the VPN baseline is removing the
invalid `android:minSdkVersion="34"` attribute from the
`FOREGROUND_SERVICE_SPECIAL_USE` permission.

## CFIP isolation

CFIP is deliberately isolated from GoMobile/X-Tunnel:

```text
Android CfIpActivity
      |
      +-- subprocess --> libcfipscan.so (Go executable)
                              |
                              +-- CFIP scanning code

X-Tunnel TProxyService
      |
      +-- original libgojni.so (smux v1.5.35)
      +-- original libhev-socks5-tunnel.so
```

`libcfipscan.so` is a same-UID helper executable. It never loads GoMobile and
cannot collide with X-Tunnel's `libgojni.so`.

CFIP only interacts with X-Tunnel by writing `PrefIp` through the existing
`Preferences` class.

## CFIP behavior

- Start scan -> stop X-Tunnel VPN first.
- Scan multiple candidate IPs.
- Results are stored by batch, newest first.
- `全选达标` and `清空所选` are enabled after rows are rendered.
- Applying selected IPs writes comma-separated addresses to current `PrefIp`.
- Applying results does not clear history.
- Applying results never restarts VPN.
- Only `清除测速记录` deletes history.

## Config backup

The configuration activity dynamically enumerates all app SharedPreferences,
so export includes current and future preference stores, including CFIP scan
history. Runtime `Enable` is forced off during export/import so importing a
backup cannot auto-start VPN.

## Build

Requirements used by the workflow:

- Go 1.25.0
- x/mobile `v0.0.0-20251126181937-5c265dc024c4`
- Java 17
- Android platform 35
- NDK 26.1.10909125
- Gradle 8.9 / AGP 8.7.3

Run:

```bash
bash tools/build.sh
```

Output:

```text
x-tunnel-apkbase-cfip-arm64-debug.apk
x-tunnel-apkbase-cfip-arm64-debug.apk.sha256
```
