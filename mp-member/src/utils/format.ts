/** 通用格式化工具 */

export function fmtMoney(v?: number | string | null, digits = 2): string {
  if (v === undefined || v === null || v === '') return (0).toFixed(digits)
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(digits) : (0).toFixed(digits)
}

export function fmtInt(v?: number | string | null): string {
  if (v === undefined || v === null || v === '') return '0'
  const n = Number(v)
  return Number.isFinite(n) ? String(Math.trunc(n)) : '0'
}

/** yyyy-MM-dd HH:mm:ss → MM-dd HH:mm */
export function shortTime(dt?: string | null): string {
  if (!dt) return ''
  const parts = dt.split(' ')
  if (parts.length < 2) return dt
  return parts[0].slice(5) + ' ' + parts[1].slice(0, 5)
}

/** 相对时间 */
export function fromNow(dt?: string | null): string {
  if (!dt) return ''
  const t = new Date(dt.replace(/-/g, '/')).getTime()
  if (!Number.isFinite(t)) return dt
  const diff = Date.now() - t
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  return dt.slice(0, 10)
}

/** 券面额展示 */
export function couponFaceText(type: number, faceValue: number): string {
  if (type === 2) {
    // 折扣券：faceValue 为折扣率
    const rate = Number(faceValue) > 1 ? Number(faceValue) / 100 : Number(faceValue)
    return `${(rate * 10).toFixed(1).replace(/\.0$/, '')}折`
  }
  return `¥${fmtMoney(faceValue)}`
}

/** 券类型文本 */
export function couponTypeText(type: number): string {
  const map: Record<number, string> = { 1: '满减券', 2: '折扣券', 3: '代金券', 4: '兑换券', 5: '次卡券' }
  return map[type] || '优惠券'
}
