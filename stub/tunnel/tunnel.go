// Package tunnel exists only to generate Java/GoMobile binding classes that
// exactly match the ABI expected by the known-good reference libgojni.so.
// The generated native library is NEVER packaged.
package tunnel

type ECHPool struct{}

func (p *ECHPool) Start() {}
func (p *ECHPool) Close() {}

type GlobalConfig struct {
	ReadBuf int
}

type ProxyConfig struct {
	Host     string
	Username string
	Password string
}

type UDPAssociation struct{}

func (u *UDPAssociation) Close() {}

func StartSocksProxy(host, wsServer string, n int, udpBlockPortsStr string, dns, ech, ip string, token string, disableECH bool, ipsPref string, insecure bool) {
}
func WaitSocksProxyReady(timeoutMs int) bool { return false }
func StopSocksProxy()                        {}
