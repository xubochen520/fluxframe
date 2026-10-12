/**
 * 主题目录 —— 全站唯一真源。
 * tokens.css 负责把这些色值写进 CSS 变量；这里给 JS 侧用：
 * 顶栏切换器的色板、设置页的色卡、流体背景的默认三色、星系引擎的兜底预设。
 * 改主题色只改这一处（再同步 tokens.css 的同名块）。
 */
export interface ThemeDef {
  name: string
  /** 深色模式强调三色（与 tokens.css 的 --accent-1~3 一致） */
  accents: [string, string, string]
  /** 浅色模式的强调色覆盖（没有则沿用 accents） */
  lightAccent?: string
  /** 中文短描述，用在切换器 tooltip */
  label: string
}

export const THEMES: ThemeDef[] = [
  { name: 'Aurora', label: '极光', accents: ['#7c6cff', '#22d3ee', '#f472b6'], lightAccent: '#6455e8' },
  { name: 'Ember', label: '炽橙', accents: ['#f0603c', '#f5a524', '#ff8a5c'], lightAccent: '#e04a24' },
  { name: 'Mono', label: '银盐', accents: ['#d4d4d0', '#f0f0ec', '#8a8a86'], lightAccent: '#3d3d3a' },
  { name: 'Nebula', label: '星云', accents: ['#9b5cf6', '#f0509b', '#35d6e8'], lightAccent: '#7c3aed' },
  { name: 'Forest', label: '苔绿', accents: ['#3ecf6e', '#b3e054', '#2dd4bf'], lightAccent: '#16a34a' },
  { name: 'Rose', label: '玫红', accents: ['#f04e6a', '#ff8aa0', '#fda4af'], lightAccent: '#e11d48' },
  { name: 'Ocean', label: '深海', accents: ['#2e9be6', '#5ce1e6', '#60a5fa'], lightAccent: '#0b78c4' },
  { name: 'Solar', label: '日光', accents: ['#f5a623', '#fde047', '#fb923c'], lightAccent: '#d97706' },
  { name: 'Slate', label: '石板', accents: ['#64a2ff', '#93c5fd', '#818cf8'], lightAccent: '#2563eb' },
  { name: 'Matcha', label: '抹茶', accents: ['#a8c93a', '#d6e06a', '#6ee7a0'], lightAccent: '#65a30d' },
  { name: 'Sakura', label: '樱', accents: ['#f078b8', '#c084fc', '#fda4af'], lightAccent: '#db2777' },
  { name: 'Copper', label: '铜版', accents: ['#d98e4a', '#e8c07a', '#c2703d'], lightAccent: '#b45309' },
]

export const THEME_NAMES = THEMES.map((theme) => theme.name)

export function themeDef(name: string): ThemeDef {
  return THEMES.find((theme) => theme.name === name) || THEMES[0]
}

/** 某主题的流体背景三色（深浅通用，FluidCanvas 内部会按模式调亮度） */
export function themeFluidPalette(name: string): string[] {
  return [...themeDef(name).accents]
}
