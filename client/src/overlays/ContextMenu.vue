<script setup lang="ts">
import { Check, Download, Eye, Pencil, Star, Tags, Trash2 } from 'lucide-vue-next'
import type { ImageItem } from '../types'

const props = defineProps<{
  image: ImageItem
  /** 视口坐标；组件内部不再做边界计算（App.vue 打开时已经夹好） */
  x: number
  y: number
  trash: boolean
  video: boolean
}>()
const emit = defineEmits<{
  close: []
  open: [image: ImageItem]
  download: [image: ImageItem]
  rename: [image: ImageItem]
  addTag: [image: ImageItem]
  removeTag: [image: ImageItem]
  remove: [image: ImageItem]
  restore: [image: ImageItem]
  star: [image: ImageItem]
}>()
</script>

<template>
  <div class="context-menu menu" @click.stop :style="{ left: `${props.x}px`, top: `${props.y}px` }">
    <div class="context-title">{{ props.image.name }}</div>
    <button @click="emit('open', props.image); emit('close')"><Eye :size="16" />{{ props.video ? '查看视频' : '查看图片' }}</button>
    <button @click="emit('download', props.image); emit('close')"><Download :size="16" />{{ props.video ? '下载视频' : '下载图片' }}</button>
    <div class="context-sep"></div>
    <button v-if="!props.trash" @click="emit('rename', props.image)"><Pencil :size="16" />修改名称</button>
    <button v-if="!props.trash" @click="emit('star', props.image); emit('close')"><Star :size="16" :fill="props.image.starred ? 'currentColor' : 'none'" />{{ props.image.starred ? '取消收藏' : '收藏图片' }}</button>
    <button @click="emit('addTag', props.image)"><Tags :size="16" />添加标签（可多选）</button>
    <button v-if="props.image.tags.length" @click="emit('removeTag', props.image)"><Trash2 :size="16" />移除标签</button>
    <div class="context-sep"></div>
    <button v-if="!props.trash" class="danger" @click="emit('remove', props.image); emit('close')"><Trash2 :size="16" />移入回收站</button>
    <template v-else>
      <button class="danger" @click="emit('remove', props.image); emit('close')"><Trash2 :size="16" />永久删除</button>
      <button @click="emit('restore', props.image); emit('close')"><Check :size="16" />恢复图片</button>
    </template>
  </div>
</template>

<style scoped>
.menu { width: 236px; }
</style>
