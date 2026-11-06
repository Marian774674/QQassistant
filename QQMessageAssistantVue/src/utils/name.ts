/**
 * 会话称呼的展示收口。
 *
 * 同一个会话在库里可能有好几个名字：用户起的别名串「三班群,班群」、群名自带的后缀
 * 「双创部学科竞赛交流群，交流群」、插件重复写入的历史值。界面上只认第一个，
 * 全名留给 title / 横向滚动——否则一张卡片里同一个群名会连着出现两三遍，把正文挤变形。
 *
 * 这里只做「展示层」收口，不动后端数据：逗号串本身是给别名匹配用的，不能截掉。
 */

const NAME_SPLIT = /[，,、;；]/

/** 取逗号分隔串的第一段；null / 空串都安全 */
export function firstSegment(raw: string | null | undefined): string {
  return (raw ?? '').split(NAME_SPLIT)[0]?.trim() ?? ''
}

/**
 * 出处串形如「群名 · 发言人 · 10-28 21:03」，逐段只留第一个名字。
 * QQ 号、时间这类不含逗号的段原样保留；不能对整串切第一段，否则出处信息全没了。
 */
export function shortSource(raw: string | null | undefined): string {
  return (raw ?? '')
    .split('·')
    .map(firstSegment)
    .filter(Boolean)
    .join(' · ')
}

/** 群聊里「群名 · 发言人」才看得清是谁；两边是同一个名字就只说一遍 */
export function joinNames(alias: string, sender: string): string {
  const a = alias.trim()
  const s = sender.trim()
  if (a && s && a !== s) return `${a} · ${s}`
  return a || s
}

/** 展示串和原始串不同时才挂 title，免得处处弹一个一模一样的提示 */
export function fullNameHint(shown: string, full: string | null | undefined): string {
  const f = (full ?? '').trim()
  return f && f !== shown ? f : ''
}
