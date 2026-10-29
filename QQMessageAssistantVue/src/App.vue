<script setup lang="ts">
import { onMounted } from 'vue'
import AppShell from '@/components/AppShell.vue'
import ToastHost from '@/components/ToastHost.vue'
import { loadOwner, useOwner } from '@/composables/useOwner'

const owner = useOwner()

onMounted(() => {
  if (!owner.loaded) void loadOwner()
})
</script>

<template>
  <AppShell />
  <ToastHost />
</template>
