// mzy_downloader — 梦之韵启动器 HTTP/3 (QUIC) 多线程分块下载器
//
// 从 Cloudflare CDN 下载大文件（整合包）时，国内网络必须走基于 QUIC 的
// HTTP/3 连接才下得动。Java 内置 HttpClient 不支持 HTTP/3，因此用 Go
// (quic-go) 编译独立下载器，启动器通过进程调用它完成下载。
//
// 多线程分块：先发 HEAD 获取文件大小，分成 N 块并发下载（Range 请求），
// 最后合并。服务器不支持 Range 时自动降级单线程。
//
// 用法:
//   mzy_downloader.exe -url <URL> -out <目标文件> -progress <进度JSON> [-threads N] [-expected <期望字节数>]
//
// 进度文件内容(每 ~500ms 刷新):
//   {"state":"downloading|done|error","downloaded":N,"total":N,"speed":N,"error":"..."}
//
// 协议: 优先 HTTP/3(QUIC)，失败自动降级 HTTP/2 → HTTP/1.1。
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/quic-go/quic-go/http3"
)

type Progress struct {
	State      string `json:"state"`
	Downloaded int64  `json:"downloaded"`
	Total      int64  `json:"total"`
	Speed      int64  `json:"speed"`
	Error      string `json:"error"`
}

var (
	url        = flag.String("url", "", "下载地址")
	out        = flag.String("out", "", "目标文件路径")
	progress   = flag.String("progress", "", "进度 JSON 文件路径")
	expected   = flag.Int64("expected", -1, "期望文件字节数（下载完成后校验）")
	timeoutSec = flag.Int("timeout", 3600, "总超时秒数")
	threads    = flag.Int("threads", 8, "并发下载线程数（分块下载）")
)

func writeProgress(p Progress) {
	if *progress == "" {
		return
	}
	b, err := json.Marshal(p)
	if err != nil {
		return
	}
	_ = os.WriteFile(*progress, b, 0o644)
}

func main() {
	flag.Parse()
	if *url == "" || *out == "" {
		fmt.Fprintln(os.Stderr, "usage: mzy_downloader -url URL -out PATH [-progress JSON] [-threads N] [-expected N]")
		os.Exit(2)
	}

	if dir := filepath.Dir(*out); dir != "" {
		_ = os.MkdirAll(dir, 0o755)
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeoutSec)*time.Second)
	defer cancel()

	clients := []struct {
		name string
		cli  *http.Client
	}{
		{"http/3", &http.Client{Transport: &http3.Transport{}}},
		{"http/2", &http.Client{Transport: &http.Transport{}}},
	}

	var lastErr error
	for _, c := range clients {
		err := downloadWithClient(ctx, c.cli, c.name)
		if err == nil {
			writeProgress(Progress{State: "done", Downloaded: *expected, Total: *expected})
			fmt.Println("done")
			os.Exit(0)
		}
		lastErr = err
		fmt.Fprintf(os.Stderr, "%s 失败: %v，尝试下一协议\n", c.name, err)
	}

	writeProgress(Progress{State: "error", Error: lastErr.Error()})
	fmt.Fprintln(os.Stderr, "all protocols failed:", lastErr)
	os.Exit(1)
}

func downloadWithClient(ctx context.Context, client *http.Client, proto string) error {
	// 1) HEAD 获取文件大小和是否支持 Range
	size, acceptRanges, err := headFile(ctx, client, *url)
	if err != nil {
		return fmt.Errorf("HEAD failed: %w", err)
	}
	if *expected > 0 && size > 0 && size != *expected {
		fmt.Fprintf(os.Stderr, "警告: HEAD size=%d expected=%d\n", size, *expected)
	}
	if size <= 0 {
		size = *expected
	}

	part := *out + ".part"

	// 已有完整 .part 且大小匹配 → 直接改名
	if fi, err := os.Stat(part); err == nil && size > 0 && fi.Size() == size {
		return os.Rename(part, *out)
	}

	// 2) 不支持 Range 或线程数<=1 → 单线程
	if !acceptRanges || *threads <= 1 || size <= 0 {
		return singleThread(ctx, client, *url, part, size, proto)
	}

	// 3) 多线程分块下载
	n := *threads
	if n > 16 {
		n = 16
	}
	chunkSize := (size + int64(n) - 1) / int64(n)

	var downloaded atomic.Int64
	var wg sync.WaitGroup
	errCh := make(chan error, n)

	// 进度统计协程
	go func() {
		start := time.Now()
		last := int64(0)
		ticker := time.NewTicker(500 * time.Millisecond)
		defer ticker.Stop()
		for range ticker.C {
			cur := downloaded.Load()
			elapsed := time.Since(start).Seconds()
			speed := int64(0)
			if elapsed > 0 {
				speed = int64(float64(cur-last) / elapsed)
			}
			last = cur
			writeProgress(Progress{State: "downloading", Downloaded: cur, Total: size, Speed: speed})
			if cur >= size {
				return
			}
		}
	}()

	for i := 0; i < n; i++ {
		start := int64(i) * chunkSize
		end := start + chunkSize - 1
		if end >= size {
			end = size - 1
		}
		if start > end {
			continue
		}
		wg.Add(1)
		go func(idx int, s, e int64) {
			defer wg.Done()
			chunkFile := fmt.Sprintf("%s.part.%d", *out, idx)
			// 已下载的块跳过（断点续传）
			if fi, err := os.Stat(chunkFile); err == nil && fi.Size() == e-s+1 {
				downloaded.Add(fi.Size())
				return
			}
			if err := downloadChunk(ctx, client, *url, chunkFile, s, e, &downloaded); err != nil {
				errCh <- fmt.Errorf("chunk %d: %w", idx, err)
			}
		}(i, start, end)
	}
	wg.Wait()
	close(errCh)

	for e := range errCh {
		if e != nil {
			return e
		}
	}

	// 4) 合并块
	final, err := os.Create(part)
	if err != nil {
		return err
	}
	defer final.Close()
	for i := 0; i < n; i++ {
		chunkFile := fmt.Sprintf("%s.part.%d", *out, i)
		cf, err := os.Open(chunkFile)
		if err != nil {
			return fmt.Errorf("open chunk %d: %w", i, err)
		}
		if _, err := io.Copy(final, cf); err != nil {
			cf.Close()
			return fmt.Errorf("merge chunk %d: %w", i, err)
		}
		cf.Close()
		_ = os.Remove(chunkFile)
	}
	final.Close()

	// 5) 校验大小
	if size > 0 {
		fi, err := os.Stat(part)
		if err != nil {
			return err
		}
		if fi.Size() != size {
			os.Remove(part)
			return fmt.Errorf("size mismatch after merge: got %d want %d", fi.Size(), size)
		}
	}
	if *expected > 0 {
		fi, _ := os.Stat(part)
		if fi != nil && fi.Size() != *expected {
			os.Remove(part)
			return fmt.Errorf("size mismatch: got %d want %d", fi.Size(), *expected)
		}
	}

	return os.Rename(part, *out)
}

func headFile(ctx context.Context, client *http.Client, url string) (size int64, acceptRanges bool, err error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, url, nil)
	if err != nil {
		return 0, false, err
	}
	req.Header.Set("User-Agent", "MZY-Downloader/2.0 (HTTP/3 QUIC, multi-thread)")
	resp, err := client.Do(req)
	if err != nil {
		return 0, false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, false, fmt.Errorf("HEAD HTTP %d", resp.StatusCode)
	}
	size = resp.ContentLength
	ar := strings.ToLower(resp.Header.Get("Accept-Ranges"))
	acceptRanges = ar == "bytes"
	return size, acceptRanges, nil
}

func downloadChunk(ctx context.Context, client *http.Client, url, chunkFile string, start, end int64, downloaded *atomic.Int64) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "MZY-Downloader/2.0 (HTTP/3 QUIC, multi-thread)")
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusPartialContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	f, err := os.Create(chunkFile)
	if err != nil {
		return err
	}
	defer f.Close()

	buf := make([]byte, 256*1024)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return werr
			}
			downloaded.Add(int64(n))
		}
		if rerr != nil {
			if rerr == io.EOF {
				break
			}
			return rerr
		}
	}
	return nil
}

func singleThread(ctx context.Context, client *http.Client, url, part string, total int64, proto string) error {
	var resumed int64
	if fi, err := os.Stat(part); err == nil {
		resumed = fi.Size()
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "MZY-Downloader/2.0 (HTTP/3 QUIC)")
	if resumed > 0 {
		req.Header.Set("Range", "bytes="+strconv.FormatInt(resumed, 1)+"-")
	}

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	f, err := os.OpenFile(part, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()

	if resumed > 0 && resp.StatusCode != http.StatusPartialContent {
		resumed = 0
		_ = f.Truncate(0)
	}

	buf := make([]byte, 256*1024)
	start := time.Now()
	var downloaded int64 = resumed
	lastFlush := time.Now()

	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return werr
			}
			downloaded += int64(n)
		}
		if time.Since(lastFlush) > 500*time.Millisecond {
			elapsed := time.Since(start).Seconds()
			speed := int64(0)
			if elapsed > 0 {
				speed = int64(float64(downloaded-resumed) / elapsed)
			}
			writeProgress(Progress{State: "downloading", Downloaded: downloaded, Total: total, Speed: speed})
			lastFlush = time.Now()
		}
		if rerr != nil {
			if rerr == io.EOF {
				break
			}
			return rerr
		}
	}

	if *expected > 0 {
		fi, e := os.Stat(part)
		if e == nil && fi.Size() != *expected {
			os.Remove(part)
			return fmt.Errorf("size mismatch: got %d want %d", fi.Size(), *expected)
		}
	}
	return os.Rename(part, *out)
}
