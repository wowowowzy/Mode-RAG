<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, type TaskView } from '../api'

const props = defineProps<{ domain: string }>()
const router = useRouter()

const tasks = ref<TaskView[]>([])
const selected = ref<TaskView | null>(null)
const loading = ref(true)
const error = ref('')
const running = ref(false)
const askConfirm = ref(false)
const elapsed = ref(0)

// 一次双臂跑 40–130 秒。用户很可能中途切到「测试记录」去看进度，
// 这时组件已卸载，完成后的 router.push 会把人家从正在看的页面拽走。
let alive = true
let timer: number | undefined

onUnmounted(() => {
  alive = false
  if (timer !== undefined) clearInterval(timer)
})

const domainName = computed(() => {
  if (props.domain === 'planning') return '约束规划'
  if (props.domain === 'debate') return '立场对比'
  return '跨域通用'
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    tasks.value = await api.tasks(props.domain)
    selected.value = tasks.value[0] ?? null
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.domain, load)

async function startTest() {
  if (!selected.value || running.value) return
  askConfirm.value = false
  running.value = true
  error.value = ''
  elapsed.value = 0
  const startedAt = Date.now()
  timer = window.setInterval(() => {
    elapsed.value = (Date.now() - startedAt) / 1000
  }, 200)
  try {
    const run = await api.run(selected.value.taskId)
    // 已经离开本页就不要抢跳转，用户此刻可能正盯着记录页
    if (alive) router.push(`/runs/${run.id}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '测试失败'
  } finally {
    running.value = false
    if (timer !== undefined) {
      clearInterval(timer)
      timer = undefined
    }
  }
}
</script>

<template>
  <header class="mb-6">
    <h1 class="font-serif text-2xl font-semibold text-white/95 md:text-3xl">{{ domainName }}</h1>
    <p class="mt-2 text-sm text-white/70">共 {{ tasks.length }} 条任务，选择一条查看输入详情。</p>
  </header>

  <p v-if="error" class="mb-4 rounded-lg border border-white/10 bg-[#3a3a3c] px-4 py-3 text-sm text-white/80">
    {{ error }}
  </p>

  <div v-if="loading" class="text-sm text-white/40">加载中…</div>

  <div v-else-if="tasks.length === 0" class="rounded-xl border border-white/8 bg-[#2c2c2e] p-6 text-sm text-white/60">
    该域暂无任务。通用域只建卡、不参与评测，任务集集中在 planning 与 debate。
  </div>

  <div v-else class="grid grid-cols-1 gap-4 lg:grid-cols-[280px_1fr]">
    <div class="max-h-[70vh] overflow-y-auto rounded-xl border border-white/8 bg-[#2c2c2e]">
      <button
        v-for="task in tasks"
        :key="task.taskId"
        type="button"
        class="block w-full border-b border-white/8 px-4 py-3 text-left transition-colors duration-200 ease-out last:border-b-0"
        :class="selected?.taskId === task.taskId ? 'bg-white/10' : 'hover:bg-white/5'"
        @click="selected = task"
      >
        <div class="flex items-center justify-between gap-2">
          <span class="font-mono text-xs text-white/80">{{ task.taskId }}</span>
          <span class="font-mono text-[11px] text-white/40">{{ task.split }}</span>
        </div>
        <div class="mt-1 text-xs text-white/40">{{ task.family }}</div>
      </button>
    </div>

    <section v-if="selected" class="rounded-xl border border-white/8 bg-[#2c2c2e] p-4 md:p-6">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 class="font-serif text-lg font-semibold text-white/95">{{ selected.taskId }}</h2>
          <p class="mt-1 font-mono text-xs text-white/40">
            {{ selected.domain }} · {{ selected.family }} · {{ selected.split }}
          </p>
        </div>
        <button
          type="button"
          class="rounded-lg bg-[#3a3a3c] px-4 py-2 text-sm text-white/90 transition-colors duration-200 ease-out hover:bg-white/10 active:opacity-80 disabled:opacity-60"
          :disabled="running"
          @click="askConfirm = true"
        >
          {{ running ? `测试中… ${elapsed.toFixed(0)}s` : '用这个任务测试' }}
        </button>
      </div>

      <p v-if="running" class="mt-3 rounded-lg border border-white/8 bg-[#1c1c1e] px-3 py-2 text-xs leading-relaxed text-white/50">
        正在跑两臂，单条约 40–130 秒。可以切到「测试记录」看实时进度，那条任务会显示在顶部，不会因为离开本页而中断。
      </p>

      <div class="mt-5">
        <h3 class="font-serif text-sm font-semibold text-white/95">任务描述</h3>
        <p class="mt-2 whitespace-pre-wrap text-sm leading-relaxed text-white/70">{{ selected.prompt }}</p>
      </div>

      <div v-if="selected.constraints.length" class="mt-5">
        <h3 class="font-serif text-sm font-semibold text-white/95">约束</h3>
        <ul class="mt-2 space-y-2">
          <li
            v-for="constraint in selected.constraints"
            :key="constraint.cid"
            class="rounded-lg border border-white/8 bg-[#1c1c1e] px-3 py-2 text-xs text-white/70"
          >
            <span class="font-mono text-white/40">{{ constraint.cid }} · {{ constraint.type }}</span>
            <span class="ml-2">{{ constraint.text }}</span>
          </li>
        </ul>
      </div>

      <div v-if="selected.stances.length" class="mt-5">
        <h3 class="font-serif text-sm font-semibold text-white/95">双方立场</h3>
        <div class="mt-2 grid grid-cols-1 gap-2 md:grid-cols-2">
          <div
            v-for="stance in selected.stances"
            :key="stance.label"
            class="rounded-lg border border-white/8 bg-[#1c1c1e] px-3 py-2 text-xs text-white/70"
          >
            <span class="font-mono text-white/40">{{ stance.label }}</span>
            <span class="ml-2">{{ stance.position }}</span>
          </div>
        </div>
      </div>

      <div class="mt-5 border-t border-white/8 pt-3 text-xs text-white/40">
        测试时只把任务描述发给服务与模型；约束与可接受卡片仅用于事后判定，不会外传。
      </div>
    </section>
  </div>

  <!-- 确认弹层 -->
  <div
    v-if="askConfirm"
    class="fixed inset-0 z-20 flex items-center justify-center bg-black/60 px-4"
    @click.self="askConfirm = false"
  >
    <div class="w-full max-w-md rounded-xl border border-white/10 bg-[#2c2c2e] p-6">
      <h2 class="font-serif text-lg font-semibold text-white/95">开始测试？</h2>
      <p class="mt-2 text-sm leading-relaxed text-white/70">
        将对任务 <span class="font-mono text-white/90">{{ selected?.taskId }}</span>
        依次执行两臂：直接调用执行模型，以及先取推理骨架再调用。会产生两次模型调用。
      </p>
      <div class="mt-6 flex justify-end gap-3">
        <button
          type="button"
          class="rounded-lg border border-white/12 px-4 py-2 text-sm text-white/70 transition-colors duration-200 ease-out hover:bg-white/5"
          @click="askConfirm = false"
        >
          取消
        </button>
        <button
          type="button"
          class="rounded-lg bg-[#3a3a3c] px-4 py-2 text-sm text-white/90 transition-colors duration-200 ease-out hover:bg-white/10 active:opacity-80"
          @click="startTest"
        >
          开始测试
        </button>
      </div>
    </div>
  </div>
</template>
