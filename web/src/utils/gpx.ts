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
 * - TrackPointDTO 无海拔字段（client.ts:11-19），GPX <ele> 合法省略。
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
    lines.push(`        <time>${new Date(p.ts).toISOString()}</time>`)
    lines.push('      </trkpt>')
  }
  lines.push('    </trkseg>')
  lines.push('  </trk>')
  lines.push('</gpx>')
  return lines.join('\n')
}
