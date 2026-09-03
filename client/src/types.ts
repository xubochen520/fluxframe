export type View = 'overview' | 'library' | 'tags' | 'logs' | 'trash' | 'settings'
export type IconName = 'LayoutDashboard' | 'Images' | 'Tags' | 'ScrollText' | 'Trash2' | 'Settings2'

export interface ImageItem {
  id: string
  name: string
  mimeType?: string
  url: string
  thumb: string
  width: number
  height: number
  size: string
  views: number
  uploadedAt: string
  capturedAt: string
  tags: string[]
  tagIds: string[]
  color: string
  r18?: boolean
  deletedAt?: string
}

export interface TagItem {
  id: string
  name: string
  color: string
  r18?: boolean
  count: number
}

export interface AuditLog {
  id: string
  action: string
  target: string
  user: string
  ip: string
  scope: '内网' | '外网'
  time: string
  tone: 'blue' | 'violet' | 'orange' | 'red' | 'green'
}
