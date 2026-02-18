package main

import (
	"fmt"
	"net/http"
	"sync"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // 允许跨域
	},
}

type Hub struct {
	tvConn *websocket.Conn
	mu     sync.Mutex
}

var hub = Hub{}

type Channel struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
	URL  string `json:"url"`
}

var channels = []Channel{
	{1, "CCTV-1", "http://example.com/cctv1.m3u8"},
	{2, "CCTV-6", "http://example.com/cctv6.m3u8"},
	{3, "湖南卫视", "http://example.com/hunan.m3u8"},
}

func main() {
	r := gin.Default()

	// --- 1. API 分组管理 (保持不变) ---
	api := r.Group("/api")
	{
		api.GET("/ws", handleWS)
		api.GET("/send", handleSend)
		api.GET("/channels", func(c *gin.Context) {
			c.JSON(200, channels)
		})
	}

	// --- 2. 处理首页跳转 ---
	// 如果用户直接访问 http://IP:8080/
	r.GET("/", func(c *gin.Context) {
		c.Redirect(http.StatusMovedPermanently, "/remote.html")
	})

	// --- 3. 静态文件兜底方案 (核心修改) ---
	// 不再使用 r.Static，而是使用 NoRoute
	// 这样请求进来时，Gin 会先匹配 /api，匹配不到才会进到这里
	r.NoRoute(func(c *gin.Context) {
		path := c.Request.URL.Path
		// 构建本地文件路径
		localFile := "./dist" + path

		// 尝试打开该文件
		c.File(localFile)
	})

	fmt.Println("TV-Hub 运行在 :8080")
	r.Run(":8080")
}

// 提取处理函数让 main 保持整洁
func handleWS(c *gin.Context) {
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		return
	}

	hub.mu.Lock()
	hub.tvConn = conn
	hub.mu.Unlock()

	fmt.Println("电视端已通过 WebSocket 连接 (via /api/ws)")

	defer func() {
		hub.mu.Lock()
		hub.tvConn = nil
		hub.mu.Unlock()
		conn.Close()
		fmt.Println("电视端断开连接")
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
		err := hub.tvConn.WriteMessage(websocket.TextMessage, []byte(cmd))
		if err != nil {
			c.JSON(500, gin.H{"status": "推送失败"})
			return
		}
		c.JSON(200, gin.H{"status": "已发送", "command": cmd})
	} else {
		c.JSON(404, gin.H{"status": "电视端未连接"})
	}
}
