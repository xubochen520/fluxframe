import { gsap } from 'gsap'

function cubicBezier(x1: number, y1: number, x2: number, y2: number) {
  const cx = 3 * x1
  const bx = 3 * (x2 - x1) - cx
  const ax = 1 - cx - bx
  const cy = 3 * y1
  const by = 3 * (y2 - y1) - cy
  const ay = 1 - cy - by
  const sampleX = (t: number) => ((ax * t + bx) * t + cx) * t
  const sampleY = (t: number) => ((ay * t + by) * t + cy) * t
  return (x: number) => {
    if (x <= 0) return 0
    if (x >= 1) return 1
    let lo = 0
    let hi = 1
    let t = x
    for (let i = 0; i < 16; i += 1) {
      const value = sampleX(t)
      if (Math.abs(value - x) < 1e-4) break
      if (value < x) lo = t
      else hi = t
      t = (lo + hi) / 2
    }
    return sampleY(t)
  }
}

/** 与 CSS --ease 同一条曲线：cubic-bezier(.2, .8, .2, 1) */
export const auroraEase = cubicBezier(0.2, 0.8, 0.2, 1)

export function motionReduced() {
  return document.documentElement.dataset.motion === 'reduced' || window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export function enterPage(selector = '.page-content') {
  if (motionReduced()) {
    gsap.fromTo(selector, { opacity: 0 }, { opacity: 1, duration: 0.15, ease: auroraEase })
    return
  }
  gsap.fromTo(selector, { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: 0.42, ease: auroraEase, clearProps: 'transform' })
}

const lastPointer = { x: -1, y: -1, at: 0 }

/** 最近一次按下的位置（1.5 秒内有效），查看器据此从缩略图处放大 */
export function recentPointer() {
  return performance.now() - lastPointer.at < 1500 ? { x: lastPointer.x, y: lastPointer.y } : null
}

/** 全局一个 pointermove：把指针坐标写进当前悬停卡片的 --mx/--my（只在桌面精确指针下） */
export function installMotion() {
  const fine = window.matchMedia('(hover: hover) and (pointer: fine) and (min-width: 721px)')
  let frame = 0
  let target: HTMLElement | null = null
  let px = 0
  let py = 0
  const flush = () => {
    frame = 0
    if (!target) return
    const rect = target.getBoundingClientRect()
    target.style.setProperty('--mx', `${px - rect.left}px`)
    target.style.setProperty('--my', `${py - rect.top}px`)
  }
  const onMove = (event: PointerEvent) => {
    if (!fine.matches || event.pointerType !== 'mouse' || document.documentElement.dataset.motion === 'reduced') return
    target = (event.target as Element | null)?.closest?.('.glow-card') as HTMLElement | null
    if (!target) return
    px = event.clientX
    py = event.clientY
    if (!frame) frame = requestAnimationFrame(flush)
  }
  const onDown = (event: PointerEvent) => {
    lastPointer.x = event.clientX
    lastPointer.y = event.clientY
    lastPointer.at = performance.now()
  }
  window.addEventListener('pointermove', onMove, { passive: true })
  window.addEventListener('pointerdown', onDown, { passive: true, capture: true })
  return () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerdown', onDown, { capture: true })
    if (frame) cancelAnimationFrame(frame)
  }
}
