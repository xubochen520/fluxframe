<script setup lang="ts">
import { Check } from 'lucide-vue-next'

defineProps<{ message: string }>()
</script>

<template>
  <transition name="toast">
    <div v-if="message" class="toast-message" role="status"><i><Check :size="15" /></i>{{ message }}</div>
  </transition>
</template>
