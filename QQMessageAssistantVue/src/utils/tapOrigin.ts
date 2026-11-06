/**
 * 记下「上一次按下的位置」，给路由切换的圆形扩散转场当圆心。
 *
 * 为什么单独一个文件、而不是塞进 AppShell 的 pointerdown 里：
 * 底部导航的 tabbar 是 `.screen` 的**兄弟节点**，点它根本不会触发 `.screen` 上的监听，
 * 而那恰好是最常发生的一次切页。挂在 document 上才能一次收全。
 *
 * 为什么在 pointerdown 就把 CSS 变量写好、而不是等路由变化时再算：
 * 新页面的进入动画在 DOM 插入后的下一帧就开始跑了，等 watch 里再设变量已经晚了一拍，
 * 第一帧会从旧位置（或默认的中心）起跳。按下那一刻是唯一确定「从哪儿扩散」的时机。
 */

/** 圆心与半径写到根元素上，靠 CSS 变量继承进任意页面 */
function write(x: number, y: number, radius: number) {
  const root = document.documentElement.style
  root.setProperty('--tap-x', `${x}px`)
  root.setProperty('--tap-y', `${y}px`)
  root.setProperty('--tap-r', `${radius}px`)
}

export function installTapOrigin() {
  document.addEventListener(
    'pointerdown',
    (event) => {
      if (event.button !== 0) return

      // 坐标要换算到「页面所在的那个盒子」里：宽屏下手机画布是居中的，直接用视口坐标会偏
      const screen = document.getElementById('main')
      if (!screen) return
      const rect = screen.getBoundingClientRect()
      const x = event.clientX - rect.left
      const y = event.clientY - rect.top

      // 半径取到最远的那个角，圆一扩就一定能盖满整屏，不会在边角漏一块
      const radius = Math.hypot(Math.max(x, rect.width - x), Math.max(y, rect.height - y))
      write(x, y, radius)
    },
    // 只读坐标，不参与点击，绝不能拖慢滚动
    { passive: true, capture: true },
  )
}
