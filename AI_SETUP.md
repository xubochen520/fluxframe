# AI 图片标签配置（llama.cpp）

本项目通过 OpenAI 兼容接口调用本地视觉模型生成建议标签，**图片不会发送到外网**。推荐使用 **llama.cpp** 作为本地推理引擎，完全离线、单文件、无需 Docker、**无需 API 密钥**。

## 0. 一键自动安装（推荐）

系统设置 → AI 图片标签 → 打开「启用 AI 标签分析」开关：

1. 自动检测本机是否已有 llama.cpp 在运行（8080/8000/11434 等端口），检测到则直接显示「成功」并自动填入接口地址；
2. 未检测到时，选择模型（7B 推荐 / 3B 轻量），可选「国内镜像下载」，点击 **自动下载并启动**；
3. 后端自动完成：下载 llama.cpp 引擎（GitHub Releases，自动识别 NVIDIA 显卡选 CUDA 版）→ 解压 → 下载 GGUF 模型 + mmproj → 启动服务并等待就绪；
4. 就绪后开关左侧显示「成功」，接口地址与模型名自动保存，上传图片即可自动生成标签。

下载的文件保存在项目根目录 `models\`（llama-server.exe + 两个 gguf），进度在设置页实时显示。启用 AI 后，每次启动 Node 服务也会自动拉起已下载的 llama.cpp（已运行则直接复用）。

## 1. 手动下载 llama.cpp

到 [llama.cpp Releases](https://github.com/ggml-org/llama.cpp/releases) 下载 Windows 版压缩包（当前命名规则）：

- 纯 CPU 机器：`llama-<版本>-bin-win-cpu-x64.zip`
- NVIDIA 显卡（推荐，速度快 5~20 倍）：`llama-<版本>-bin-win-cuda-12.4-x64.zip`（驱动 ≥ 550）或 `llama-<版本>-bin-win-cuda-13.3-x64.zip`（驱动 ≥ 580）

解压后，`llama-server.exe` 就是推理服务。自动下载会优先选择 CUDA 版并加 `-ngl 99` 全量 GPU 加速。

## 2. 下载视觉模型（GGUF + mmproj）

llama.cpp 跑视觉模型需要**两个文件**：模型主体 GGUF + 视觉投影 `--mmproj` 文件，都来自同一模型仓库。

> 注意：Qwen 官方 GGUF 仓库（`Qwen/Qwen2.5-VL-*-Instruct-GGUF`）需要 Hugging Face 登录（gated），因此自动下载使用公开镜像仓库 **mradermacher**（同为 Qwen2.5-VL 官方权重的 GGUF 量化，文件名不同）：

| 模型 | 仓库文件（Q4_K_M） | 下载量 | 显存/内存需求 |
| --- | --- | --- | --- |
| **Qwen2.5-VL-7B-Instruct**（稳妥首选） | `Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf` + `Qwen2.5-VL-7B-Instruct.mmproj-f16.gguf` | 约 5.7GB | 约 6GB 显存，或 8GB 内存纯 CPU |
| **Qwen2.5-VL-3B-Instruct**（低配机器） | `Qwen2.5-VL-3B-Instruct.Q4_K_M.gguf` + `Qwen2.5-VL-3B-Instruct.mmproj-fp16.gguf` | 约 3.1GB | 约 3GB 显存 / 4GB 内存 |

下载地址（Hugging Face）：

- [mradermacher/Qwen2.5-VL-7B-Instruct-GGUF](https://huggingface.co/mradermacher/Qwen2.5-VL-7B-Instruct-GGUF)
- [mradermacher/Qwen2.5-VL-3B-Instruct-GGUF](https://huggingface.co/mradermacher/Qwen2.5-VL-3B-Instruct-GGUF)

## 3. 网络与代理说明

自动下载会**自动发现系统代理**（读取 Windows 代理设置或 `HTTPS_PROXY`/`HTTP_PROXY` 环境变量）并经 CONNECT 隧道下载 GitHub 与 Hugging Face 资源；无代理时直连。国内网络建议保持系统代理开启；`hf-mirror.com` 镜像选项适用于代理不可用但镜像可达的环境。

## 4. 启动 llama-server

把两个文件放到同一目录（例如项目根目录 `models\`），执行：

```bat
llama-server.exe -m models\qwen2.5-vl-7b-instruct-q4_k_m.gguf --mmproj models\qwen2.5-vl-7b-instruct-mmproj-f16.gguf -c 8192 -fa --port 8080
```

参数说明：

- `-c 8192`：上下文长度，视觉识别足够；内存紧张可降到 4096
- `-fa`：Flash Attention，显著提速（旧版可用 `-f`）
- `--port 8080`：服务端口，默认 8080
- NVIDIA 显卡可加 `-ngl 99` 把层全部放 GPU

项目根目录提供了 `start-llamacpp.cmd`，把模型放进 `models\` 后双击即可启动。

启动后浏览器打开 `http://127.0.0.1:8080` 可看到服务状态；也可用 curl 验证：

```bat
curl http://127.0.0.1:8080/v1/models
```

## 5. 在本系统里配置

系统设置 → AI 图片标签：

- **AI 开关**：开启
- **AI 接口地址**：`http://127.0.0.1:8080/v1`（注意末尾的 `/v1`，llama.cpp 的 OpenAI 兼容路径）
- **模型名称**：任意备注（llama.cpp 不校验模型名），例如 `qwen2.5-vl-7b-instruct`
- **API 密钥**：留空

也可以在根目录 `.env` 中配置（DB 中的设置优先）：

```env
AI_ENABLED=true
AI_BASE_URL="http://127.0.0.1:8080/v1"
AI_MODEL="qwen2.5-vl-7b-instruct"
```

上传图片时程序会：把图片压缩到最长边 1280px 的 JPEG → 请求 llama.cpp 生成 4~12 个中文标签 → 在确认弹窗中展示，可增删后完成上传。

> 兼容性：仍保留 Ollama 原生 `/api/chat` 路径（接口地址填 `http://127.0.0.1:11434` 时自动启用）；其他 OpenAI 兼容服务（LM Studio、vLLM 等）同样可用。请求带 `response_format: json_object` 强制结构化输出，旧版 llama.cpp 不支持时自动降级重试。

## 6. 识别提示词（可编辑）

默认提示词在 **`server/prompts/tagging.txt`**，服务启动后修改该文件**无需重启**（按修改时间自动重新加载），下次上传立即生效。也可以用 `.env` 的 `AI_PROMPT_FILE` 指向其他文件。

提示词里用 `{{existing_tags}}` 占位符注入系统已有标签，让模型优先复用、保持标签库一致。

### 识别策略与调优建议

识别采用「**两阶段**」策略：AI 先完整理解图片 → 从已有标签中**尽量选全匹配项**（matched）→ 仅在确实无法覆盖时补充**少量新标签**（new，最多 3 个）。后端会过滤模型编造的 matched（必须是标签库真实存在的词），并按标签使用频率排序辅助选择。

- **R-18 识别过严/过松**：调整提示词中 R-18 规则的措辞（“必须额外加上 R-18”改为“大概率是成人内容时才加”）。
- **新增标签还是太多**：把“最多 3 个”改成“最多 1 个”，或加强输出要求里 new 的说明。
- **匹配不够全**：把“尽量选全”改为“必须选全，不要遗漏任何匹配项”。
- **标签太泛**：加强输出要求，把“具体、可检索”的例子换成你期望的标签风格。
- **想要英文标签**：把“中文为主”改为“英文为主”，并把输出要求中的示例换成英文。
- **7B 以下的小模型乱输出**：保留输出格式示例行，并确保“只输出一个 JSON 对象”在提示词中出现两次。

未配置 AI 时上传流程照常可用，只是不自动生成标签，可手动添加。
