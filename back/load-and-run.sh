#!/bin/bash
# ============================================================
# 译丛译著库 · 智能情报分析平台 - 一键构建并启动
# 适用于接收方首次部署
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"

echo "============================================"
echo "  译丛译著库 · 智能情报分析平台 - 一键部署"
echo "============================================"
echo ""

# 检查Docker
if ! command -v docker &> /dev/null; then
    echo "❌ 未检测到Docker"
    echo ""
    echo "请先安装Docker："
    echo "  macOS:   brew install --cask docker"
    echo "  Linux:   curl -fsSL https://get.docker.com | sh"
    echo "  Windows: https://www.docker.com/products/docker-desktop"
    echo ""
    echo "安装后请启动Docker Desktop，然后重新运行此脚本"
    exit 1
fi

# 检查Docker是否运行
if ! docker info &> /dev/null; then
    echo "❌ Docker未运行"
    echo "请启动Docker Desktop或Docker服务后重试"
    exit 1
fi

# 选择docker compose命令
if docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
elif docker-compose version &> /dev/null; then
    COMPOSE_CMD="docker-compose"
else
    echo "❌ 未检测到Docker Compose"
    echo "请安装Docker Compose: https://docs.docker.com/compose/install/"
    exit 1
fi

echo "✓ Docker环境检测通过"
echo "  Docker: $(docker --version)"
echo "  Compose: ${COMPOSE_CMD}"
echo ""

# 创建.env文件（如果不存在）
if [ ! -f .env ]; then
    echo "[1/4] 创建环境配置..."
    cp .env.example .env
    echo "   ✓ 已创建 .env 配置文件"
    echo "   提示: 如需修改密码等配置，请编辑 .env 文件"
else
    echo "[1/4] 环境配置已存在，跳过"
fi

echo ""

# 构建并启动服务
echo "[2/5] 构建Docker镜像（首次构建需要5-10分钟）..."
echo "   正在构建后端镜像..."
${COMPOSE_CMD} build backend

echo "   正在构建前端镜像..."
${COMPOSE_CMD} build frontend

echo "   正在构建图谱计算服务镜像..."
${COMPOSE_CMD} build kg-compute

echo "   ✓ 镜像构建完成"
echo ""

# 启动服务
echo "[3/5] 启动服务..."
${COMPOSE_CMD} up -d
echo "   ✓ 所有服务已启动"
echo ""

# 等待服务就绪
echo "[4/5] 等待服务就绪..."
echo "   MySQL初始化中..."
sleep 10

echo "   检查后端服务..."
for i in {1..30}; do
    if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo "   ✓ 后端服务已就绪"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "   ⚠ 后端服务启动超时，请查看日志: ${COMPOSE_CMD} logs backend"
    fi
    sleep 2
done

echo "   检查前端服务..."
if curl -s http://localhost > /dev/null 2>&1; then
    echo "   ✓ 前端服务已就绪"
else
    echo "   ⚠ 前端服务可能未完全就绪，请稍后刷新页面"
fi

echo ""
echo "============================================"
echo "  ✅ 部署完成！"
echo "============================================"
echo ""
echo "  访问地址:"
echo "    前端界面: http://localhost"
echo "    后端API:  http://localhost:8080"
echo ""
echo "  默认配置:"
echo "    MySQL用户名: root"
echo "    MySQL密码:   $(grep MYSQL_ROOT_PASSWORD .env | cut -d'=' -f2)"
echo ""
echo "  常用命令:"
echo "    查看日志:   ${COMPOSE_CMD} logs -f"
echo "    停止服务:   ${COMPOSE_CMD} down"
echo "    重启服务:   ${COMPOSE_CMD} restart"
echo "    查看状态:   ${COMPOSE_CMD} ps"
echo ""
echo "  详细说明请查看: README.md"
echo ""
${COMPOSE_CMD} ps
echo ""
