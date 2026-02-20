package main

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/abema/go-mp4"
	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"github.com/grandcat/zeroconf"
)

// ---- 数据结构 ----

type VideoFile struct {
	Path     string
	Duration float64 // 秒
}

type Channel struct {
	ID           int
	Name         string
	Videos       []VideoFile
	TotalSeconds float64
}

// ---- 全局状态 ----

var channels []Channel
var currentChIndex int // 后端维护当前频道，所有 tv 端保持一致

const videoDir = "/home/oasis/视频/mytv/gpu_processed"

// ---- MP4 时长解析 ----

func getMp4Duration(path string) (float64, error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, err
	}
	defer f.Close()

	var duration float64
	_, err = mp4.ReadBoxStructure(f, func(h *mp4.ReadHandle) (interface{}, error) {
		if h.BoxInfo.Type == mp4.BoxTypeMvhd() {
			box, _, err := h.ReadPayload()
			if err != nil {
				return nil, err
			}
			switch b := box.(type) {
			case *mp4.Mvhd:
				if b.GetVersion() == 0 {
					duration = float64(b.DurationV0) / float64(b.Timescale)
				} else {
					duration = float64(b.DurationV1) / float64(b.Timescale)
				}
			}
		}
		return h.Expand()
	})
	return duration, err
}

// ---- 启动时扫描目录 ----

func scanChannels(root string) ([]Channel, error) {
	entries, err := os.ReadDir(root)
	if err != nil {
		return nil, err
	}

	var result []Channel
	id := 0
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		chDir := filepath.Join(root, entry.Name())
		files, err := os.ReadDir(chDir)
		if err != nil {
			continue
		}

		var videos []VideoFile
		for _, f := range files {
			if f.IsDir() {
				continue
			}
			if filepath.Ext(f.Name()) != ".mp4" {
				continue
			}
			fullPath := filepath.Join(chDir, f.Name())
			dur, err := getMp4Duration(fullPath)
			if err != nil || dur <= 0 {
				fmt.Printf("警告: 无法读取时长 %s: %v\n", fullPath, err)
				continue
			}
			videos = append(videos, VideoFile{Path: fullPath, Duration: dur})
		}

		if len(videos) == 0 {
			continue
		}

		// 按文件名排序（ReadDir 已经是字母序，但显式 sort 更保险）
		sort.Slice(videos, func(i, j int) bool {
			return filepath.Base(videos[i].Path) < filepath.Base(videos[j].Path)
		})

		var total float64
		for _, v := range videos {
			total += v.Duration
		}

		result = append(result, Channel{
			ID:           id,
			Name:         entry.Name(),
			Videos:       videos,
			TotalSeconds: total,
		})
		id++
	}
	return result, nil
}

// ---- 时间轴计算 ----

type PlayPosition struct {
	FileIndex int     `json:"fileIndex"`
	Offset    float64 `json:"offset"` // 秒
}

func calcPosition(ch Channel) PlayPosition {
	now := float64(time.Now().Unix())
	pos := math_mod(now, ch.TotalSeconds)

	var acc float64
	for i, v := range ch.Videos {
		if pos < acc+v.Duration {
			return PlayPosition{FileIndex: i, Offset: pos - acc}
		}
		acc += v.Duration
	}
	return PlayPosition{FileIndex: 0, Offset: 0}
}

func math_mod(a, b float64) float64 {
	return a - float64(int(a/b))*b
}

// ---- WebSocket Hub ----

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

type Hub struct {
	tvConns map[*websocket.Conn]bool
	mu      sync.Mutex
}

var hub = Hub{tvConns: make(map[*websocket.Conn]bool)}

// ---- main ----

func main() {
	var err error
	fmt.Println("扫描视频目录...")
	channels, err = scanChannels(videoDir)
	if err != nil {
		fmt.Printf("扫描失败: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("加载完成，共 %d 个频道\n", len(channels))
	for _, ch := range channels {
		fmt.Printf("  [%d] %s — %d 个视频，总时长 %.0f 秒\n", ch.ID, ch.Name, len(ch.Videos), ch.TotalSeconds)
	}

	r := gin.Default()
	api := r.Group("/api")
	{
		api.GET("/ws", handleWS)
		api.GET("/send", handleSend)

		// 返回频道列表（id + name + fileCount）
		api.GET("/channels", func(c *gin.Context) {
			type ChInfo struct {
				ID        int    `json:"id"`
				Name      string `json:"name"`
				FileCount int    `json:"fileCount"`
			}
			var list []ChInfo
			for _, ch := range channels {
				list = append(list, ChInfo{ID: ch.ID, Name: ch.Name, FileCount: len(ch.Videos)})
			}
			c.JSON(200, list)
		})

		// 返回当前播放位置
		api.GET("/position/:id", func(c *gin.Context) {
			id, _ := strconv.Atoi(c.Param("id"))
			if id < 0 || id >= len(channels) {
				c.JSON(404, gin.H{"error": "频道不存在"})
				return
			}
			pos := calcPosition(channels[id])
			c.JSON(200, pos)
		})

		// 节目单：当前 + 后续3条
		api.GET("/schedule/:id", func(c *gin.Context) {
			id, _ := strconv.Atoi(c.Param("id"))
			if id < 0 || id >= len(channels) {
				c.JSON(404, gin.H{"error": "频道不存在"})
				return
			}
			ch := channels[id]
			pos := calcPosition(ch)

			type Item struct {
				Title    string `json:"title"`
				StartsAt string `json:"startsAt"`
				EndsAt   string `json:"endsAt"`
				Current  bool   `json:"current"`
			}

			now := time.Now()
			// 当前文件已播了 pos.Offset 秒，所以它的开始时间是 now - offset
			startOfCurrent := now.Add(-time.Duration(pos.Offset) * time.Second)

			var items []Item
			t := startOfCurrent
			for i := 0; i < 4; i++ {
				idx := (pos.FileIndex + i) % len(ch.Videos)
				v := ch.Videos[idx]
				end := t.Add(time.Duration(v.Duration) * time.Second)
				name := filepath.Base(v.Path)
				name = name[:len(name)-len(filepath.Ext(name))] // 去掉扩展名
				items = append(items, Item{
					Title:    name,
					StartsAt: t.Format("15:04"),
					EndsAt:   end.Format("15:04"),
					Current:  i == 0,
				})
				t = end
			}
			c.JSON(200, items)
		})

		// 流式传输视频文件
		api.GET("/video/:id/:fileIndex", func(c *gin.Context) {
			id, _ := strconv.Atoi(c.Param("id"))
			fi, _ := strconv.Atoi(c.Param("fileIndex"))
			if id < 0 || id >= len(channels) {
				c.Status(404)
				return
			}
			ch := channels[id]
			if fi < 0 || fi >= len(ch.Videos) {
				c.Status(404)
				return
			}
			c.Header("Content-Type", "video/mp4")
			http.ServeFile(c.Writer, c.Request, ch.Videos[fi].Path)
		})
	}

	r.GET("/", func(c *gin.Context) {
		c.Redirect(http.StatusMovedPermanently, "/tv.html")
	})

	r.NoRoute(func(c *gin.Context) {
		c.File("./dist" + c.Request.URL.Path)
	})

	fmt.Println("TV-Hub 运行在 :8080")

	// 注册 mDNS 服务，让局域网内的设备能自动发现
	mdns, err := zeroconf.Register("mytv-hub", "_mytv._tcp", "local.", 8080, []string{"version=1"}, nil)
	if err != nil {
		fmt.Printf("mDNS 注册失败（不影响正常使用）: %v\n", err)
	} else {
		defer mdns.Shutdown()
		fmt.Println("mDNS 已注册: mytv-hub._mytv._tcp.local.")
	}

	// UDP 广播，让局域网设备自动发现
	go func() {
		addr, _ := net.ResolveUDPAddr("udp", "255.255.255.255:5354")
		conn, err := net.DialUDP("udp", nil, addr)
		if err != nil {
			fmt.Printf("UDP 广播启动失败: %v\n", err)
			return
		}
		defer conn.Close()
		msg := []byte("mytv:8080")
		for {
			conn.Write(msg)
			time.Sleep(2 * time.Second)
		}
	}()

	r.Run(":8080")
}

// ---- WebSocket 处理 ----

func handleWS(c *gin.Context) {
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		return
	}
	hub.mu.Lock()
	hub.tvConns[conn] = true
	// 新连接立即同步当前频道
	conn.WriteMessage(websocket.TextMessage, []byte(fmt.Sprintf("SWITCH:%d", currentChIndex)))
	hub.mu.Unlock()
	fmt.Printf("电视端已连接，当前共 %d 个\n", len(hub.tvConns))

	defer func() {
		hub.mu.Lock()
		delete(hub.tvConns, conn)
		hub.mu.Unlock()
		conn.Close()
		fmt.Printf("电视端断开，当前共 %d 个\n", len(hub.tvConns))
	}()

	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			break
		}
	}
}

func handleSend(c *gin.Context) {
	cmd := c.Query("cmd")
	hub.mu.Lock()
	defer hub.mu.Unlock()

	if len(hub.tvConns) == 0 {
		c.JSON(404, gin.H{"status": "无电视端连接"})
		return
	}

	// 遥控器切台：后端算出新频道号，广播绝对指令
	total := len(channels)
	outCmd := cmd
	if cmd == "NEXT" {
		currentChIndex = (currentChIndex + 1) % total
		outCmd = fmt.Sprintf("SWITCH:%d", currentChIndex)
	} else if cmd == "PREV" {
		currentChIndex = (currentChIndex - 1 + total) % total
		outCmd = fmt.Sprintf("SWITCH:%d", currentChIndex)
	}

	var failed []*websocket.Conn
	for conn := range hub.tvConns {
		if err := conn.WriteMessage(websocket.TextMessage, []byte(outCmd)); err != nil {
			failed = append(failed, conn)
		}
	}
	for _, conn := range failed {
		delete(hub.tvConns, conn)
		conn.Close()
	}

	c.JSON(200, gin.H{"status": "已发送", "command": outCmd, "receivers": len(hub.tvConns)})
}
