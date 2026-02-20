#!/bin/bash

# 配置你的手机信息
PHONE_IP="192.168.0.103"  # 修改为你的手机 IP
PHONE_PORT="8022"       # Termux 默认端口
TARGET_DIR="/data/data/com.termux/files/home"
APP_NAME="mytv"

echo "开始交叉编译 for Android ARM64..."
# CGO_ENABLED=0 确保静态链接，避免缺少安卓系统库的问题
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o $APP_NAME main.go

echo "正在推送到手机..."
scp -P $PHONE_PORT $APP_NAME root@$PHONE_IP:$TARGET_DIR/

echo "赋予执行权限..."
ssh -p $PHONE_PORT root@$PHONE_IP "chmod +x $TARGET_DIR/$APP_NAME"
