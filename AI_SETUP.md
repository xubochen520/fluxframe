# AI 图片标签配置

默认按本地 Ollama 配置，不需要 API Key，也不会把图片发送到外网。请先安装 Ollama，并下载一个支持视觉输入的模型，例如 `qwen2.5vl:7b`，然后在设置页填写：

- AI 开关：开启
- AI 接口地址：`http://127.0.0.1:11434`
- 模型名称：填写本机实际下载的视觉模型名称
- API 密钥：留空

程序会调用 Ollama 原生 `/api/chat` 接口，并以 base64 图片内容进行识别。

上传弹窗会先把图片暂存到 `storage/temp`，调用已配置的 OpenAI 兼容视觉模型生成建议标签。用户确认、删除或补充标签后，点击“完成上传”才会正式写入原图、缩略图和数据库。

在项目根目录 `.env` 中配置：

```env
AI_ENABLED=true
AI_API_KEY=你的模型服务密钥
AI_BASE_URL=https://api.openai.com/v1
AI_MODEL=gpt-4o-mini
```

也可以使用支持视觉输入的本地 OpenAI 兼容服务，例如把 `AI_BASE_URL` 改为本地服务地址。修改后重启 Node API。

未配置 AI 时，上传流程仍然可用，只是不自动生成标签；用户可以在确认弹窗中手动添加标签。
