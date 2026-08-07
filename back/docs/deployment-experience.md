# 服务器部署经验记录

## 1. 部署背景

本项目部署在内网 Ubuntu 服务器上，服务器已有 Docker 环境。由于服务器无法稳定访问外部网络，不能直接在服务器上执行在线镜像构建，因此本次采用“本机构建镜像、服务器离线加载镜像”的方式完成部署。

项目实际部署目录为：

```bash
/data/iscas/智能信息平台/back
```

说明：项目的 `docker-compose.yml` 位于 `back` 目录下，因此 Docker Compose 的执行目录和 `--project-dir` 均应指向该目录。

## 2. 离线镜像部署流程

本次部署没有在服务器上直接执行 `docker compose up -d --build`，因为该命令需要访问 Docker Hub、Maven、npm、Cargo 等外部依赖源，内网环境会出现请求取消或连接失败。

实际采用流程如下：

```text
本机 docker compose build
本机 docker pull mysql:8.0
本机 docker save 导出镜像包
scp 上传镜像包到服务器
服务器 docker load 导入镜像
服务器 docker compose up -d --no-build 启动
```

离线镜像包示例：

```text
intelligence-platform-images.tar
```

服务器启动命令：

```bash
cd /data/iscas/智能信息平台/back
docker compose up -d --no-build
```

## 3. 端口调整记录

部署过程中发现服务器 `8080` 端口已被占用，因此曾将后端宿主机端口从：

```yaml
8080:8080
```

调整为：

```yaml
8889:8080
```

含义为：

```text
宿主机 8889 端口 -> backend 容器内部 8080 端口
```

后续为了降低暴露面，建议后端不再直接对外暴露端口，只由前端 Nginx 容器通过 Docker 内部网络访问：

```text
frontend -> backend:8080
```

正式访问入口保留：

```text
http://服务器IP/
```

建议最终仅对外开放：

```text
80    前端访问入口
```

不建议对外暴露：

```text
3306  MySQL
8889  Backend
8101  kg-compute
```

## 4. MySQL 初始化权限问题

部署过程中 MySQL 容器启动失败，日志提示：

```text
/docker-entrypoint-initdb.d/: permission denied
```

原因是 MySQL 容器无法读取宿主机挂载的初始化 SQL 目录：

```bash
/data/iscas/智能信息平台/back/init-db
```

修复命令：

```bash
cd /data/iscas/智能信息平台/back
sudo chmod 755 init-db
sudo chmod 644 init-db/*.sql
```

必要时可调整所有者：

```bash
sudo chown -R root:root init-db
```

该问题会影响数据库表结构和初始化数据是否能正常导入，是离线部署中需要重点检查的一项。

## 5. 常驻运行与开机恢复

项目通过 Docker Compose 后台运行：

```bash
docker compose up -d --no-build
```

Compose 文件中服务配置了：

```yaml
restart: unless-stopped
```

含义是容器异常退出或 Docker 服务重启后会自动恢复。

如需保证服务器重启后 Docker 自动启动，应执行：

```bash
sudo systemctl enable docker
sudo systemctl start docker
```

常用运维命令：

```bash
cd /data/iscas/智能信息平台/back
docker compose ps
docker compose logs --tail=100 backend
docker compose logs -f backend
docker compose restart backend
```

查看实时日志后退出：

```text
Ctrl + C
```

## 6. 访问验证过程

本次先通过 SSH 隧道验证服务：

```bash
ssh -L 18080:127.0.0.1:80 root@服务器IP
```

本机浏览器访问：

```text
http://127.0.0.1:18080/
```

确认系统可正常进入后，再验证其他电脑可直接访问：

```text
http://服务器IP/
```

说明服务器 `80` 端口已对目标网络开放，前端服务可被其他电脑访问。

## 7. 异常日志说明

后端日志中曾出现如下请求：

```text
/api/v4/internal/kubernetes/receptive-agents
```

该路径不是本项目业务接口，更像外部探测或无关客户端请求。该日志不作为部署失败依据。判断项目是否正常，应优先检查：

```bash
curl http://127.0.0.1/api/health
docker compose ps
docker compose logs --tail=100 backend
```

## 8. 本次部署经验总结

本次部署的核心难点不是项目代码，而是内网服务器环境下的镜像构建、端口冲突、容器权限和访问链路验证。最终采用离线镜像包方式解决网络受限问题，通过端口调整解决已有服务冲突，通过权限修复解决 MySQL 初始化失败，并通过 SSH 隧道和服务器 IP 访问完成前端验证。

这次经历体现了对 Docker Compose 部署、内网环境交付、端口映射、容器日志排查、服务健康检查和最小暴露面安全原则的掌握。

## 9. 需要掌握的技术知识

- Docker 镜像与容器：理解 `docker build`、`docker save`、`docker load`、`docker compose up` 的区别。
- Docker Compose：理解多服务编排、服务名通信、数据卷、网络和 `restart` 策略。
- 端口映射：理解 `8889:8080` 表示宿主机端口映射到容器内部端口。
- 内网离线部署：掌握在有网环境构建镜像，再传入内网服务器运行的流程。
- Linux 权限：理解目录 `755`、文件 `644` 对容器挂载目录读取的影响。
- Nginx 反向代理：理解前端容器通过 `/api` 将请求转发到后端服务。
- 日志排查：会使用 `docker compose logs`、`curl`、`docker compose ps` 判断服务状态。
- 安全暴露面：正式部署时只开放必要入口端口，数据库和内部计算服务不直接暴露到外部网络。
