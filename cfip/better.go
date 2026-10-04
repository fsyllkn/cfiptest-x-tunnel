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
	dataDir    string
	progress   string
	progressMu sync.Mutex
	rng        = rand.New(rand.NewSource(time.Now().UnixNano()))
	rngMu      sync.Mutex
)

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
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
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
	if tlsOn { port = "443" }
	total := 0
	for i:=0; i<3; i++ {
		start := time.Now()
		d := net.Dialer{Timeout: 1200*time.Millisecond}
		conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
		if err != nil { return 0 }
		rwc := net.Conn(conn)
		if tlsOn {
			tc := tls.Client(conn, &tls.Config{ServerName:"cloudflare.com", InsecureSkipVerify:true})
			if err := tc.HandshakeContext(ctx); err != nil { conn.Close(); return 0 }
			rwc = tc
		}
		_ = rwc.SetDeadline(time.Now().Add(1500*time.Millisecond))
		_, err = io.WriteString(rwc, "HEAD / HTTP/1.1\r\nHost: cloudflare.com\r\nConnection: close\r\n\r\n")
		if err != nil { rwc.Close(); return 0 }
		resp, err := http.ReadResponse(bufio.NewReader(rwc), nil)
		rwc.Close()
		if err != nil || resp.Header.Get("CF-RAY") == "" { return 0 }
		if resp.Body != nil { resp.Body.Close() }
		total += int(time.Since(start).Milliseconds())
	}
	return total/3
}

func rttCandidates(ctx context.Context, ips []string, tlsOn bool) []CandidateResult {
	type item struct{ ip string; ms int }
	ch := make(chan item, len(ips))
	sem := make(chan struct{}, 50)
	var wg sync.WaitGroup
	for _, ip := range ips {
		if ip == "" { continue }
		wg.Add(1)
		go func(ip string){
			defer wg.Done()
			sem <- struct{}{}; defer func(){<-sem}()
			if ms := testRTT(ctx, ip, tlsOn); ms > 0 { ch <- item{ip,ms} }
		}(ip)
	}
	go func(){ wg.Wait(); close(ch) }()
	var out []CandidateResult
	for x := range ch { out = append(out, CandidateResult{IP:x.ip, LatencyMs:x.ms}) }
	sort.Slice(out, func(i,j int) bool { return out[i].LatencyMs < out[j].LatencyMs })
	if len(out)>10 { out=out[:10] }
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

func speedTest(ctx context.Context, ip string, tlsOn bool) (int,string) {
	host, fileName, err := speedTarget()
	if err != nil { return 0,"" }
	port := "80"; scheme := "http"
	if tlsOn { port="443"; scheme="https" }
	tr := &http.Transport{
		DialContext: func(c context.Context, network, addr string)(net.Conn,error){
			return (&net.Dialer{Timeout:3*time.Second}).DialContext(c,"tcp",net.JoinHostPort(ip,port))
		},
		TLSClientConfig: &tls.Config{ServerName:host},
	}
	client := &http.Client{Transport:tr, Timeout:6*time.Second}
	req, _ := http.NewRequestWithContext(ctx,http.MethodGet,scheme+"://"+host+"/"+fileName,nil)
	resp, err := client.Do(req)
	if err != nil { return 0,"" }
	defer resp.Body.Close()
	dc := ""
	if ray := resp.Header.Get("CF-RAY"); ray != "" {
		p := strings.Split(ray,"-")
		if len(p)>1 { dc = p[len(p)-1] }
	}
	buf := make([]byte,32<<10)
	start := time.Now()
	var bytes int64
	for time.Since(start)<4*time.Second {
		n, er := resp.Body.Read(buf)
		bytes += int64(n)
		if er != nil { break }
	}
	elapsed := time.Since(start).Seconds()
	if elapsed <= 0 { return 0,dc }
	return int(float64(bytes)/1024.0/elapsed),dc
}

func GetIPCandidates(v4 bool, useTLS bool, bandwidth int, maxResults int) string {
	if bandwidth <= 0 { bandwidth=1 }
	if maxResults < 1 { maxResults=6 }
	if maxResults > 10 { maxResults=10 }

	start := time.Now()
	ctx := context.Background()
	result := CandidateListResult{Bandwidth:bandwidth}

	if err := ensureData(ctx); err != nil {
		result.Error = err.Error()
		b,_:=json.Marshal(result)
		return string(b)
	}

	file := "ips-v4.txt"
	if !v4 { file="ips-v6.txt" }
	subnets, err := readLines(file)
	if err != nil {
		result.Error=err.Error()
		b,_:=json.Marshal(result)
		return string(b)
	}
	if len(subnets) == 0 {
		result.Error="IP 列表为空"
		b,_:=json.Marshal(result)
		return string(b)
	}

	// Keep the original CFIP test semantics:
	// the requested count means "qualified IP count", not "candidate count".
	// Non-qualified IPs never enter the final result and never consume quota.
	seen := map[string]bool{}
	targetKB := bandwidth * 128
	round := 0

	for len(result.Candidates) < maxResults {
		round++
		setProgress(fmt.Sprintf(
			"第 %d 轮：已找到 %d/%d 个达标 IP，开始 RTT 测试...",
			round, len(result.Candidates), maxResults))

		var ips []string
		for _, s := range sampleSubnets(subnets, 100) {
			if ip := randomFromCIDR(s); ip != "" && !seen[ip] {
				seen[ip] = true
				ips = append(ips, ip)
			}
		}

		// Extremely unlikely for normal CF CIDR lists, but do not spin on an
		// empty generated batch. Clear the random-IP de-duplication set and
		// start a fresh sampling cycle.
		if len(ips) == 0 {
			seen = map[string]bool{}
			continue
		}

		cands := rttCandidates(ctx, ips, useTLS)
		if len(cands) == 0 {
			setProgress(fmt.Sprintf(
				"第 %d 轮没有可用 RTT IP，继续下一轮（已达标 %d/%d）",
				round, len(result.Candidates), maxResults))
			continue
		}

		for i := range cands {
			setProgress(fmt.Sprintf(
				"测速 %s (%dms)，已达标 %d/%d",
				cands[i].IP, cands[i].LatencyMs,
				len(result.Candidates), maxResults))

			kb, dc := speedTest(ctx, cands[i].IP, useTLS)
			if kb <= 0 {
				continue
			}

			// Same threshold semantics as the original test APK:
			// bandwidth Mbps -> target kB/s, only accept maxSpeed >= target.
			if kb < targetKB {
				setProgress(fmt.Sprintf(
					"%s 峰值 %d kB/s，未达到 %d Mbps，继续测试...",
					cands[i].IP, kb, bandwidth))
				continue
			}

			cands[i].MaxSpeed = kb
			cands[i].RealBandwidth = kb / 128
			cands[i].DataCenter = dc
			cands[i].Qualified = true
			result.Candidates = append(result.Candidates, cands[i])

			setProgress(fmt.Sprintf(
				"找到达标 IP %d/%d：%s，%d kB/s，%dms",
				len(result.Candidates), maxResults,
				cands[i].IP, kb, cands[i].LatencyMs))

			if len(result.Candidates) >= maxResults {
				break
			}
		}

		if len(result.Candidates) < maxResults {
			setProgress(fmt.Sprintf(
				"本轮结束，已找到 %d/%d 个达标 IP，继续新一轮测试...",
				len(result.Candidates), maxResults))
		}
	}

	sort.Slice(result.Candidates, func(i,j int) bool {
		if result.Candidates[i].MaxSpeed != result.Candidates[j].MaxSpeed {
			return result.Candidates[i].MaxSpeed > result.Candidates[j].MaxSpeed
		}
		return result.Candidates[i].LatencyMs < result.Candidates[j].LatencyMs
	})

	result.Elapsed=int(time.Since(start).Seconds())
	setProgress(fmt.Sprintf(
		"扫描完成：已找到 %d/%d 个达到 %d Mbps 的 IP，用时 %d 秒",
		len(result.Candidates), maxResults, bandwidth, result.Elapsed))

	b,_:=json.Marshal(result)
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

func CancelScan() {}
func init() { _ = strconv.IntSize }
