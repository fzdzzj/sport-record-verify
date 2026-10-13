import { describe, expect, it } from 'vitest'
import { generateGpx } from '@/utils/gpx'
import type { TrackPointDTO } from '@/api/client'

function pt(seq: number, lat: number, lng: number, ts: number): TrackPointDTO {
  return { seq, lat, lng, ts }
}

// 提取全部 trkpt 的 lat/lon，保持文档序。
function extractTrkpts(xml: string): { lat: string; lon: string }[] {
  const out: { lat: string; lon: string }[] = []
  const re = /<trkpt lat="([^"]+)" lon="([^"]+)">/g
  let m: RegExpExecArray | null
  while ((m = re.exec(xml)) !== null) {
    out.push({ lat: m[1], lon: m[2] })
  }
  return out
}

describe('gpx.ts 锚点（TASK-218）', () => {
  // R2a：单点恰 1 个 trkpt，且 lat/lon 固定 6 位小数精确
  it('R2a 单点恰 1 个 trkpt 且 lat/lon 精确', () => {
    const xml = generateGpx([pt(1, 31.230416, 121.473701, 1700000000000)])
    const trkpts = extractTrkpts(xml)
    expect(trkpts).toHaveLength(1)
    expect(trkpts[0]).toEqual({ lat: '31.230416', lon: '121.473701' })
    expect(xml).toContain('<gpx version="1.1" creator="sport-verify-web"')
    expect(xml).toContain('xmlns="http://www.topografix.com/GPX/1/1"')
  })

  // R2b：多点 trkpt 数量=点数，且顺序与输入一致（seq 对应）
  it('R2b 多点 trkpt 数量与顺序均与输入一致', () => {
    const points = [
      pt(1, 31.230416, 121.473701, 1700000000000),
      pt(2, 31.2305, 121.4738, 1700000001000),
      pt(3, 31.2306, 121.4739, 1700000002000),
    ]
    const xml = generateGpx(points)
    const trkpts = extractTrkpts(xml)
    expect(trkpts).toHaveLength(points.length)
    trkpts.forEach((t, i) => {
      expect(t.lat).toBe(points[i].lat.toFixed(6))
      expect(t.lon).toBe(points[i].lng.toFixed(6))
    })
  })

  // R2c：<time> 由 epoch ms 正确转 ISO8601（已知时间戳断言）
  it('R2c time 由 epoch ms 转 ISO8601 精确', () => {
    const ts = 1700000000000
    const xml = generateGpx([pt(1, 31.230416, 121.473701, ts)])
    expect(xml).toContain(`<time>${new Date(ts).toISOString()}</time>`)
    expect(xml).toContain('2023-11-14T22:13:20.000Z')
  })

  // R2d：name 含 & < > 时被正确转义（文本节点 + 属性值同覆盖）
  it('R2d name 含 & < > 被正确转义', () => {
    const xml = generateGpx([pt(1, 31.230416, 121.473701, 1700000000000)], {
      name: 'A&B<C>D"E\'F',
    })
    expect(xml).toContain('<name>A&amp;B&lt;C&gt;D&quot;E&apos;F</name>')
    expect(xml).not.toContain('<name>A&B<C>D')
  })

  // R2e：空数组调用抛错（防御；页面按钮已按 points.length 门控）
  it('R2e 空 points 数组抛错', () => {
    expect(() => generateGpx([])).toThrowError()
  })
})
