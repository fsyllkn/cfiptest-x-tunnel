package main

import (
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	cfip "xtunnelrebuild/cfip"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: cfipscan <scan|update|clear> [flags]")
		os.Exit(2)
	}

	switch os.Args[1] {
	case "scan":
		fs := flag.NewFlagSet("scan", flag.ExitOnError)
		cacheDir := fs.String("cache-dir", "", "cache directory")
		v4 := fs.Bool("v4", true, "scan IPv4")
		tls := fs.Bool("tls", true, "use TLS")
		bandwidth := fs.Int("bandwidth", 20, "target Mbps")
		maxResults := fs.Int("max-results", 6, "maximum candidates")
		_ = fs.Parse(os.Args[2:])

		cfip.SetCacheDir(strings.TrimSpace(*cacheDir))
		done := make(chan string, 1)
		go func() {
			done <- cfip.GetIPCandidates(*v4, *tls, *bandwidth, *maxResults)
		}()

		ticker := time.NewTicker(350 * time.Millisecond)
		defer ticker.Stop()
		last := ""
		for {
			select {
			case result := <-done:
				p := cfip.GetProgress()
				if p != "" && p != last {
					fmt.Printf("PROGRESS	%s
", sanitizeLine(p))
				}
				fmt.Printf("RESULT	%s
", result)
				return
			case <-ticker.C:
				p := cfip.GetProgress()
				if p != "" && p != last {
					last = p
					fmt.Printf("PROGRESS	%s
", sanitizeLine(p))
				}
			}
		}

	case "update":
		fs := flag.NewFlagSet("update", flag.ExitOnError)
		cacheDir := fs.String("cache-dir", "", "cache directory")
		_ = fs.Parse(os.Args[2:])
		cfip.SetCacheDir(strings.TrimSpace(*cacheDir))
		cfip.UpdateData()
		fmt.Printf("RESULT	{"ok":true,"message":%q}
", cfip.GetProgress())

	case "clear":
		fs := flag.NewFlagSet("clear", flag.ExitOnError)
		cacheDir := fs.String("cache-dir", "", "cache directory")
		_ = fs.Parse(os.Args[2:])
		cfip.SetCacheDir(strings.TrimSpace(*cacheDir))
		cfip.ClearCache()
		fmt.Printf("RESULT	{"ok":true,"message":%q}
", cfip.GetProgress())

	default:
		fmt.Fprintln(os.Stderr, "unknown command:", os.Args[1])
		os.Exit(2)
	}
}

func sanitizeLine(s string) string {
	s = strings.ReplaceAll(s, "", " ")
	s = strings.ReplaceAll(s, "
", " ")
	return strings.TrimSpace(s)
}
