# VLM 本地部署接入记录

## 基本信息

项目目录：

```bash
/data/iscas/智能信息平台/back
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

项目后端容器访问宿主机的网关地址：

```bash
10.21.6.1
```

项目中最终配置的 VLM/OCR API 地址：

```bash
http://10.21.6.1:8124/v1
```

## 部署过程

先确认 8124 端口未被占用：

```bash
ss -ltnp | grep 8124 || echo "8124可用"
```

启动 Qwen3VL 的 OpenAI 兼容接口：

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

启动成功后验证：

```bash
curl -sS http://127.0.0.1:8124/v1/models | python3 -m json.tool
```

返回模型列表即可。

## 常驻服务配置

为了避免 SSH 或 xterm 关闭后 VLM 服务退出，将启动命令写成 systemd 服务。

启动脚本：

```bash
/usr/local/bin/start-qwen3vl-8124.sh
```

systemd 服务：

```bash
/etc/systemd/system/qwen3vl-8124.service
```

常用命令：

```bash
sudo systemctl daemon-reload
sudo systemctl enable qwen3vl-8124
sudo systemctl start qwen3vl-8124
sudo systemctl status qwen3vl-8124 --no-pager
```

查看日志：

```bash
journalctl -u qwen3vl-8124 -f
```

退出日志查看：

```bash
Ctrl+C
```

## Docker 后端访问方式

后端服务运行在 Docker 容器中，容器内的 `127.0.0.1` 不是宿主机，所以不能在项目配置里填写：

```bash
http://127.0.0.1:8124/v1
```

需要先查看 Docker 网络网关：

```bash
docker network inspect intelligence-platform_intel-network --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}'
```

本次结果为：

```bash
10.21.6.1
```

因此项目后端应使用：

```bash
http://10.21.6.1:8124/v1
```

验证后端容器能否访问 VLM：

```bash
docker exec intel-platform-backend sh -c 'curl -sS http://10.21.6.1:8124/v1/models'
```

能返回模型列表，说明 Docker 网络通了。

## 写入项目配置

进入 MySQL：

```bash
docker exec -it intel-platform-mysql mysql -uroot -p intelligence_platform
```

写入 VLM 配置：

```sql
UPDATE llm_configs SET enabled=0 WHERE purpose='vlm';

INSERT INTO llm_configs
(name, provider, api_key, model, base_url, enabled, purpose, max_context_size, api_mode, description)
VALUES
('Local Qwen3VL','custom','','qwen3vl4b-test3-v3-robust-grpo','http://10.21.6.1:8124/v1',1,'vlm',4096,'chat_completions','本地GPU0 Qwen3VL图片理解模型');
```

写入 OCR 配置：

```sql
UPDATE llm_configs SET enabled=0 WHERE purpose='ocr';

INSERT INTO llm_configs
(name, provider, api_key, model, base_url, enabled, purpose, max_context_size, api_mode, description)
VALUES
('Local Qwen3VL OCR','custom','','qwen3vl4b-test3-v3-robust-grpo','http://10.21.6.1:8124/v1',1,'ocr',4096,'chat_completions','本地GPU0 Qwen3VL试用OCR/表格识别');
```

检查配置：

```sql
SELECT id,name,model,base_url,enabled,purpose,api_mode
FROM llm_configs
WHERE purpose IN ('vlm','ocr')
ORDER BY id DESC
LIMIT 10;
```

退出 MySQL：

```sql
exit;
```

重启后端：

```bash
docker compose restart backend
```

检查后端日志：

```bash
docker compose logs --tail=100 backend | grep -iE 'vlm|ocr|vision|error|exception'
```

没有输出，说明最近日志里没有相关报错。

## 验收结果

本次验证通过的内容：

- 宿主机可以访问 `http://127.0.0.1:8124/v1/models`
- 后端容器可以访问 `http://10.21.6.1:8124/v1/models`
- VLM 和 OCR 配置已写入 `llm_configs`
- 后端重启后没有出现 VLM/OCR 相关错误日志

最终需要在网页端上传图片，测试图片理解、OCR 或表格识别结果。

## 维护方式

查看 VLM 服务状态：

```bash
sudo systemctl status qwen3vl-8124 --no-pager
```

重启 VLM 服务：

```bash
sudo systemctl restart qwen3vl-8124
```

停止 VLM 服务：

```bash
sudo systemctl stop qwen3vl-8124
```

查看 VLM 日志：

```bash
journalctl -u qwen3vl-8124 -f
```

查看 GPU 使用情况：

```bash
nvidia-smi
```

查看 8124 端口：

```bash
ss -ltnp | grep 8124
```

如果网页端图片功能失效，排查顺序固定为：

```bash
sudo systemctl status qwen3vl-8124 --no-pager
curl -sS http://127.0.0.1:8124/v1/models | python3 -m json.tool
docker exec intel-platform-backend sh -c 'curl -sS http://10.21.6.1:8124/v1/models'
docker compose logs --tail=100 backend | grep -iE 'vlm|ocr|vision|error|exception'
```

## 注意事项

- 8124 是 VLM 服务端口，不是项目前端端口。
- 项目前端仍然通过 `http://服务器IP/` 访问。
- Docker 后端访问宿主机服务时使用 `10.21.6.1`，不要写 `127.0.0.1`。
- VLM 服务依赖 GPU 0，重启或多人共用服务器时要注意显存占用。
- 当前 OCR 使用 Qwen3VL 试用，适合图片理解和普通文字识别；如果后续表格识别效果不稳定，再换专门 OCR 模型。
