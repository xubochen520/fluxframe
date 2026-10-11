<script setup lang="ts">
import { Pencil, X } from 'lucide-vue-next'

const props = defineProps<{ name: string; video: boolean }>()
const emit = defineEmits<{ 'update:name': [value: string]; close: []; submit: [] }>()

function selectAll(event: FocusEvent) {
  ;(event.target as HTMLInputElement | null)?.select()
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal rename-modal" @click.stop>
      <button class="modal-close" @click="emit('close')"><X :size="18" /></button>
      <div class="upload-icon"><Pencil :size="24" /></div>
      <h2>修改名称</h2>
      <p>为「{{ props.name }}」设置新的文件名称（{{ props.video ? '视频' : '图片' }}）。</p>
      <input
        class="modal-input"
        :value="props.name"
        maxlength="200"
        placeholder="输入新的文件名称"
        @input="emit('update:name', ($event.target as HTMLInputElement).value)"
        @keyup.enter="emit('submit')"
        @focus="selectAll"
      />
      <small class="rename-hint">无需输入扩展名，查看与下载时会自动补全（如 .jpg / .mp4）。</small>
      <button class="primary-button full" :disabled="!props.name.trim()" @click="emit('submit')">保存名称</button>
    </div>
  </div>
</template>

<style scoped>
.rename-modal { width: min(430px, 100%); }
.rename-hint { display: block; margin: -4px 0 16px; color: var(--text-3); font-size: 11.5px; line-height: 1.6; }
</style>
