/**
 * 按压波纹（跟手）：从手指/鼠标落下的那一点扩散出去，而不是从元素中心。
 *
 * 为什么用「一个全局委托」而不是给每个按钮挂指令：
 * 全站的可点元素都落在少数几个类名上（`.btn` / `.row` / `.stat` / `.seg-item` / `.tab`），
 * 委托一次就能覆盖，不用去 14 个页面里逐个加 `v-ripple`；以后新增按钮只要用了 `.btn`
 * 就自动带上。代价是这层「魔法」隐式——所以选择器集中写在下面一处，改的时候只看这里。
 *
 * 三个约束：
 * 1. 只认主键（触摸和鼠标左键），右键菜单不弹波纹；
 * 2. 禁用的控件不弹（`disabled` / `aria-disabled="true"`）；
 * 3. 尊重 `prefers-reduced-motion`，开了减少动效就完全不注册监听。
 */

/**
 * 会被自动加上波纹的元素。要新增就改这张表，别在别处再写选择器。
 *
 * `max` 是直径上限（px）。默认按长边算，宽扁的控件因此能拿到很大的圆——
 * 对卡片和整行是对的（整行铺满也不违和），但对**底部导航**不行：
 * 标签本身没有可见边框，直径一大就盖满整个格子、看着像泼到了邻居头上。
 * 这类紧凑控件必须封顶，让波纹只留在图标和文字底下那一小片。
 */
const HOSTS: Array<{ selector: string; max?: number }> = [
  { selector: '.btn' },
  { selector: '.icon-btn' },
  { selector: '.row' },
  { selector: '.stat' },
  { selector: '.seg-item', max: 72 },
  { selector: '.tab', max: 44 },
]

const RIPPLE_SELECTOR = HOSTS.map((host) => host.selector).join(',')

/** 波纹扩散到的倍数：直径取元素长边的 1.6 倍，盖住按压点周围就够了，整行铺满会像盖了块灰布 */
const SPREAD = 1.6

const DURATION = 560

/** 波纹起手的不透明度；再高就盖住字了 */
const ALPHA = 0.12

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false
}

function isDisabled(el: Element): boolean {
  if (el.closest('[disabled], [aria-disabled="true"]')) return true
  return el.getAttribute('aria-disabled') === 'true'
}

function spawn(host: HTMLElement, clientX: number, clientY: number, max?: number) {
  // 首次按压时才加类：不让每个按钮在初始渲染时就多一个 overflow:hidden 的层
  if (!host.classList.contains('ripple-host')) host.classList.add('ripple-host')

  const rect = host.getBoundingClientRect()
  const size = Math.min(Math.max(rect.width, rect.height) * SPREAD, max ?? Number.POSITIVE_INFINITY)
  if (size <= 0) return

  const ink = document.createElement('span')
  ink.className = 'ripple-ink'
  ink.style.width = `${size}px`
  ink.style.height = `${size}px`
  ink.style.left = `${clientX - rect.left - size / 2}px`
  ink.style.top = `${clientY - rect.top - size / 2}px`

  host.appendChild(ink)

  const cleanup = () => ink.remove()
  // 动画被取消（元素提前被移除/页面切走）时 finished 会 reject，这里一律按结束处理
  /*
   * 关键帧不能只写「0.26 → 0」：配合 ease-out 会在一百毫秒内就淡到看不见，
   * 手感上等于「什么都没发生」。先快扩、中途保住浓度、最后才收掉，
   * 这样按下去那一下才有「墨滴落在纸上」的实感。
   */
  const animation = ink.animate(
    [
      { transform: 'scale(0.25)', opacity: ALPHA, offset: 0 },
      { transform: 'scale(0.72)', opacity: ALPHA * 0.82, offset: 0.62 },
      { transform: 'scale(1)', opacity: 0, offset: 1 },
    ],
    { duration: DURATION, easing: 'cubic-bezier(0.4, 0, 0.2, 1)' },
  )
  animation.finished.then(cleanup).catch(cleanup)
  // 兜底：万一 finished 永远不落（后台标签页暂停动画），也不能留一堆节点
  window.setTimeout(cleanup, DURATION + 400)
}

/** 在应用启动时调一次即可；重复调用是安全的（内部会去重） */
export function installPressRipple() {
  if (prefersReducedMotion()) return

  document.addEventListener(
    'pointerdown',
    (event) => {
      if (event.button !== 0) return
      const target = event.target
      if (!(target instanceof Element)) return

      const host = target.closest<HTMLElement>(RIPPLE_SELECTOR)
      if (!host || isDisabled(host)) return

      const rule = HOSTS.find((item) => host.matches(item.selector))
      spawn(host, event.clientX, event.clientY, rule?.max)
    },
    // 被动监听：这里只做视觉反馈，绝不能拖慢滚动和点击
    { passive: true },
  )
}
