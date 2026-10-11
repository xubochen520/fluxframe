import { Check, Coins, Download, Eye, Images, Link2, Pencil, Play, Tags, Trash2, Upload, UserRound, Users } from 'lucide-vue-next'

export const PERSON_COLOR = '#22c55e'

export interface DashboardStats {
  imageCount: number
  tagCount: number
  userCount: number
  totalViews: number
  storage: string
  storageCapacity: string
  storagePercent: number
  databaseImageBytes: string
}

export type GraphViewMode = 'galaxy' | 'galaxy3d' | 'list'

export function isVideoItem(item: { mimeType?: string } | null | undefined) { return !!item?.mimeType && item.mimeType.startsWith('video/') }

/* 有服务端生成的封面（thumb 指向 variant 而非视频本体）时，视频卡片直接用 <img> 显示封面 */
export function hasPoster(item: { mimeType?: string; thumb?: string; url?: string }) { return !item.url || item.thumb !== item.url }

export function tagDot(tag: { person?: boolean; r18?: boolean; color: string }) { return tag.person ? PERSON_COLOR : tag.r18 ? '#ef4444' : tag.color }

export function formatTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return value
  const diff = Math.max(0, Date.now() - date.valueOf())
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return date.toLocaleDateString('zh-CN')
}

/* 瀑布流：行高 8px、行距 13px（与 .image-grid 的 grid-auto-rows / row-gap 一致） */
export function sizeImageCard(event: Event) {
  const media = event.target as HTMLElement
  if (!media || (media.tagName !== 'IMG' && media.tagName !== 'VIDEO')) return
  const card = media.closest('.image-card') as HTMLElement | null
  const grid = card?.parentElement
  if (!card || !grid) return
  card.style.gridRowEnd = 'auto'
  requestAnimationFrame(() => {
    const rowHeight = 8
    const rowGap = 13
    card.style.gridRowEnd = `span ${Math.max(1, Math.ceil((card.scrollHeight + rowGap) / (rowHeight + rowGap)))}`
  })
}

export function sizeBatchCard(event: Event) {
  const image = event.currentTarget as HTMLImageElement
  const card = image.closest('.batch-image-option') as HTMLElement | null
  const list = card?.parentElement
  if (!card || !list) return
  card.style.gridRowEnd = 'auto'
  requestAnimationFrame(() => {
    const rowHeight = 8
    const rowGap = Number.parseFloat(getComputedStyle(list).rowGap) || 8
    card.style.gridRowEnd = `span ${Math.max(1, Math.ceil((card.scrollHeight + rowGap) / (rowHeight + rowGap)))}`
  })
}

export function logActionIcon(action: string) {
  if (action === '查看图片') return Eye
  if (action === '查看视频') return Play
  if (action === '提取视频') return Link2
  if (action === '提取封面') return Images
  if (action.includes('提取')) return Link2
  if (action.includes('下载')) return Download
  if (action.includes('上传')) return Upload
  if (action.includes('名称')) return Pencil
  if (action === '恢复图片') return Check
  if (action.includes('人物')) return Users
  if (action.includes('DeepSeek')) return Coins
  if (action.includes('标签')) return Tags
  if (action.includes('删除')) return Trash2
  return UserRound
}
