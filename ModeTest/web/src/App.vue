<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'

const route = useRoute()

const nav = [
  { key: 'planning', label: '约束规划', path: '/d/planning' },
  { key: 'debate', label: '立场对比', path: '/d/debate' },
  { key: 'general', label: '跨域通用', path: '/d/general' }
]

function isActive(path: string) {
  return route.path === path || route.path.startsWith(path + '/')
}
</script>

<template>
  <div class="flex h-screen w-screen overflow-hidden bg-[#1c1c1e] text-white/90">
    <aside
      class="hidden w-60 shrink-0 flex-col border-r border-white/8 bg-[#1c1c1e]/80 px-4 py-6 backdrop-blur-xl md:flex"
    >
      <RouterLink to="/" class="mb-1 px-2 font-serif text-lg font-semibold text-white/95">
        ModeTest
      </RouterLink>
      <p class="mb-8 px-2 text-xs text-white/40">推理骨架对照测试</p>

      <nav class="flex flex-col gap-1">
        <RouterLink
          v-for="item in nav"
          :key="item.key"
          :to="item.path"
          class="rounded-lg px-3 py-2 text-sm transition-colors duration-200 ease-out"
          :class="isActive(item.path) ? 'bg-white/10 text-white/95' : 'text-white/70 hover:bg-white/5'"
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="mt-8 border-t border-white/8 pt-4">
        <RouterLink
          to="/records"
          class="block rounded-lg px-3 py-2 text-sm transition-colors duration-200 ease-out"
          :class="isActive('/records') ? 'bg-white/10 text-white/95' : 'text-white/70 hover:bg-white/5'"
        >
          测试记录
        </RouterLink>
      </div>

      <div class="mt-auto px-3 text-xs leading-relaxed text-white/40">
        两臂对照：直接调用 vs 先取骨架。除 system 段外，模型与参数完全一致。
      </div>
    </aside>

    <main class="flex-1 overflow-y-auto bg-[#2c2c2e]">
      <div class="mx-auto max-w-6xl px-4 py-8 md:px-8 md:py-10">
        <RouterView />
      </div>
    </main>
  </div>
</template>
