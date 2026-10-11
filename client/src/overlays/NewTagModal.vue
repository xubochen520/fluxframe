<script setup lang="ts">
import { ref } from 'vue'
import { Tags, Users, X } from 'lucide-vue-next'

const props = defineProps<{ person: boolean; r18Mode: boolean }>()
const emit = defineEmits<{ close: []; create: [payload: { name: string; person: boolean; r18: boolean }] }>()

const name = ref('')
const person = ref(props.person)
const r18 = ref(false)

function submit() {
  const value = name.value.trim()
  if (!value) return
  emit('create', { name: value, person: person.value, r18: r18.value })
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal new-tag-modal" @click.stop>
      <button class="modal-close" @click="emit('close')"><X :size="18" /></button>
      <div class="upload-icon" :class="{ 'person-icon': person }"><component :is="person ? Users : Tags" :size="24" /></div>
      <h2>{{ person ? '新建人物' : '新建标签' }}</h2>
      <p>{{ person ? '把人名放进人物组：绿色圆点，点击即可查看 TA 的全部标签。' : '创建一个新的视觉索引。' }}</p>
      <input v-model="name" class="modal-input" :placeholder="person ? '例如：张三、李四' : '例如：灵感、场景、项目'" @keyup.enter="submit" />

      <label class="option-row">
        <input v-model="person" type="checkbox" />
        <span><strong>放入人物组（人名，绿色圆点）</strong><small>在标签页单独成组，点击查看 TA 的全部标签</small></span>
      </label>
      <label v-if="props.r18Mode" class="option-row danger">
        <input v-model="r18" type="checkbox" />
        <span><strong>标记为 R18 链接标签</strong><small>仅 R18 模式开启时可见、可选择</small></span>
      </label>

      <button class="primary-button full" :disabled="!name.trim()" @click="submit">{{ person ? '创建人物' : '创建标签' }}</button>
    </div>
  </div>
</template>

<style scoped>
.new-tag-modal { width: min(452px, 100%); }
.option-row { display: flex; align-items: flex-start; gap: 11px; margin: 0 0 10px; padding: 11px 13px; border-radius: var(--r-md); border: 1px solid var(--stroke); background: var(--field); cursor: pointer; transition: border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.option-row:hover { border-color: var(--stroke-strong); background: var(--field-hover); }
.option-row input { margin-top: 2px; accent-color: var(--accent-1); }
.option-row span { min-width: 0; }
.option-row strong { display: block; font-size: 12.5px; font-weight: 600; }
.option-row small { display: block; margin-top: 3px; color: var(--text-3); font-size: 11.5px; line-height: 1.55; }
.option-row.danger strong { color: var(--danger); }
.new-tag-modal .primary-button { margin-top: 8px; }
</style>
