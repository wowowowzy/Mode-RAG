<script setup lang="ts">
import { computed } from 'vue'

/**
 * 极简 Markdown 渲染：只处理模型输出里常见的标题、表格、列表、代码块与段落。
 * 全程不用 v-html，模型输出按纯文本处理，避免注入。
 */
const props = defineProps<{ text: string }>()

interface Span {
  text: string
  bold?: boolean
  code?: boolean
}

type Block =
  | { kind: 'heading'; level: number; spans: Span[] }
  | { kind: 'paragraph'; spans: Span[] }
  | { kind: 'list'; ordered: boolean; items: Span[][] }
  | { kind: 'table'; header: Span[][]; rows: Span[][][] }
  | { kind: 'code'; text: string }
  | { kind: 'rule' }

/** 行内 **加粗** 与 `代码`，其余原样保留。 */
function inline(source: string): Span[] {
  const spans: Span[] = []
  const pattern = /\*\*([^*]+)\*\*|`([^`]+)`/g
  let last = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(source)) !== null) {
    if (match.index > last) {
      spans.push({ text: source.slice(last, match.index) })
    }
    if (match[1] !== undefined) {
      spans.push({ text: match[1], bold: true })
    } else {
      spans.push({ text: match[2], code: true })
    }
    last = match.index + match[0].length
  }
  if (last < source.length) {
    spans.push({ text: source.slice(last) })
  }
  return spans.length ? spans : [{ text: source }]
}

function splitRow(line: string): string[] {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  return trimmed.split('|').map((cell) => cell.trim())
}

const isTableRow = (line: string) => /^\s*\|.*\|\s*$/.test(line)
const isTableDivider = (line: string) => /^\s*\|[\s:|-]+\|\s*$/.test(line) && line.includes('-')
const isRule = (line: string) => /^\s*([-*_])\1{2,}\s*$/.test(line)

const blocks = computed<Block[]>(() => {
  const lines = (props.text ?? '').replace(/\r\n/g, '\n').split('\n')
  const result: Block[] = []
  let index = 0

  while (index < lines.length) {
    const line = lines[index]

    if (!line.trim()) {
      index++
      continue
    }

    // 代码块
    if (line.trim().startsWith('```')) {
      const body: string[] = []
      index++
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        body.push(lines[index])
        index++
      }
      index++
      result.push({ kind: 'code', text: body.join('\n') })
      continue
    }

    // 表格：表头 + 分隔行
    if (isTableRow(line) && index + 1 < lines.length && isTableDivider(lines[index + 1])) {
      const header = splitRow(line).map(inline)
      index += 2
      const rows: Span[][][] = []
      while (index < lines.length && isTableRow(lines[index])) {
        rows.push(splitRow(lines[index]).map(inline))
        index++
      }
      result.push({ kind: 'table', header, rows })
      continue
    }

    // 标题
    const heading = line.match(/^\s*(#{1,4})\s+(.*)$/)
    if (heading) {
      result.push({ kind: 'heading', level: heading[1].length, spans: inline(heading[2]) })
      index++
      continue
    }

    // 分隔线
    if (isRule(line) && !line.includes('|')) {
      result.push({ kind: 'rule' })
      index++
      continue
    }

    // 列表
    const listItem = line.match(/^\s*([-*+]|\d+\.)\s+(.*)$/)
    if (listItem) {
      const ordered = /\d+\./.test(listItem[1])
      const items: Span[][] = []
      while (index < lines.length) {
        const current = lines[index]
        const match = current.match(/^\s*([-*+]|\d+\.)\s+(.*)$/)
        if (!match) {
          // 缩进的续行并入上一项
          if (items.length && /^\s{2,}\S/.test(current)) {
            items[items.length - 1].push({ text: '\n' + current.trim() })
            index++
            continue
          }
          break
        }
        items.push(inline(match[2]))
        index++
      }
      result.push({ kind: 'list', ordered, items })
      continue
    }

    // 段落：连续普通行
    const paragraph: string[] = []
    while (index < lines.length) {
      const current = lines[index]
      if (
        !current.trim() ||
        current.trim().startsWith('```') ||
        /^\s*(#{1,4})\s+/.test(current) ||
        /^\s*([-*+]|\d+\.)\s+/.test(current) ||
        isTableRow(current) ||
        isRule(current)
      ) {
        break
      }
      paragraph.push(current.trim())
      index++
    }
    result.push({ kind: 'paragraph', spans: inline(paragraph.join('\n')) })
  }

  return result
})
</script>

<template>
  <div class="space-y-3 text-xs leading-relaxed text-white/80">
    <template v-for="(block, blockIndex) in blocks" :key="blockIndex">
      <h3
        v-if="block.kind === 'heading' && block.level <= 2"
        class="font-serif text-sm font-semibold text-white/95"
      >
        <span v-for="(span, i) in block.spans" :key="i" :class="{ 'font-semibold': span.bold }">
          <code v-if="span.code" class="rounded bg-white/8 px-1 py-0.5 font-mono text-[11px]">{{ span.text }}</code>
          <template v-else>{{ span.text }}</template>
        </span>
      </h3>
      <h4 v-else-if="block.kind === 'heading'" class="font-serif text-xs font-semibold text-white/95">
        <span v-for="(span, i) in block.spans" :key="i" :class="{ 'font-semibold': span.bold }">
          <code v-if="span.code" class="rounded bg-white/8 px-1 py-0.5 font-mono text-[11px]">{{ span.text }}</code>
          <template v-else>{{ span.text }}</template>
        </span>
      </h4>

      <p v-else-if="block.kind === 'paragraph'" class="whitespace-pre-wrap break-words">
        <span v-for="(span, i) in block.spans" :key="i" :class="{ 'font-semibold text-white/95': span.bold }">
          <code v-if="span.code" class="rounded bg-white/8 px-1 py-0.5 font-mono text-[11px]">{{ span.text }}</code>
          <template v-else>{{ span.text }}</template>
        </span>
      </p>

      <ol v-else-if="block.kind === 'list' && block.ordered" class="ml-4 list-decimal space-y-1">
        <li v-for="(item, i) in block.items" :key="i" class="whitespace-pre-wrap break-words">
          <span v-for="(span, j) in item" :key="j" :class="{ 'font-semibold text-white/95': span.bold }">
            <code v-if="span.code" class="rounded bg-white/8 px-1 py-0.5 font-mono text-[11px]">{{ span.text }}</code>
            <template v-else>{{ span.text }}</template>
          </span>
        </li>
      </ol>
      <ul v-else-if="block.kind === 'list'" class="ml-4 list-disc space-y-1">
        <li v-for="(item, i) in block.items" :key="i" class="whitespace-pre-wrap break-words">
          <span v-for="(span, j) in item" :key="j" :class="{ 'font-semibold text-white/95': span.bold }">
            <code v-if="span.code" class="rounded bg-white/8 px-1 py-0.5 font-mono text-[11px]">{{ span.text }}</code>
            <template v-else>{{ span.text }}</template>
          </span>
        </li>
      </ul>

      <div v-else-if="block.kind === 'table'" class="overflow-x-auto">
        <table class="w-full border-collapse text-xs">
          <thead>
            <tr class="text-white/40">
              <th
                v-for="(cell, i) in block.header"
                :key="i"
                class="border-b border-white/8 py-1.5 pr-3 text-left font-normal"
              >
                <span v-for="(span, j) in cell" :key="j" :class="{ 'font-semibold': span.bold }">{{ span.text }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in block.rows" :key="i" class="text-white/80">
              <td
                v-for="(cell, j) in row"
                :key="j"
                class="border-b border-white/5 py-1.5 pr-3 align-top"
              >
                <span v-for="(span, k) in cell" :key="k" :class="{ 'font-semibold text-white/95': span.bold }">
                  <code v-if="span.code" class="rounded bg-white/8 px-1 py-0.5 font-mono text-[11px]">{{ span.text }}</code>
                  <template v-else>{{ span.text }}</template>
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <pre
        v-else-if="block.kind === 'code'"
        class="overflow-x-auto rounded-lg border border-white/8 bg-[#1c1c1e] p-3 font-mono text-[11px] leading-relaxed text-white/70"
      >{{ block.text }}</pre>

      <hr v-else-if="block.kind === 'rule'" class="border-white/8" />
    </template>
  </div>
</template>
