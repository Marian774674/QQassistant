<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import ToastHost from '@/components/ToastHost.vue'
import { loadOwner, useOwner } from '@/composables/useOwner'

const owner = useOwner()
const route = useRoute()
const isLogin = computed(() => route.name === 'login')

watch(
  isLogin,
  (login) => {
    if (!login && !owner.loaded) void loadOwner()
  },
  { immediate: true },
)
</script>

<template>
  <AppShell v-if="!isLogin" />
  <RouterView v-else />
  <ToastHost />
</template>
