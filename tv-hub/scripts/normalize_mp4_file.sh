#!/bin/bash

# 检查依赖
for cmd in ffmpeg ffprobe; do
    if ! command -v $cmd &> /dev/null; then
        echo "错误: 未安装 $cmd。请先执行 pkg install ffmpeg"
        exit 1
    fi
done

echo "开始处理视频目录: $(pwd)"
echo "--------------------------------------"

count=0; success=0; failed=0; skipped=0

# 使用 find 处理复杂文件名
find . -type f -name "*.mp4" -print0 | while IFS= read -r -d '' f; do
    # 避开临时文件
    [[ "$f" == *"_faststart_tmp.mp4" ]] && continue
    
    ((count++))
    echo "[$count] 正在检查: $f"

    # --- 核心检测逻辑 ---
    # 使用 ffprobe 获取 top-level boxes 的顺序
    # 如果第一个 box 是 ftyp 且第二个是 moov，则认为是 faststart
    is_faststart=$(ffprobe -v error -show_packets -show_format -show_data -show_entries "format_tags=major_brand" -find_stream_info "$f" 2>&1 | head -c 1000)
    
    # 更简单稳妥的方法：利用 ffprobe 检查 moov 是否在 mdat 之前
    # 我们检查前 100KB 数据中是否包含 moov
    if head -c 102400 "$f" | grep -q "moov"; then
        echo "   ⏩ 跳过: 该文件已经是 Faststart 格式"
        ((skipped++))
        continue
    fi
    # --- 检测结束 ---

    tmp="${f%.mp4}_faststart_tmp.mp4"
    echo "   🛠️  正在转换..."

    if ffmpeg -y -loglevel error -i "$f" -c copy -movflags +faststart "$tmp" -nostdin; then
        if mv "$tmp" "$f"; then
            echo "   ✅ 处理成功"
            ((success++))
        else
            echo "   ❌ 替换失败"
            ((failed++))
        fi
    else
        echo "   ❌ 转换失败"
        [ -f "$tmp" ] && rm "$tmp"
        ((failed++))
    fi
done

echo "--------------------------------------"
echo "处理完成！"
echo "总计: $count | 跳过: $skipped | 成功: $success | 失败: $failed"
