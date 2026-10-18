import type { TrackPointDTO } from '@/api/client'

export interface GpxOptions {
  name?: string
  creator?: string
}

/**
 * XML 转义：覆盖文本节点与属性值所需的 5 个字符（& < > " '）。
 * 顺序：& 必须最先替换，避免二次转义。
 */
function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

/**
 * 生成 GPX 1.1 字符串（纯前端导出，无任何新依赖）：
 * - 完整结构：<?xml?> 头 + gpx(version=1.1, creator 默认 sport-verify-web) + metadata(name 默认 record-track, time=首点 ts)
 *   + trk/trkseg + 逐点 trkpt（lat/lon 属性固定 6 位小数、<time>=new Date(ts).toISOString()，保持原始顺序）。
 * - 点位包含 ele（TASK-273 海拔增强）时在 <trkpt> 节点内输出 <ele>（GPX schema 中先于 <time>），缺失省略。
 * - 空 points 数组抛 Error（防御；页面按钮已按 points.length 门控）。
 */
export function generateGpx(points: TrackPointDTO[], opts?: GpxOptions): string {
  if (!points.length) {
    throw new Error('generateGpx: points 数组为空，无法生成 GPX')
  }
  const creator = opts?.creator || 'sport-verify-web'
  const name = opts?.name || 'record-track'

  const lines: string[] = []
  lines.push('<?xml version="1.0" encoding="UTF-8"?>')
  lines.push(
    `<gpx version="1.1" creator="${escapeXml(creator)}" xmlns="http://www.topografix.com/GPX/1/1">`,
  )
  lines.push('  <metadata>')
  lines.push(`    <name>${escapeXml(name)}</name>`)
  lines.push(`    <time>${new Date(points[0].ts).toISOString()}</time>`)
  lines.push('  </metadata>')
  lines.push('  <trk>')
  lines.push(`    <name>${escapeXml(name)}</name>`)
  lines.push('    <trkseg>')
  for (const p of points) {
    lines.push(`      <trkpt lat="${p.lat.toFixed(6)}" lon="${p.lng.toFixed(6)}">`)
    if (p.ele != null) {
      lines.push(`        <ele>${p.ele.toFixed(1)}</ele>`)
    }
    lines.push(`        <time>${new Date(p.ts).toISOString()}</time>`)
    lines.push('      </trkpt>')
  }
  lines.push('    </trkseg>')
  lines.push('  </trk>')
  lines.push('</gpx>')
  return lines.join('\n')
}

/**
 * 解析 GPX 1.1 字符串为轨迹点数组（TASK-245 B28 前端导入解析，与 {@link generateGpx} 形成双向闭环）。
 *
 * <p>纯正则解析（零新依赖），不依赖浏览器 {@code DOMParser}（vitest/Node 环境与浏览器通用）：
 * 逐 {@code <trkpt lat="..." lon="...">} 节点提取经纬度、内部 {@code <time>} 与 {@code <ele>}（TASK-273，
 * 存在时填入 {@code point.ele}）文本；每个点自动补 {@code seq}（文档序 0..N-1）、{@code ts}（epoch 毫秒，
 * 非法/缺失时间戳按首点递推或置 0）、{@code speed}（相邻点球面距离/时间差，m/s；首点不做速度计算则该字段缺省）。</p>
 *
 * <p>异常防御：输入为空/非字符串 → 格式错误；解析不到任何 trkpt → 格式错误（同时给出已解析
 * 到的行级提示以便排查）；经纬度缺失的点跳过但若最终零有效点仍报错。</p>
 *
 * @param xmlString GPX 文件文本
 * @returns {@code TrackPointDTO[]}（seq 升序，与文档序一致）
 * @throws Error 输入空/非法，或未解析到任何有效 trkpt
 */
export function parseGpx(xmlString: string): TrackPointDTO[] {
  if (!xmlString || typeof xmlString !== 'string' || !xmlString.trim()) {
    throw new Error('parseGpx: 输入为空或非法 GPX 内容')
  }

  // 逐个 <trkpt ...>...</trkpt> 节点：非贪婪跨点捕获（属性段 + 内部子元素段）
  const trkptRe = /<trkpt([^>]*)>([\s\S]*?)<\/trkpt>/g
  const timeRe = /<time>\s*([^<]+?)\s*<\/time>/
  const eleRe = /<ele>\s*([-\d.]+)\s*<\/ele>/
  const latAttrRe = /lat\s*=\s*["']([^"']+)["']/
  const lonAttrRe = /lon\s*=\s*["']([^"']+)["']/

  const parsed: { lat: number; lng: number; ts: number; ele?: number }[] = []
  let m: RegExpExecArray | null
  while ((m = trkptRe.exec(xmlString)) !== null) {
    const attrs = m[1] || ''
    const body = m[2] || ''
    const latMatch = latAttrRe.exec(attrs)
    const lonMatch = lonAttrRe.exec(attrs)
    if (!latMatch || !lonMatch) {
      // 缺经纬度属性 → 该点无效，跳过
      continue
    }
    const lat = Number.parseFloat(latMatch[1])
    const lng = Number.parseFloat(lonMatch[1])
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
      continue
    }
    let ts = 0
    const timeMatch = timeRe.exec(body)
    if (timeMatch) {
      const t = Date.parse(timeMatch[1].trim())
      if (!Number.isNaN(t)) {
        ts = t
      }
    }
    const item: { lat: number; lng: number; ts: number; ele?: number } = { lat, lng, ts }
    // TASK-273 海拔增强：<ele> 存在且为有限数值时填入 point.ele，缺失/非法省略
    const eleMatch = eleRe.exec(body)
    if (eleMatch) {
      const ele = Number.parseFloat(eleMatch[1])
      if (Number.isFinite(ele)) {
        item.ele = ele
      }
    }
    parsed.push(item)
  }

  if (parsed.length === 0) {
    throw new Error('parseGpx: 未解析到任何有效 trkpt 轨迹点')
  }

  // 补齐 seq 文档序、缺时间戳点按首点偏移递推、相邻点 speed（m/s）
  return parsed.map((p, i) => {
    const seq = i
    const prev = parsed[i - 1]
    let ts = p.ts
    if (ts === 0 && i > 0) {
      // 无时间戳：以首点 ts（或 0）为基递推偏移 i（秒），保证单调可推导
      ts = parsed[0].ts + i * 1000
    }
    let speed: number | undefined
    if (prev) {
      const dtMs = ts - prev.ts
      const dMeters = haversineDistanceMeters(prev.lat, prev.lng, p.lat, p.lng)
      if (dtMs > 0) {
        speed = Math.round((dMeters / (dtMs / 1000)) * 100) / 100
      }
    }
    const point: TrackPointDTO = { seq, lat: p.lat, lng: p.lng, ts }
    if (speed !== undefined) {
      point.speed = speed
    }
    if (p.ele !== undefined) {
      point.ele = p.ele
    }
    return point
  })
}

/** 两点球面距离（米，Haversine；R=6371000，与后端推导同公式同常量）。 */
function haversineDistanceMeters(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371000
  const rad = (x: number) => (x * Math.PI) / 180
  const dLat = rad(lat2 - lat1)
  const dLng = rad(lng2 - lng1)
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(s))
}
