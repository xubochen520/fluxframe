/**
 * 模型 / 索引目录。
 * 单独抽一个模块是为了让「只处理 JSON 索引」的代码（比如 layout.ts）不必连带 import
 * embed.ts——那条链会拉起 sharp 和 onnxruntime，几十 MB 的原生库，纯粹算坐标时不需要。
 *
 * Docker 部署时挂载到 /data/models（见 deploy/docker-compose.yml），避免容器重建丢文件。
 */
import path from 'node:path'

export function modelsDir() {
  return process.env.MODELS_DIR?.trim() ? path.resolve(process.env.MODELS_DIR.trim()) : path.resolve('./models')
}
