<script setup lang="ts">
import { ChevronLeft, Images, Search, Tags, X } from 'lucide-vue-next'
import type { TagItem } from '../types'

defineProps<{ tags: TagItem[] }>()
const emit = defineEmits<{ close: []; select: [name: string] }>()
</script>

<template>
  <div class="tag-browser-overlay"><div class="tag-browser-head"><button class="settings-back" @click="emit('close')"><ChevronLeft :size="17" />返回工作区</button><div class="settings-overlay-title"><Tags :size="18" /><strong>智能标签</strong></div><button class="settings-close" @click="emit('close')"><X :size="19" /></button></div><div class="tag-browser-body"><div class="settings-hero"><div><p class="eyebrow"><Tags :size="14" /> ORGANIZE SMARTER</p><h1>按标签浏览</h1><p>点击任意标签，直接查看该标签关联的全部图片。</p></div><div class="tag-browser-search"><Search :size="15" /><input placeholder="搜索标签..." /></div></div><div class="tag-browser-grid"><button v-for="tag in tags" :key="tag.id" class="tag-browser-card" @click="emit('select', tag.name)"><span class="tag-browser-color" :style="{ background: tag.color }"></span><span><strong>{{ tag.name }}</strong><small><Images :size="13" />{{ tag.count }} 张图片</small></span><ChevronLeft :size="17" /></button><div v-if="!tags.length" class="empty-state"><Tags :size="30" /><strong>还没有标签</strong></div></div></div></div>
</template>
