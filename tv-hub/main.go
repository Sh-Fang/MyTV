package main

import (
	"fmt"
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

const videoDir = "/home/oasis/视频/mytv/gpu_processed" // 修改为你的视频根目录

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
	tvConn *websocket.Conn
	mu     sync.Mutex
}

var hub = Hub{}

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
	r.Run(":8080")
}

// ---- WebSocket 处理 ----

func handleWS(c *gin.Context) {
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		return
	}
	hub.mu.Lock()
	hub.tvConn = conn
	hub.mu.Unlock()
	fmt.Println("电视端已连接")

	defer func() {
		hub.mu.Lock()
		hub.tvConn = nil
		hub.mu.Unlock()
		conn.Close()
		fmt.Println("电视端断开")
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
	if hub.tvConn != nil {
		if err := hub.tvConn.WriteMessage(websocket.TextMessage, []byte(cmd)); err != nil {
			c.JSON(500, gin.H{"status": "推送失败"})
			return
		}
		c.JSON(200, gin.H{"status": "已发送", "command": cmd})
	} else {
		c.JSON(404, gin.H{"status": "电视端未连接"})
	}
}
