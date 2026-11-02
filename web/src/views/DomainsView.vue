<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type DomainView } from '../api'

const router = useRouter()
const domains = ref<DomainView[]>([])
const error = ref('')
const loading = ref(true)

onMounted(async () => {
  try {
    domains.value = await api.domains()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

function open(domain: DomainView) {
  if (domain.taskCount === 0) {
    return
  }
  router.push(`/d/${domain.key}`)
}
</script>

<template>
  <header class="mb-8">
    <h1 class="font-serif text-2xl font-semibold text-white/95 md:text-3xl">选择模式域</h1>
    <p class="mt-2 max-w-2xl text-sm leading-relaxed text-white/70">
      每个域内的任务用于对照「直接调用执行模型」与「先由 ModeRouter 注入推理骨架」两种输出。
    </p>
  </header>

  <p v-if="error" class="mb-6 rounded-lg border border-white/10 bg-[#3a3a3c] px-4 py-3 text-sm text-white/80">
    {{ error }}
  </p>

  <div v-if="loading" class="text-sm text-white/40">加载中…</div>

  <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
    <button
      v-for="domain in domains"
      :key="domain.key"
      type="button"
      class="rounded-xl border border-white/8 bg-[#2c2c2e] p-4 text-left transition-colors duration-200 ease-out md:p-6"
      :class="domain.taskCount === 0 ? 'cursor-not-allowed opacity-60' : 'hover:bg-white/5'"
      @click="open(domain)"
    >
      <div class="flex items-baseline justify-between gap-3">
        <h2 class="font-serif text-lg font-semibold text-white/95 md:text-xl">{{ domain.name }}</h2>
        <span class="font-mono text-xs text-white/40">{{ domain.key }}</span>
      </div>

      <p class="mt-2 text-sm leading-relaxed text-white/70">{{ domain.description }}</p>

      <div class="mt-4 flex items-center gap-4 border-t border-white/8 pt-3 text-xs text-white/40">
        <span>{{ domain.cardCount ?? '—' }} 张卡</span>
        <span v-if="domain.taskCount > 0">{{ domain.taskCount }} 条任务</span>
        <span v-else>暂无任务</span>
      </div>
    </button>
  </div>
</template>
