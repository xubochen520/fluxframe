<script setup lang="ts">
import { Eye, ShieldCheck, Tags, Users } from 'lucide-vue-next'
import type { TagItem } from '../types'

const props = defineProps<{ tag: TagItem; x: number; y: number }>()
const emit = defineEmits<{
  close: []
  togglePerson: [tag: TagItem]
  viewPerson: [tag: TagItem]
  toggleR18: [tag: TagItem]
  batchTag: [tag: TagItem]
}>()
</script>

<template>
  <div class="context-menu menu" @click.stop :style="{ left: `${props.x}px`, top: `${props.y}px` }">
    <div class="context-title">标签：{{ props.tag.name }}</div>
    <button @click="emit('togglePerson', props.tag); emit('close')"><Users :size="16" />{{ props.tag.person ? '移出人物组' : '放入人物组（绿色人名）' }}</button>
    <button v-if="props.tag.person" @click="emit('viewPerson', props.tag); emit('close')"><Eye :size="16" />查看 TA 的全部标签</button>
    <button v-if="props.tag.name !== 'R-18'" @click="emit('toggleR18', props.tag); emit('close')"><ShieldCheck :size="16" />{{ props.tag.r18 ? '取消 R18 链接' : '标记为 R18 链接标签' }}</button>
    <div class="context-sep"></div>
    <button @click="emit('batchTag', props.tag); emit('close')"><Tags :size="16" />批量添加图片</button>
  </div>
</template>

<style scoped>
.menu { width: 246px; }
</style>
