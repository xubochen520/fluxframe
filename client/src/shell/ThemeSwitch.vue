<script setup lang="ts">
/**
 * 顶栏主题快速切换器：12 套主题 × 深浅双模式，即点即生效。
 * 不落库——改的是 document 上的 data 属性，刷新回服务端保存的设置。
 * （保存入口仍在设置页，这里负责「先看到」。）
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Moon, Palette, Sun } from 'lucide-vue-next'
import { THEMES } from '../themes'

const props = defineProps<{ selectedTheme: string; isDark: boolean }>()
const emit = defineEmits<{ pick: [name: string]; toggleMode: [] }>()

const open = ref(false)
const root = ref<HTMLElement | null>(null)

function onDocClick(event: MouseEvent) {
  if (open.value && root.value && !root.value.contains(event.target as Node)) open.value = false
}
function onEsc(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) { event.stopPropagation(); open.value = false }
}
onMounted(() => { document.addEventListener('click', onDocClick); window.addEventListener('keydown', onEsc, true) })
onBeforeUnmount(() => { document.removeEventListener('click', onDocClick); window.removeEventListener('keydown', onEsc, true) })

function pick(name: string) { emit('pick', name); open.value = false }
</script>

<template>
  <div ref="root" class="theme-switch">
    <button class="theme-trigger" :title="`主题：${props.selectedTheme}`" aria-label="切换主题" @click.stop="open = !open">
      <Palette :size="17" />
      <i class="swatch-mini"></i>
    </button>
    <transition name="sheet">
      <div v-if="open" class="theme-pop" @click.stop>
        <div class="theme-pop-title">
          <span>主题 / THEME</span>
          <div class="mode-toggle">
            <button :class="{ active: props.isDark }" title="深色" @click="!props.isDark && emit('toggleMode')"><Moon :size="13" /></button>
            <button :class="{ active: !props.isDark }" title="浅色" @click="props.isDark && emit('toggleMode')"><Sun :size="13" /></button>
          </div>
        </div>
        <div class="theme-grid">
          <button
            v-for="theme in THEMES"
            :key="theme.name"
            :class="['theme-cell', { active: props.selectedTheme === theme.name }]"
            :title="theme.label"
            @click="pick(theme.name)"
          >
            <i :style="{ background: `linear-gradient(135deg, ${theme.accents[0]}, ${theme.accents[1]})` }"></i>
            {{ theme.name }}
          </button>
        </div>
      </div>
    </transition>
  </div>
</template>
