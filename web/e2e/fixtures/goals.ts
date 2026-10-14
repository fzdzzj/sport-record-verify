/**
 * 运动目标夹具（TASK-227 route-mock，TASK-225 目标卡片交互承载）：
 * 初始 2 条：周·距离 50km（65%）与 月·次数 12 次（100% 已达成）。
 * upsert 语义：同 (periodType, metric) 存在则覆盖 targetValue，否则追加新目标（id 自增）。
 * percent = min(100, floor(achieved / target * 100))，与后端 pull-on-read 口径同构。
 */
export interface Goal {
  id: number
  userId: number
  periodType: number // 0=周 1=月
  metric: number // 0=距离 1=次数
  targetValue: number
  achievedValue: number
  percent: number
  createdAt: string
}

export interface GoalUpsert {
  periodType: number
  metric: number
  targetValue: number
}

export function createGoalStore(): {
  goals: Goal[]
  upsert(p: GoalUpsert): Goal
  deleteById(id: number): boolean
} {
  const goals: Goal[] = [
    { id: 1, userId: 7, periodType: 0, metric: 0, targetValue: 50, achievedValue: 32.5, percent: 65, createdAt: '2026-10-01T08:00:00' },
    { id: 2, userId: 7, periodType: 1, metric: 1, targetValue: 12, achievedValue: 12, percent: 100, createdAt: '2026-10-01T08:00:00' },
  ]
  let nextId = 3

  function upsert(p: GoalUpsert): Goal {
    const existing = goals.find((g) => g.periodType === p.periodType && g.metric === p.metric)
    if (existing) {
      existing.targetValue = p.targetValue
      existing.percent = Math.min(100, Math.floor((existing.achievedValue / existing.targetValue) * 100))
      return existing
    }
    const g: Goal = {
      id: nextId++,
      userId: 7,
      periodType: p.periodType,
      metric: p.metric,
      targetValue: p.targetValue,
      achievedValue: 0,
      percent: 0,
      createdAt: '2026-10-14T08:00:00',
    }
    goals.push(g)
    return g
  }

  function deleteById(id: number): boolean {
    const idx = goals.findIndex((g) => g.id === id)
    if (idx < 0) return false
    goals.splice(idx, 1)
    return true
  }

  return { goals, upsert, deleteById }
}
