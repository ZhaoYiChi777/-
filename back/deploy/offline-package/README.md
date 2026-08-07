# 离线镜像包制作与部署

这个目录用于准备离线部署包，适合目标服务器无法访问 Docker Hub、Maven、npm、crates.io 的情况。

## 目录说明

```text
deploy/offline-package/
  prepare-offline-package.sh  在线机器执行，构建并导出离线包
  load-offline-package.sh     离线服务器执行，导入镜像并启动项目
  README.md                   本说明
```

执行 `prepare-offline-package.sh` 后，会生成：

```text
deploy/offline-package/dist/intelligence-platform-offline-时间戳/
  images/intelligence-platform-images.tar.gz
  project/
  load-offline-package.sh
  IMAGE_LIST.txt
  README-OFFLINE.txt
```

## 第一步：在可联网机器制作离线包

可联网机器必须可以访问：

```text
Docker Hub
Maven 仓库
npm registry
crates.io 或 Rust 依赖源
```

进入项目根目录：

```bash
cd /root/back
```

执行：

```bash
bash deploy/offline-package/prepare-offline-package.sh
```

也可以指定输出目录：

```bash
bash deploy/offline-package/prepare-offline-package.sh /tmp/intelligence-platform-offline
```

脚本会做这些事：

```text
1. docker compose build 构建 backend / frontend / kg-compute 镜像
2. docker pull mysql:8.0 拉取 MySQL 镜像
3. docker save 导出所有运行所需镜像
4. 复制项目文件到离线包 project/ 目录
5. 排除 .env、node_modules、target、.git 等不应打包的内容
```

离线包包含这些镜像：

```text
mysql:8.0
intelligence-platform/backend:offline
intelligence-platform/frontend:offline
intelligence-platform/kg-compute:offline
```

## 第二步：把离线包传到目标服务器

把整个生成目录上传到目标服务器，例如：

```text
/root/intelligence-platform-offline/
```

上传后目标服务器目录类似：

```text
/root/intelligence-platform-offline/images/intelligence-platform-images.tar.gz
/root/intelligence-platform-offline/project/docker-compose.yml
/root/intelligence-platform-offline/load-offline-package.sh
```

## 第三步：离线服务器安装 Docker

离线包只能解决项目镜像和构建依赖问题。目标服务器仍然必须提前有 Docker Engine 和 Docker Compose 插件。

如果目标服务器连 Docker 安装源也无法访问，需要提前准备 Docker 的 `.deb` 安装包，或在有网环境安装好 Docker 后再进入离线部署。

检查：

```bash
docker --version
docker compose version
```

## 第四步：离线导入并启动

进入离线包目录：

```bash
cd /root/intelligence-platform-offline
```

导入镜像、复制项目到 `/root/back` 并启动：

```bash
bash load-offline-package.sh --project-dir /root/back
```

只导入镜像和复制项目，不启动服务：

```bash
bash load-offline-package.sh --project-dir /root/back --no-start
```

脚本启动服务时使用：

```bash
docker compose up -d --no-build --remove-orphans
```

因此不会访问 Maven、npm、crates.io，也不会重新构建镜像。

## 第五步：配置 .env

离线包默认不携带 `.env`，避免把 API Key、数据库密码等敏感信息打包带走。

离线服务器第一次加载时，如果 `/root/back/.env` 不存在，脚本会从 `.env.example` 生成一个基础 `.env`，并生成随机 MySQL 密码。

部署后请检查：

```bash
cd /root/back
nano .env
```

按现场情况填写：

```text
LLM_API_KEY
LLM_API_BASE_URL
LLM_MODEL
EMBEDDING_API_KEY
RERANK_API_KEY
```

如果这些 API 也无法从离线服务器访问，相关智能功能无法正常使用，需要改成内网可访问的大模型或本地模型服务地址。

## 常见问题

### 离线服务器 docker compose up 仍然尝试 build

确认使用的是离线加载脚本，或者手动启动时使用：

```bash
docker compose up -d --no-build
```

不要在离线服务器执行：

```bash
docker compose up -d --build
```

### 提示镜像不存在

检查离线包是否完整：

```bash
ls images
cat IMAGE_LIST.txt
```

重新加载：

```bash
docker load -i images/intelligence-platform-images.tar.gz
```

如果是 `.tar.gz`：

```bash
gzip -dc images/intelligence-platform-images.tar.gz | docker load
```

### 端口被占用

默认需要：

```text
80
8080
8101
3306
```

检查：

```bash
sudo ss -ltnp | grep -E ':80|:8080|:8101|:3306'
```

释放端口或修改 `docker-compose.yml` 的宿主机端口映射。

### 离线包太大

这是正常的。离线包会包含 MySQL、后端、前端、kg-compute 的完整镜像层，通常会达到数 GB。

可以使用移动硬盘、内网文件传输、SFTP 断点续传工具传输。
