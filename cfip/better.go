package better

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type CandidateResult struct {
	IP            string `json:"ip"`
	RealBandwidth int    `json:"realBandwidth"`
	MaxSpeed      int    `json:"maxSpeed"`
	LatencyMs     int    `json:"latencyMs"`
	DataCenter    string `json:"dataCenter"`
	Qualified     bool   `json:"qualified"`
}

type CandidateListResult struct {
	Bandwidth  int               `json:"bandwidth"`
	Candidates []CandidateResult `json:"candidates"`
	Elapsed    int               `json:"elapsed"`
	Cancelled  bool              `json:"cancelled"`
	Error      string            `json:"error"`
}

var (
	dataDir      string
	progress     string
	progressMu   sync.Mutex
	rng          = rand.New(rand.NewSource(time.Now().UnixNano()))
	rngMu        sync.Mutex
	cancelCtx    context.Context
	cancelCancel context.CancelFunc
	cancelMu     sync.Mutex
)

func scanCtx() context.Context {
	cancelMu.Lock()
	defer cancelMu.Unlock()
	if cancelCtx != nil {
		return cancelCtx
	}
	return context.Background()
}

func resetCancel() {
	cancelMu.Lock()
	defer cancelMu.Unlock()
	cancelCtx, cancelCancel = context.WithCancel(context.Background())
}

func isCancelled() bool {
	cancelMu.Lock()
	defer cancelMu.Unlock()
	if cancelCtx == nil {
		return false
	}
	select {
	case <-cancelCtx.Done():
		return true
	default:
		return false
	}
}

func CancelScan() {
	cancelMu.Lock()
	if cancelCancel != nil {
		cancelCancel()
	}
	cancelMu.Unlock()
	setProgress("用户已取消扫描")
}

func SetCacheDir(dir string) { dataDir = dir }
func GetProgress() string {
	progressMu.Lock()
	defer progressMu.Unlock()
	return progress
}
func setProgress(s string) {
	progressMu.Lock()
	progress = s
	progressMu.Unlock()
}

func path(name string) string {
	if dataDir == "" { return name }
	return filepath.Join(dataDir, name)
}

func ClearCache() {
	for _, n := range []string{"url.txt","ips-v4.txt","ips-v6.txt","locations.json"} {
		_ = os.Remove(path(n))
	}
	setProgress("缓存已清除")
}

func UpdateData() {
	ClearCache()
	resetCancel()
	ctx, cancel := context.WithTimeout(scanCtx(), 20*time.Second)
	defer cancel()
	if err := ensureData(ctx); err != nil {
		setProgress("数据更新失败: " + err.Error())
		return
	}
	setProgress("数据更新完成")
}

func fetch(ctx context.Context, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil { return nil, err }
	resp, err := (&http.Client{Timeout: 10*time.Second}).Do(req)
	if err != nil { return nil, err }
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 32<<20))
}

func ensureFile(ctx context.Context, name, url string) error {
	p := path(name)
	if st, err := os.Stat(p); err == nil && st.Size() > 0 { return nil }
	b, err := fetch(ctx, url)
	if err != nil { return err }
	if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil && filepath.Dir(p) != "." { return err }
	return os.WriteFile(p, b, 0644)
}

func ensureData(ctx context.Context) error {
	setProgress("正在准备 CF 数据...")
	if err := ensureFile(ctx, "url.txt", "https://www.baipiao.eu.org/cloudflare/url"); err != nil { return err }
	if err := ensureFile(ctx, "ips-v4.txt", "https://www.baipiao.eu.org/cloudflare/ips-v4"); err != nil { return err }
	if err := ensureFile(ctx, "ips-v6.txt", "https://www.baipiao.eu.org/cloudflare/ips-v6"); err != nil { return err }
	_ = ensureFile(ctx, "locations.json", "https://www.baipiao.eu.org/cloudflare/locations")
	return nil
}

func readLines(name string) ([]string, error) {
	f, err := os.Open(path(name))
	if err != nil { return nil, err }
	defer f.Close()
	var out []string
	s := bufio.NewScanner(f)
	for s.Scan() {
		v := strings.TrimSpace(s.Text())
		if v != "" { out = append(out, v) }
	}
	return out, s.Err()
}

func randomIntn(n int) int {
	rngMu.Lock(); defer rngMu.Unlock()
	return rng.Intn(n)
}

func randomFromCIDR(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" { return "" }
	ip, network, err := net.ParseCIDR(raw)
	if err != nil {
		if parsed := net.ParseIP(raw); parsed != nil { return parsed.String() }
		return ""
	}
	mask := network.Mask
	base := ip.To16()
	if base == nil { return "" }
	ones, bits := mask.Size()
	if bits == 32 {
		b4 := ip.To4()
		if b4 == nil { return "" }
		baseN := binary.BigEndian.Uint32(b4)
		hostBits := 32 - ones
		var add uint32
		if hostBits > 0 {
			limit := uint64(1) << uint(hostBits)
			add = uint32(randomIntn(int(limit)))
		}
		out := make(net.IP, 4)
		binary.BigEndian.PutUint32(out, baseN+add)
		return out.String()
	}
	out := append(net.IP(nil), base...)
	for bit := ones; bit < 128; bit++ {
		if randomIntn(2) == 1 {
			out[bit/8] |= byte(1 << uint(7-bit%8))
		}
	}
	return out.String()
}

func sampleSubnets(in []string, n int) []string {
	if len(in) <= n { return append([]string(nil), in...) }
	out := append([]string(nil), in...)
	rngMu.Lock()
	rng.Shuffle(len(out), func(i,j int){ out[i],out[j]=out[j],out[i] })
	rngMu.Unlock()
	return out[:n]
}

func testRTT(ctx context.Context, ip string, tlsOn bool) int {
	port := "80"
	if tlsOn {
		port = "443"
	}

	var totalMs int
	for i := 0; i < 3; i++ {
		if isCancelled() {
			return 0
		}

		start := time.Now()
		d := net.Dialer{Timeout: 1 * time.Second}
		conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
		if err != nil {
			return 0
		}
		tcpDuration := time.Since(start)

		_ = conn.SetDeadline(start.Add(1 * time.Second))
		var rwc net.Conn = conn
		if tlsOn {
			tc := tls.Client(conn, &tls.Config{ServerName: "cloudflare.com", InsecureSkipVerify: true})
			if err := tc.HandshakeContext(ctx); err != nil {
				conn.Close()
				return 0
			}
			rwc = tc
		}

		_, err = io.WriteString(rwc, "GET / HTTP/1.1\r\nHost: cloudflare.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n")
		if err != nil {
			rwc.Close()
			return 0
		}

		resp, err := http.ReadResponse(bufio.NewReader(rwc), nil)
		rwc.Close()
		if err != nil {
			return 0
		}
		if resp.Body != nil {
			resp.Body.Close()
		}
		if resp.Header.Get("CF-RAY") == "" {
			return 0
		}
		totalMs += int(tcpDuration.Milliseconds())
	}
	return totalMs / 3
}

func rttCandidates(ctx context.Context, ips []string, tlsOn bool) []CandidateResult {
	if len(ips) == 0 {
		return nil
	}

	type item struct {
		ip string
		ms int
	}
	resultChan := make(chan item, len(ips))
	thread := make(chan struct{}, 50)
	var wg sync.WaitGroup
	var count int
	var countMu sync.Mutex
	total := len(ips)

	for _, ip := range ips {
		if isCancelled() {
			break
		}
		if ip == "" {
			continue
		}
		wg.Add(1)
		thread <- struct{}{}
		go func(ip string) {
			defer func() {
				<-thread
				wg.Done()
				countMu.Lock()
				count++
				current := count
				countMu.Unlock()
				if current%10 == 0 || current == total {
					setProgress(fmt.Sprintf("RTT 测试进度: %d/%d", current, total))
				}
			}()

			if isCancelled() {
				return
			}
			if ms := testRTT(ctx, ip, tlsOn); ms > 0 {
				resultChan <- item{ip: ip, ms: ms}
			}
		}(ip)
	}

	go func() {
		wg.Wait()
		close(resultChan)
	}()

	var out []CandidateResult
	for x := range resultChan {
		out = append(out, CandidateResult{IP: x.ip, LatencyMs: x.ms})
	}
	if isCancelled() {
		return nil
	}

	sort.Slice(out, func(i, j int) bool {
		return out[i].LatencyMs < out[j].LatencyMs
	})
	if len(out) > 10 {
		setProgress(fmt.Sprintf("RTT 测试完成，%d/%d 个 IP 有效，保留延迟最低的 10 个", len(out), total))
		out = out[:10]
	} else {
		setProgress(fmt.Sprintf("RTT 测试完成，%d/%d 个 IP 有效", len(out), total))
	}
	return out
}

func speedTarget() (string,string,error) {
	b, err := os.ReadFile(path("url.txt"))
	if err != nil { return "","",err }
	raw := strings.TrimSpace(string(b))
	raw = strings.TrimPrefix(raw,"https://")
	raw = strings.TrimPrefix(raw,"http://")
	parts := strings.SplitN(raw,"/",2)
	if len(parts)<2 { return "","",fmt.Errorf("测速 URL 无效") }
	return parts[0], parts[1], nil
}

type speedTestResult struct {
	VerifiedSpeed int
	PeakSpeed     int
	DataCenter    string
	Qualified     bool
}

func averageSpeed(values []int) int {
	if len(values) == 0 {
		return 0
	}
	total := 0
	for _, v := range values {
		total += v
	}
	return total / len(values)
}

// speedTest keeps the original one-connection download test, but adds:
// - 5 second quick rejection for clearly slow IPs
// - 15 second sustained verification for promising IPs
func speedTest(ctx context.Context, ip string, tlsOn bool, targetKB int) speedTestResult {
	host, fileName, err := speedTarget()
	if err != nil {
		return speedTestResult{}
	}

	port := "80"
	scheme := "http"
	if tlsOn {
		port = "443"
		scheme = "https"
	}

	tr := &http.Transport{
		DialContext: func(c context.Context, network, addr string) (net.Conn, error) {
			return (&net.Dialer{Timeout: 3 * time.Second}).DialContext(c, "tcp", net.JoinHostPort(ip, port))
		},
		TLSClientConfig: &tls.Config{ServerName: host},
	}
	client := &http.Client{Transport: tr, Timeout: 22 * time.Second}
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, scheme+"://"+host+"/"+fileName, nil)
	resp, err := client.Do(req)
	if err != nil {
		return speedTestResult{}
	}
	defer resp.Body.Close()

	dc := ""
	if ray := resp.Header.Get("CF-RAY"); ray != "" {
		parts := strings.Split(ray, "-")
		if len(parts) > 1 {
			dc = strings.TrimSpace(parts[len(parts)-1])
		}
	}

	buf := make([]byte, 32<<10)
	testStart := time.Now()
	windowStart := testStart
	var windowBytes int64
	var windows []int
	peakSpeed := 0
	quickChecked := false
	reached15s := false

	for {
		if isCancelled() {
			return speedTestResult{}
		}

		n, readErr := resp.Body.Read(buf)
		windowBytes += int64(n)
		now := time.Now()
		windowElapsed := now.Sub(windowStart).Seconds()

		if windowElapsed >= 1.0 {
			speedKB := int(float64(windowBytes) / 1024 / windowElapsed)
			windows = append(windows, speedKB)
			if speedKB > peakSpeed {
				peakSpeed = speedKB
			}
			windowBytes = 0
			windowStart = now

			elapsed := now.Sub(testStart)
			setProgress(fmt.Sprintf("%s 稳定测速 %ds/15s: %d kB/s", ip, int(elapsed.Seconds()), speedKB))

			if !quickChecked && elapsed >= 5*time.Second {
				quickChecked = true
				quickWindows := windows
				if len(quickWindows) > 2 {
					quickWindows = quickWindows[2:]
				}
				quickAvg := averageSpeed(quickWindows)
				if quickAvg < targetKB*3/4 && peakSpeed < targetKB {
					setProgress(fmt.Sprintf("%s 5 秒初筛未达标（均速 %d kB/s，峰值 %d kB/s），快速跳过", ip, quickAvg, peakSpeed))
					return speedTestResult{VerifiedSpeed: quickAvg, PeakSpeed: peakSpeed, DataCenter: dc}
				}
			}

			if elapsed >= 15*time.Second {
				reached15s = true
				break
			}
		}

		if readErr != nil {
			break
		}
	}

	if len(windows) == 0 {
		return speedTestResult{DataCenter: dc}
	}

	stableWindows := windows
	if len(stableWindows) > 2 {
		stableWindows = stableWindows[2:]
	}
	stableAvg := averageSpeed(stableWindows)

	nearTarget := 0
	atTarget := 0
	for _, v := range stableWindows {
		if v >= targetKB*4/5 {
			nearTarget++
		}
		if v >= targetKB {
			atTarget++
		}
	}

	qualified := reached15s &&
		stableAvg >= targetKB &&
		len(stableWindows) > 0 &&
		nearTarget*10 >= len(stableWindows)*7 &&
		atTarget >= 3

	return speedTestResult{
		VerifiedSpeed: stableAvg,
		PeakSpeed:     peakSpeed,
		DataCenter:    dc,
		Qualified:     qualified,
	}
}

func GetIPCandidates(v4 bool, useTLS bool, bandwidth int, maxResults int) string {
	setProgress("正在初始化...")
	resetCancel()

	if bandwidth <= 0 {
		bandwidth = 1
	}
	if maxResults < 1 {
		maxResults = 1
	}
	if maxResults > 10 {
		maxResults = 10
	}

	start := time.Now()
	ctx := scanCtx()
	result := CandidateListResult{Bandwidth: bandwidth}

	if err := ensureData(ctx); err != nil {
		result.Error = err.Error()
		b, _ := json.Marshal(result)
		return string(b)
	}

	file := "ips-v4.txt"
	if !v4 {
		file = "ips-v6.txt"
	}
	subnets, err := readLines(file)
	if err != nil {
		result.Error = err.Error()
		b, _ := json.Marshal(result)
		return string(b)
	}
	if len(subnets) == 0 {
		result.Error = "IP 列表为空"
		b, _ := json.Marshal(result)
		return string(b)
	}

	sampleSize := 100
	if len(subnets) < sampleSize {
		sampleSize = len(subnets)
	}
	targetKB := bandwidth * 128
	qualifiedSeen := make(map[string]bool)

	for len(result.Candidates) < maxResults {
		if isCancelled() {
			result.Cancelled = true
			result.Error = "扫描已取消"
			break
		}

		var cands []CandidateResult
		for {
			if isCancelled() {
				result.Cancelled = true
				result.Error = "扫描已取消"
				break
			}

			sampled := sampleSubnets(subnets, sampleSize)
			var ips []string
			for _, subnet := range sampled {
				if ip := randomFromCIDR(subnet); ip != "" {
					ips = append(ips, ip)
				}
			}

			setProgress(fmt.Sprintf("已生成 %d 个测试 IP，开始 RTT 测试（已达标 %d/%d）...", len(ips), len(result.Candidates), maxResults))
			cands = rttCandidates(ctx, ips, useTLS)
			if isCancelled() {
				result.Cancelled = true
				result.Error = "扫描已取消"
				break
			}
			if len(cands) > 0 {
				break
			}
			setProgress("当前所有 IP 都存在 RTT 丢包，继续新的 RTT 测试...")
		}
		if result.Cancelled {
			break
		}

		for i := range cands {
			if isCancelled() {
				result.Cancelled = true
				result.Error = "扫描已取消"
				break
			}
			if qualifiedSeen[cands[i].IP] {
				continue
			}

			setProgress(fmt.Sprintf("正在测速 %s (RTT %dms，已达标 %d/%d)", cands[i].IP, cands[i].LatencyMs, len(result.Candidates), maxResults))
			sr := speedTest(ctx, cands[i].IP, useTLS, targetKB)

			if !sr.Qualified {
				setProgress(fmt.Sprintf("%s 未通过稳定测速（稳定均速 %d kB/s，峰值 %d kB/s），继续测试...", cands[i].IP, sr.VerifiedSpeed, sr.PeakSpeed))
				continue
			}

			qualifiedSeen[cands[i].IP] = true
			cands[i].RealBandwidth = sr.VerifiedSpeed / 128
			cands[i].MaxSpeed = sr.PeakSpeed
			cands[i].DataCenter = sr.DataCenter
			cands[i].Qualified = true
			result.Candidates = append(result.Candidates, cands[i])

			setProgress(fmt.Sprintf("找到达标 IP %d/%d：%s，稳定 %d kB/s，峰值 %d kB/s，RTT %dms",
				len(result.Candidates), maxResults, cands[i].IP, sr.VerifiedSpeed, sr.PeakSpeed, cands[i].LatencyMs))

			if len(result.Candidates) >= maxResults {
				break
			}
		}

		if result.Cancelled {
			break
		}
		if len(result.Candidates) < maxResults {
			setProgress(fmt.Sprintf("本轮结束，已找到 %d/%d 个达标 IP，继续新一轮测试...", len(result.Candidates), maxResults))
		}
	}

	sort.Slice(result.Candidates, func(i, j int) bool {
		if result.Candidates[i].RealBandwidth != result.Candidates[j].RealBandwidth {
			return result.Candidates[i].RealBandwidth > result.Candidates[j].RealBandwidth
		}
		return result.Candidates[i].LatencyMs < result.Candidates[j].LatencyMs
	})

	result.Elapsed = int(time.Since(start).Seconds())
	if result.Cancelled {
		setProgress("扫描已取消")
	} else {
		setProgress(fmt.Sprintf("扫描完成：找到 %d 个稳定达到 %d Mbps 的 IP，用时 %d 秒",
			len(result.Candidates), bandwidth, result.Elapsed))
	}

	b, _ := json.Marshal(result)
	return string(b)
}

func GetIPs(v4 bool, useTLS bool, bandwidth int) string {
	raw := GetIPCandidates(v4,useTLS,bandwidth,1)
	var x CandidateListResult
	if json.Unmarshal([]byte(raw),&x)!=nil || len(x.Candidates)==0 { return raw }
	c:=x.Candidates[0]
	out:=map[string]any{"ip":c.IP,"bandwidth":bandwidth,"realBandwidth":c.RealBandwidth,"maxSpeed":c.MaxSpeed,"latencyMs":c.LatencyMs,"dataCenter":c.DataCenter,"elapsed":x.Elapsed,"error":x.Error}
	b,_:=json.Marshal(out); return string(b)
}

func init() { _ = strconv.IntSize }
