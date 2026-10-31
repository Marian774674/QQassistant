<script setup lang="ts">
import { useSlots } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowLeft } from 'lucide-vue-next'

/**
 * 全站统一的页头（刊头）。
 *
 * 三件事按「读的速度」从上到下排：先是一枚胶囊标签（这是哪一天 / 哪一栏），
 * 再是字号 32 的标题（一眼扫到），最后才是一句说明（想看才看）。
 * 三者之间的留白比字号更能决定它高不高级，所以间距全部写成具名常量。
 *
 * 吸顶与收起由 AppShell 控制（它读 .page-head 的高度），这里只管结构与局部样式。
 */
defineProps<{
  /** 大标题，页面唯一的主角 */
  title: string
  /** 标题上方胶囊里的短标签：日期、栏目名、身份 */
  eyebrow?: string
  /** 标题下方的一句说明，收起时折叠 */
  sub?: string
  /** 有值就在左上角渲染一颗圆形返回钮 */
  back?: { to: string; label?: string }
}>()

/**
 * 有没有 #actions 插槽要告诉 CSS：带动作钮的页头在收起时得换成横排，
 * 否则动作钮会自己占掉一整行，标题被挤到下面，中间空出一条白带。
 */
const slots = useSlots()
</script>

<template>
  <header class="page-head" :class="{ 'has-actions': !!slots.actions }">
    <div v-if="back || eyebrow || $slots.actions" class="head-bar">
      <RouterLink
        v-if="back"
        :to="back.to"
        class="back"
        :aria-label="back.label || '返回'"
        :title="back.label || '返回'"
      >
        <ArrowLeft :size="15" :stroke-width="2.3" aria-hidden="true" />
      </RouterLink>

      <span v-if="eyebrow" class="eyebrow">{{ eyebrow }}</span>

      <!-- 引导线：把「标签 / 返回」和右侧动作连成一整行，印刷目录的排法 -->
      <span class="head-rule" aria-hidden="true"></span>

      <div v-if="$slots.actions" class="head-actions">
        <slot name="actions" />
      </div>
    </div>

    <h1 class="page-title">{{ title }}</h1>

    <p v-if="sub" class="page-sub">{{ sub }}</p>

    <slot />
  </header>
</template>

<style scoped>
.head-bar {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 32px;
  margin-bottom: 13px;
  transition: margin var(--dur-2) var(--ease-out);
}

/*
 * 引导线：1px 暖发丝线，从标签（或返回钮）一直拉到右侧。
 * 它把页头第一行从「几个零散的小块」变成「一行版式」——
 * 上面一行有横线，下面一行是衬线大字，这就是刊物的抬头。
 * 末端渐隐，免得和右边缘撞成一条生硬的断口。
 */
.head-rule {
  flex: 1;
  min-width: 16px;
  height: 1px;
  background: linear-gradient(
    90deg,
    var(--rule) 0%,
    var(--rule) 60%,
    rgba(220, 214, 201, 0) 100%
  );
}

/*
 * 返回是纸白圆钮而不是一行文字：它和右侧的动作钮、左边的印章标签是同一套「小块」语言，
 * 三者都不抢标题，但都在。
 * 32px 是跟着标题走的——标题 32px 时，36px 的圆钮会比标题的字面还高，看着喧宾夺主。
 */
.back {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--hairline);
  border-radius: 50%;
  background: var(--card);
  color: var(--ink);
  box-shadow: 0 1px 2px rgba(46, 38, 26, 0.04);
  flex: none;
  transition: transform var(--dur-1) var(--ease-spring),
    background-color var(--dur) var(--ease);
}

.back:active {
  transform: scale(0.92);
  background: var(--surface);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
</style>
