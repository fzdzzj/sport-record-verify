import { describe, expect, it } from 'vitest'
import { generateGpx, parseGpx } from '@/utils/gpx'
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

describe('gpx.ts parseGpx（TASK-245 B28）', () => {
  // B28 R1：合法 GPX（generateGpx 导出）→ 回读出点数/经纬度/ts/seq 与导出源一致（双向闭环）
  it('R1 往返 round-trip：parseGpx(generateGpx(points)) 还原点数/坐标/ts/seq', () => {
    const points = [
      pt(0, 31.230416, 121.473701, 1700000000000),
      pt(1, 31.2305, 121.4738, 1700000001000),
      pt(2, 31.2306, 121.4739, 1700000002000),
    ]
    const xml = generateGpx(points)
    const out = parseGpx(xml)

    expect(out).toHaveLength(3)
    out.forEach((p, i) => {
      expect(p.seq).toBe(i)
      expect(p.lat).toBe(points[i].lat)
      expect(p.lng).toBe(points[i].lng)
      expect(p.ts).toBe(points[i].ts)
    })
  })

  // B28 R2：时间戳由 <time> ISO8601 正确提取为 epoch 毫秒
  it('R2 从 <time> 提取 epoch 毫秒 ts', () => {
    const xml = `<?xml version="1.0"?>
<gpx>
  <trk><trkseg>
    <trkpt lat="31.1" lon="121.1"><time>2023-11-14T22:13:20.000Z</time></trkpt>
    <trkpt lat="31.2" lon="121.2"><time>2023-11-14T22:13:21.000Z</time></trkpt>
  </trkseg></trk>
</gpx>`
    const out = parseGpx(xml)
    expect(out).toHaveLength(2)
    expect(out[0].ts).toBe(1700000000000)
    expect(out[1].ts).toBe(1700000001000)
  })

  // B28 R3：相邻点 speed（m/s）按 距离/时间差 推导，首点不含 speed
  it('R3 相邻点 speed 推导且首点缺省', () => {
    // 赤道 0.001° 经度 ≈ 111.195 m；时间差 1s → speed ≈ 111.195 m/s（保留两位）
    const xml = `<?xml version="1.0"?>
<gpx><trk><trkseg>
  <trkpt lat="0" lon="0"><time>2023-11-14T22:13:20.000Z</time></trkpt>
  <trkpt lat="0" lon="0.001"><time>2023-11-14T22:13:21.000Z</time></trkpt>
</trkseg></trk></gpx>`
    const out = parseGpx(xml)
    expect(out[0].speed).toBeUndefined()
    expect(out[1].speed).not.toBeUndefined()
    expect(Math.round(out[1].speed as number)).toBe(111)
  })

  // B28 R4：空/非法输入抛格式错误
  it('R4 空输入或非字符串抛错', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(() => parseGpx('' as any)).toThrowError(/parseGpx/)
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(() => parseGpx('   ' as any)).toThrowError(/parseGpx/)
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(() => parseGpx(null as any)).toThrowError(/parseGpx/)
  })

  // B28 R5：解析不到任何 trkpt → 抛格式错误
  it('R5 无 trkpt 抛错', () => {
    expect(() => parseGpx('<gpx><trk><trkseg></trkseg></trk></gpx>')).toThrowError(/trkpt/)
  })

  // B28 R6：缺经纬度属性的 trkpt 被跳过；仍有点时保留有效点
  it('R6 缺经纬度节点跳过、有效点保留', () => {
    const xml = `<?xml version="1.0"?>
<gpx><trk><trkseg>
  <trkpt lat="31.1" lon="121.1"><time>2023-11-14T22:13:20.000Z</time></trkpt>
  <trkpt><time>2023-11-14T22:13:21.000Z</time></trkpt>
  <trkpt lat="31.2" lon="121.2"><time>2023-11-14T22:13:22.000Z</time></trkpt>
</trkseg></trk></gpx>`
    const out = parseGpx(xml)
    expect(out).toHaveLength(2)
    expect(out[0].lat).toBe(31.1)
    expect(out[1].lat).toBe(31.2)
  })
})
