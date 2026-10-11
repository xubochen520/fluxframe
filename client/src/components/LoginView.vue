<script setup lang="ts">
import { ref } from 'vue'
import { ArrowRight, Eye, EyeOff, LockKeyhole, Sparkles, UserRound } from 'lucide-vue-next'
import FluidCanvas from './FluidCanvas.vue'

defineProps<{ loading?: boolean; error?: string }>()
const emit = defineEmits<{ login: [username: string, password: string]; register: [username: string, password: string] }>()
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const showPassword = ref(false)
const localError = ref('')

function submit() {
  localError.value = ''
  if (username.value.trim().length < 3) { localError.value = '用户名至少需要 3 个字符'; return }
  if (password.value.length < 8) { localError.value = '密码至少需要 8 个字符'; return }
  if (mode.value === 'register' && password.value !== confirmPassword.value) { localError.value = '两次输入的密码不一致'; return }
  if (mode.value === 'login') emit('login', username.value.trim(), password.value)
  else emit('register', username.value.trim(), password.value)
}
</script>

<template>
  <div class="auth-screen">
    <FluidCanvas />
    <div class="auth-brand"><div class="brand-mark"><span></span><span></span><span></span></div><strong>fluxframe</strong><small>INTRANET IMAGE LIBRARY</small></div>
    <div class="auth-card panel">
      <div class="auth-kicker"><Sparkles :size="14" /> PRIVATE MEDIA SPACE</div>
      <h1>{{ mode === 'login' ? '欢迎回来' : '创建你的空间' }}</h1>
      <p>{{ mode === 'login' ? '登录后管理你的视觉资产。' : '注册后即可开始上传和整理图片。' }}</p>
      <form @submit.prevent="submit">
        <label>用户名<div class="auth-input"><UserRound :size="16" /><input v-model="username" autocomplete="username" placeholder="输入用户名" /></div></label>
        <label>密码<div class="auth-input"><LockKeyhole :size="16" /><input v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="至少 8 个字符" /><button type="button" class="auth-eye" @click="showPassword = !showPassword"> <EyeOff v-if="showPassword" :size="16" /><Eye v-else :size="16" /></button></div></label>
        <label v-if="mode === 'register'">确认密码<div class="auth-input"><LockKeyhole :size="16" /><input v-model="confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入密码" /></div></label>
        <div v-if="error || localError" class="auth-error">{{ error || localError }}</div>
        <button class="primary-button full auth-submit" type="submit">{{ mode === 'login' ? '登录系统' : '注册账户' }}<ArrowRight :size="17" /></button>
      </form>
      <button class="auth-switch" @click="mode = mode === 'login' ? 'register' : 'login'">{{ mode === 'login' ? '还没有账户？注册一个' : '已有账户？返回登录' }}</button>
    </div>
    <div class="auth-footer"><span>PRIVATE BY DEFAULT</span><span>·</span><span>LAN READY</span></div>
  </div>
</template>

<style scoped>
/* ============================================================
   登录 / 注册 · 极光玻璃
   全屏极光（FluidCanvas）+ 居中玻璃卡 + 品牌大字
   ============================================================ */
.auth-screen { position: relative; display: flex; align-items: center; justify-content: center; min-height: 100vh; min-height: 100dvh; padding: 96px 17px 76px; overflow: hidden; isolation: isolate; background: radial-gradient(120% 80% at 50% -10%, color-mix(in srgb, var(--accent-1) 26%, transparent), transparent 62%), radial-gradient(90% 70% at 8% 108%, color-mix(in srgb, var(--accent-2) 16%, transparent), transparent 64%), linear-gradient(180deg, var(--bg-1), var(--bg-0) 70%); }
/* FluidCanvas 是子组件，用 :deep 给它铺满并压暗，避免极光盖过表单 */
.auth-screen :deep(.fluid-canvas) { position: fixed; z-index: -1; inset: 0; width: 100%; height: 100%; opacity: .62; pointer-events: none; -webkit-mask-image: radial-gradient(120% 90% at 50% 40%, #000 30%, transparent 78%); mask-image: radial-gradient(120% 90% at 50% 40%, #000 30%, transparent 78%); }
.auth-screen::after { content: ''; position: fixed; z-index: -1; inset: 0; pointer-events: none; background: radial-gradient(130% 90% at 50% 0%, transparent 52%, color-mix(in srgb, var(--bg-0) 72%, transparent)); }

.auth-brand { position: absolute; top: 30px; left: 38px; z-index: 1; display: flex; align-items: center; gap: 12px; }
.auth-brand .brand-mark { position: relative; display: flex; align-items: center; justify-content: center; gap: 2.5px; flex: none; width: 36px; height: 36px; border-radius: 12px; background: var(--aurora-grad); box-shadow: 0 12px 30px -12px var(--glow), inset 0 1px 0 rgba(255, 255, 255, .4); transform: rotate(-8deg); }
.auth-brand .brand-mark span { width: 3.5px; height: 14px; border-radius: 3px; background: rgba(5, 6, 13, .82); }
.auth-brand .brand-mark span:nth-child(2) { height: 20px; }
.auth-brand strong { font: 700 20px var(--font-display); letter-spacing: -.045em; color: var(--text-1); }
.auth-brand small { position: absolute; top: 27px; left: 48px; color: var(--text-3); font: 500 9px var(--font-mono); letter-spacing: .14em; white-space: nowrap; }

.auth-card { z-index: 1; width: min(418px, 100%); padding: 34px; overflow: hidden; -webkit-backdrop-filter: blur(28px) saturate(1.6); backdrop-filter: blur(28px) saturate(1.6); animation: modal-in var(--t-slow) var(--ease) backwards; }
.auth-kicker { display: flex; align-items: center; gap: 7px; color: var(--accent-ink); font: 500 10px var(--font-mono); letter-spacing: .16em; }
.auth-card h1 { margin: 15px 0 7px; color: var(--text-1); font: 600 30px/1.1 var(--font-display); letter-spacing: -.045em; }
.auth-card > p { margin: 0 0 26px; color: var(--text-2); font-size: 12.5px; line-height: 1.65; }
.auth-card form { display: flex; flex-direction: column; gap: 16px; }
.auth-card label { color: var(--text-3); font: 500 10.5px var(--font-mono); letter-spacing: .1em; text-transform: uppercase; }
.auth-input { display: flex; align-items: center; gap: 10px; height: 46px; margin-top: 9px; padding: 0 13px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); color: var(--text-3); transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), box-shadow var(--t-mid) var(--ease); }
.auth-input:focus-within { border-color: var(--accent-line); background: var(--field-hover); box-shadow: 0 0 0 4px var(--accent-soft); }
.auth-input > svg { flex: none; }
.auth-input input { width: 100%; min-width: 0; height: 100%; border: 0; outline: 0; background: transparent; color: var(--text-1); font: 400 13px var(--font-sans); letter-spacing: normal; text-transform: none; }
.auth-input input::placeholder { color: var(--text-3); }
.auth-eye { display: inline-flex; flex: none; padding: 0; color: var(--text-3); transition: color var(--t-fast) var(--ease); }
.auth-eye:hover { color: var(--text-1); }
.auth-submit { height: 46px; margin-top: 4px; font-size: 13.5px; }
.auth-switch { width: 100%; margin-top: 22px; padding: 6px; color: var(--accent-ink); font-size: 12px; font-weight: 500; transition: opacity var(--t-fast) var(--ease); }
.auth-switch:hover { opacity: .78; }
.auth-footer { position: absolute; bottom: 26px; left: 50%; display: flex; gap: 11px; transform: translateX(-50%); color: var(--text-3); font: 500 9.5px var(--font-mono); letter-spacing: .15em; white-space: nowrap; }

@media (max-width: 720px) {
  .auth-screen { padding: 104px 17px 78px; }
  .auth-screen :deep(.fluid-canvas) { opacity: .45; }
  .auth-card, .auth-input, .auth-screen :deep(.fluid-canvas) { -webkit-backdrop-filter: none; backdrop-filter: none; }
  .auth-card { padding: 26px 22px; background: var(--surface-strong); }
  .auth-brand { top: 24px; left: 22px; }
  .auth-brand small { left: 48px; }
  .auth-card h1 { font-size: 26px; }
}
</style>
