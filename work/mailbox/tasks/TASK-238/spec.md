# TASK-238: 运动轨迹全链路真实化与反作弊合理性——长轨迹全量突破、GPX完整导出、SVG等比投影与闭环判定合理性（MEDIUM 档）

> **开工基线**：b1c0e3a（main 分支，工作树干净，TASK-237 封盘笔，CI run 38692154084 全 job 绿）
> **任务级别**：MEDIUM（前后端协同：长轨迹分页拉取、GPX完整导出、SVG数学等比投影矫正、R4规则起征门槛）

## 1. 目标与背景

- **背景**：
  1. **详情页长轨迹被腰斩与 GPX 残缺（record-detail.page.vue）**：当前详情页仅调用单次 `getRecordPoints(recordId, 1, 500)`，超过 500 点提示截断（`span v-if="points.length >= 500"`）。普通 10km 跑或半马长达数千点，导致地图上轨迹半路腰斩；且「导出 GPX」按钮基于此截断数组生成残缺 GPX 文件；
  2. **SVG 地图非等比拉伸变形（record-detail.page.vue）**：经纬度映射独立拉满 800×480（`fx = (lng - minLng)/lngRange`，`fy = (lat - minLat)/latRange`），未考虑经纬度每度物理米数差异（纬度 1 度约 111 km，经度 1 度随纬度余弦缩小），且强制拉满固定宽高。导致操场跑道或正方形路线在屏幕上被压扁变形成长椭圆/矩形，视觉失真严重；
  3. **反作弊 R4 规则极短微闭环误杀防御（R4DistanceRule.java）**：原逻辑在起终点直线距离 `straight < 1e-6` 时直接赋予比值无穷大；微短运动（如刚出门几步、原地微弱运动或误触提交）若累计距离极短且起终点重合，应提供最小累计距离起征门槛保护（`minCumulativeMeters`，默认 500.0 米），低于此起征门槛不予认定为长途折返刷里程，同时必须 100% 保证既有规范场景（`t5_returnTrackRejectedByR4`，累计 2000m 折返）稳定触发 R4 SOFT 拒绝。
- **目标**：
  1. 详情页突破 500 点硬限制：首包拉取若 `total > records.length`，自动循环分页按批追加拉取直至全量点到位（单次 500 点，最大安全上限 20000 点对齐 DTO 契约），更新提示为完整加载，GPX 导出基于全量点生成；
  2. SVG 地图投影重构为局部等距投影：结合平均纬度余弦 `cos(avgLat)` 矫正地面物理跨度，取统一缩放比 `scale = min(innerW/spanX, innerH/spanY)` 居中等比渲染，还原真实轨迹几何形状；
  3. R4 规则引入可配起征门槛 `minCumulativeMeters`（默认 500.0 米），低于门槛的微小闭环不误判，长距离折返（如测试用例 2000m）精准命中 R4，严守既有契约与单测；
  4. 新增针对性单元测试，前后端全套自动化门禁与 E2E 12 测稳定全绿。

## 2. 修改白名单

严格限定在以下 6 个文件内：

- 前端生产代码（共 1 文件）：
  - `web/src/pages/record-detail.page.vue`
- 后端生产代码与测试（共 3 文件）：
  - `verify-service/src/main/java/com/sportverify/verify/algorithm/rule/R4DistanceRule.java`
  - `verify-service/src/main/java/com/sportverify/verify/config/VerifyProperties.java`
  - `verify-service/src/test/java/com/sportverify/verify/algorithm/rule/R4DistanceRuleTest.java`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-238/spec.md`
  - `work/mailbox/tasks/TASK-238/handoff.md`

> **红线约束**：
> - 除上述 6 个文件外，其他微服务代码、网关、数据库 SQL、根 pom、`package.json`、`playwright.config.ts`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖（复用既有 Vue 3、Ant Design Vue、Spring Boot 配置体系）。

## 3. 核心设计与判别式要求

### 3.1 详情页全量轨迹循环拉取与 GPX 完整导出（web/src/pages/record-detail.page.vue）

- **全量点拉取逻辑**：
  - 在 `load()` 异步加载轨迹点时：
    ```ts
    const firstRes = await getRecordPoints(recordId.value, 1, 500)
    let allPoints = (firstRes.data && firstRes.data.records) || []
    const totalPoints = (firstRes.data && firstRes.data.total) || allPoints.length
    let curPage = 1
    const pageSize = 500
    // 若记录点数超过 500，自动循环分页按批追加拉取直至齐备（防超大死循环，上限 20000）
    while (allPoints.length < totalPoints && allPoints.length < 20000) {
      curPage++
      const nextRes = await getRecordPoints(recordId.value, curPage, pageSize)
      const nextBatch = (nextRes.data && nextRes.data.records) || []
      if (!nextBatch.length) break
      allPoints = allPoints.concat(nextBatch)
    }
    points.value = allPoints
    ```
- **UI 提示与导出对齐**：
  - 模板中的提示更新为：`共 {{ points.length }} 个轨迹点（完整加载）；起点绿、终点红，其余点为蓝，悬停查看 seq/speed/ts。`；
  - 移除旧版的 `<span v-if="points.length >= 500">单次拉取上限 500 点...</span>` 限制提示；
  - 点击「导出 GPX」按钮时，`generateGpx(points.value)` 导出的 GPX 保证为 100% 完整点集。

### 3.2 SVG 轨迹局部等距等比投影（web/src/pages/record-detail.page.vue）

- **数学投影算法**：
  - 提取点集中有效数值坐标；若点集为空返回空数组；
  - 计算平均纬度 `avgLat = (minLat + maxLat) / 2`，弧度 `latRad = (avgLat * Math.PI) / 180`，余弦修正系数 `cosLat = Math.cos(latRad)`；
  - 地面相对跨度：`spanX = (maxLng - minLng) * cosLat`，`spanY = maxLat - minLat`；
  - 内层视口尺寸：`innerW = W - PAD * 2`（800 - 32 = 768），`innerH = H - PAD * 2`（400 - 32 = 368）；
  - 退化处理：若 `spanX <= 1e-9 && spanY <= 1e-9`，所有点居中映射至 `(PAD + innerW / 2, H - PAD - innerH / 2)`；
  - 统一缩放尺度（保持真实纵横比）：
    ```ts
    const scaleX = spanX > 1e-9 ? innerW / spanX : 0
    const scaleY = spanY > 1e-9 ? innerH / spanY : 0
    const scale = (spanX > 1e-9 && spanY > 1e-9) ? Math.min(scaleX, scaleY) : (scaleX || scaleY)
    const drawW = spanX * scale
    const drawH = spanY * scale
    const offsetX = PAD + (innerW - drawW) / 2
    const offsetY = PAD + (innerH - drawH) / 2
    ```
  - 点坐标计算：
    ```ts
    return pts.map((p) => {
      const px = (Number(p.lng) - minLng) * cosLat
      const py = Number(p.lat) - minLat
      return {
        x: Number((offsetX + px * scale).toFixed(2)),
        y: Number((H - offsetY - py * scale).toFixed(2)),
      }
    })
    ```
  - 效果：正方形路线在屏幕上保持正方形，圆形操场保持正圆，不再拉伸失真。

### 3.3 反作弊 R4 规则起征门槛保护（verify-service）

- **VerifyProperties.Rules.R4 配置扩展**：
  - 在 `VerifyProperties.Rules.R4` 中新增字段：
    ```java
    /** 触发折返刷里程判定的最小累计轨迹距离（米），低于此门槛不视为折返刷里程（防微短起步误杀；默认 500.0 米） */
    private double minCumulativeMeters = 500.0;
    ```
  - 提供 getter/setter，默认值 500.0 米。
- **R4DistanceRule 逻辑优化**：
  - 在 `evaluate` 计算出 `cumulative` 后：
    ```java
    double minCumulative = typeThreshold.getR4().getMinCumulativeMeters();
    if (cumulative < minCumulative) {
        return null; // 低于起征门槛，豁免 R4 判定
    }
    ```
  - 严格保持 `cumulative >= minCumulative` 时原比值 `ratio = cumulative / straight`（若 `straight < 1e-6` 则 `ratio = Double.MAX_VALUE`）逻辑完全不变；
  - 确保既有测试 `VerifyEngineTest.t5_returnTrackRejectedByR4`（累计 2000m，起终点直线 0）继续命中 R4 SOFT 拒绝，零破坏向后兼容。
- **单元测试 R4DistanceRuleTest**：
  - 测试用例 1：累计距离 300m（< 500m）且起终点重合（straight=0）→ 返回 null（起征保护生效）；
  - 测试用例 2：累计距离 2000m（> 500m）且起终点重合（straight=0）→ 命中 R4_DISTANCE（折返刷里程命中）；
  - 测试用例 3：累计距离 1000m，直线距离 500m（ratio=2.0 <= 3.0）→ 返回 null。

## 4. 交付约束

- **分笔要求**：
  - C-01 实施笔：恰 4 个生产代码与测试文件（`record-detail.page.vue`、`R4DistanceRule.java`、`VerifyProperties.java`、`R4DistanceRuleTest.java`）；
  - C-02 台账笔：恰 2 个任务台账文件（`spec.md` + `handoff.md`）；
- **门禁要求**：
  - 后端：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service -am`（全绿）；
  - 前端：`pnpm --dir web type-check`（0 错误）、`pnpm --dir web test`（32 测全绿）、`pnpm --dir web build`（构建成功）、`pnpm --dir web e2e`（12 passed）；
  - 契约：`scripts/verify/mailbox-contract.sh --open=TASK-238 --baseline=b1c0e3a`（退出码 0）。
