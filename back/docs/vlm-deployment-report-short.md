# VLM 部署简要说明

## 部署目标

本次在内网服务器上部署本地 VLM 服务，用于项目中的图片理解、OCR/表格识别等功能。

## 部署环境

项目部署目录：

```bash
/data/iscas/智能信息平台/back
```

复用的 VLM 模型文件位置：

```bash
/data/cxk/test2/outputs/qwen3vl4b_midchain_sft_best_step600_hf
```

复用的 Python/vLLM 运行环境：

```bash
/root/anaconda3/envs/swift_qwen3vl/bin/python
```

VLM 模型目录：

```bash
/data/cxk/test2/outputs/qwen3vl4b_midchain_sft_best_step600_hf
```

模型服务端口：

```bash
8124
```

模型名称：

```bash
qwen3vl4b-test3-v3-robust-grpo
```

使用显卡：

```bash
GPU 0
```

## 部署方式

服务器本地已有 Qwen3VL 模型文件，因此没有从公网下载模型。通过 vLLM 启动 OpenAI 兼容接口，对外提供：

```bash
http://127.0.0.1:8124/v1
```

实际启动命令复用了服务器上的 Qwen3VL 模型目录和 `swift_qwen3vl` 环境：

```bash
CUDA_VISIBLE_DEVICES=0 \
/root/anaconda3/envs/swift_qwen3vl/bin/python -m vllm.entrypoints.openai.api_server \
  --model /data/cxk/test2/outputs/qwen3vl4b_midchain_sft_best_step600_hf \
  --served-model-name qwen3vl4b-test3-v3-robust-grpo \
  --host 0.0.0.0 \
  --port 8124 \
  --trust-remote-code \
  --dtype bfloat16 \
  --max-model-len 4096 \
  --gpu-memory-utilization 0.70 \
  --limit-mm-per-prompt '{"image":1}'
```

关键参数：

```bash
CUDA_VISIBLE_DEVICES=0
port=8124
max_model_len=4096
gpu_memory_utilization=0.70
```

## 常驻服务

为了避免 SSH 或 xterm 关闭后服务停止，将 VLM 启动命令注册为 systemd 服务：

```bash
qwen3vl-8124.service
```

启动脚本位置：

```bash
/usr/local/bin/start-qwen3vl-8124.sh
```

systemd 配置文件位置：

```bash
/etc/systemd/system/qwen3vl-8124.service
```

常用维护命令：

```bash
sudo systemctl status qwen3vl-8124 --no-pager
sudo systemctl restart qwen3vl-8124
journalctl -u qwen3vl-8124 -f
```

服务已设置为开机自启。

## 项目接入方式

项目后端运行在 Docker 容器内，不能直接使用宿主机的 `127.0.0.1`。通过 Docker 网络网关访问宿主机 VLM 服务：

```bash
http://10.21.6.1:8124/v1
```

已在项目数据库 `llm_configs` 中写入两类配置：

```text
purpose=vlm：图片理解
purpose=ocr：OCR/表格识别
```

两项配置均使用同一个本地 Qwen3VL 服务。

## 验证结果

宿主机访问模型服务成功：

```bash
curl http://127.0.0.1:8124/v1/models
```

Docker 后端容器访问模型服务成功：

```bash
docker exec intel-platform-backend sh -c 'curl http://10.21.6.1:8124/v1/models'
```

后端重启后未出现 VLM/OCR 相关错误日志。

## 后期维护

查看 VLM 是否还在运行：

```bash
sudo systemctl status qwen3vl-8124 --no-pager
```

重启 VLM：

```bash
sudo systemctl restart qwen3vl-8124
```

查看 VLM 实时日志：

```bash
journalctl -u qwen3vl-8124 -f
```

查看 8124 端口：

```bash
ss -ltnp | grep 8124
```

查看 GPU 使用情况：

```bash
nvidia-smi
```

验证宿主机能访问 VLM：

```bash
curl -sS http://127.0.0.1:8124/v1/models | python3 -m json.tool
```

验证 Docker 后端能访问 VLM：

```bash
docker exec intel-platform-backend sh -c 'curl -sS http://10.21.6.1:8124/v1/models'
```

如果项目网页图片识别失败，优先按以下顺序排查：

```bash
sudo systemctl status qwen3vl-8124 --no-pager
nvidia-smi
curl -sS http://127.0.0.1:8124/v1/models | python3 -m json.tool
docker exec intel-platform-backend sh -c 'curl -sS http://10.21.6.1:8124/v1/models'
docker compose logs --tail=100 backend | grep -iE 'vlm|ocr|vision|error|exception'
```

## 当前状态

VLM 已作为服务器本地常驻服务运行，并已接入项目的图片理解和 OCR/表格识别功能。后续如识别效果不足，可替换为专门 OCR 模型，项目侧只需更新 `llm_configs` 中对应模型配置。

## 关于 BGE-M3 未复用的原因

部署过程中也检查过服务器上的 BGE-M3 相关进程，目的是确认能否直接复用它作为项目的 Embedding 向量模型。

当时尝试查找 45101 端口：

```bash
ss -ltnp | grep 45101
```

没有返回结果，说明当前服务器上没有进程监听 `45101` 端口。

随后查看服务器上的模型和相关进程：

```bash
ps -ef | grep -iE 'vllm|bge|m3|embedding' | grep -v grep
```

能看到 BGE-M3 或 reranker 相关名称，但没有确认到一个可直接访问的 HTTP Embedding 服务，例如：

```bash
http://宿主机IP:端口/v1/embeddings
```

也就是说，服务器上可能存在 BGE-M3 模型文件或内部模型进程，但没有暴露成项目可调用的 OpenAI 兼容接口。

项目接入 Embedding 需要的是可调用接口，而不是单纯存在模型文件。项目后端需要能请求类似下面的地址：

```bash
POST /v1/embeddings
```

请求格式类似：

```json
{
  "model": "BAAI/bge-m3",
  "input": "测试文本"
}
```

并返回：

```json
{
  "data": [
    {
      "embedding": [...]
    }
  ]
}
```

之前也测试过网关上的 BGE-M3 Embedding 接口，但返回了 `500 Internal Server Error`，说明该接口本身没有正常提供服务，不属于项目配置问题。

因此本次没有复用 BGE-M3。后续如果要接入 BGE-M3，需要先单独启动一个稳定的 Embedding API 服务，并保证项目后端容器可以访问，例如：

```bash
curl http://Embedding服务地址/v1/embeddings
docker exec intel-platform-backend sh -c 'curl http://Embedding服务地址/v1/embeddings'
```

只有这两步都能通，才可以把它写入 `llm_configs`，作为 `purpose=embedding` 的配置。
