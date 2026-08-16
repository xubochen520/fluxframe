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
    <div class="auth-card">
      <div class="auth-kicker"><Sparkles :size="14" /> PRIVATE MEDIA SPACE</div>
      <h1>{{ mode === 'login' ? '欢迎回来' : '创建你的空间' }}</h1>
      <p>{{ mode === 'login' ? '登录后管理你的视觉资产。' : '注册后即可开始上传和整理图片。' }}</p>
      <form @submit.prevent="submit">
        <label>用户名<div class="auth-input"><UserRound :size="16" /><input v-model="username" autocomplete="username" placeholder="输入用户名" /></div></label>
        <label>密码<div class="auth-input"><LockKeyhole :size="16" /><input v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="至少 8 个字符" /><button type="button" @click="showPassword = !showPassword"> <EyeOff v-if="showPassword" :size="16" /><Eye v-else :size="16" /></button></div></label>
        <label v-if="mode === 'register'">确认密码<div class="auth-input"><LockKeyhole :size="16" /><input v-model="confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入密码" /></div></label>
        <div v-if="error || localError" class="auth-error">{{ error || localError }}</div>
        <button class="auth-submit" type="submit">{{ mode === 'login' ? '登录系统' : '注册账户' }}<ArrowRight :size="17" /></button>
      </form>
      <button class="auth-switch" @click="mode = mode === 'login' ? 'register' : 'login'">{{ mode === 'login' ? '还没有账户？注册一个' : '已有账户？返回登录' }}</button>
    </div>
    <div class="auth-footer"><span>PRIVATE BY DEFAULT</span><span>·</span><span>LAN READY</span></div>
  </div>
</template>
