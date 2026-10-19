<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="🏃 跑团广场">
      <p class="text-gray-600 mb-4">
        GET /user/api/clubs（keyword 名称模糊 + 分页钳制 1..100）；一人一团由
        club_member 唯一键 uk_user_club 物理约束。团队累计里程走
        GET /leaderboard/api/leaderboard/clubs（总榜 / 本周，见榜单页「跑团里程榜」）。
      </p>

      <!-- 我的跑团状态条（TASK-275）：已入团展示身份与退出，未入团给创建入口 -->
      <a-alert v-if="myClub" type="success" show-icon class="mb-4">
        <template #message>
          已加入「{{ myClub.name }}」
          <a-tag v-if="myRoleLabel" color="gold" class="ml-1">{{ myRoleLabel }}</a-tag>
          · {{ myClub.memberCount }} 人
          <a-divider type="vertical" />
          <a @click="openMembers(myClub.id, myClub.name)">查看成员</a>
          <a-divider type="vertical" />
          <a-popconfirm title="确认退出该跑团？" ok-text="确认" cancel-text="取消" @confirm="handleLeave">
            <a class="text-red-500">退出跑团</a>
          </a-popconfirm>
        </template>
      </a-alert>
      <div v-else class="mb-4">
        <a-button type="primary" @click="openCreate">创建跑团</a-button>
        <span class="ml-2 text-xs text-gray-400">未入团用户可创建或加入一个跑团（一人一团）。</span>
      </div>

      <!-- 搜索（名称模糊） -->
      <div class="mb-4">
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索跑团名称"
          style="max-width: 320px"
          allow-clear
          @search="onSearch"
        />
      </div>

      <a-table
        :data-source="clubs"
        :columns="columns"
        :loading="loading"
        row-key="id"
        size="small"
        :pagination="pagination"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'actions'">
            <a @click="openMembers(record.id, record.name)">成员</a>
            <a-divider type="vertical" />
            <a v-if="!myClub" @click="handleJoin(record)">加入</a>
            <span v-else class="text-gray-400">已在团内</span>
          </template>
          <span v-else>{{ record[column.dataIndex] }}</span>
        </template>
      </a-table>
      <a-alert v-if="loadError" class="mt-2" type="error" :message="loadError" />
    </a-card>

    <!-- 成员抽屉：按加入时间升序，团长/团员标识 + 成员贡献明细（TASK-276 R3） -->
    <a-drawer v-model:open="memberDrawerOpen" :title="`成员名单 - ${memberClubName}`" width="420">
      <div v-if="memberLoading" class="py-4 text-gray-500">加载成员中…</div>
      <a-empty v-else-if="!members.length" description="暂无成员" />
      <div v-else class="space-y-2">
        <p class="mb-2 text-xs text-gray-400">
          贡献取自 GET /leaderboard/api/leaderboard/clubs/{clubId}/contributions
          （团内成员里程降序 TOP N，未上榜成员不显示贡献）。
        </p>
        <div
          v-for="m in members"
          :key="m.id"
          class="flex items-center justify-between rounded border border-gray-100 p-2"
        >
          <div class="flex items-center gap-2">
            <span class="font-medium text-gray-800">{{ m.nickname || `用户${m.userId}` }}</span>
            <a-tag :color="m.role === 'CAPTAIN' ? 'gold' : 'blue'">
              {{ m.role === 'CAPTAIN' ? '团长' : '团员' }}
            </a-tag>
            <a-tag v-if="contributionOf(m.userId)" color="purple">{{ contributionOf(m.userId) }}</a-tag>
          </div>
          <div class="flex items-center gap-2">
            <a-popconfirm
              v-if="isCaptain && m.role !== 'CAPTAIN'"
              title="确认把团长移交给该成员？移交后你将成为团员。"
              ok-text="确认"
              cancel-text="取消"
              @confirm="handleTransferCaptain(m)"
            >
              <a class="text-xs text-blue-500">移交团长</a>
            </a-popconfirm>
            <span class="text-xs text-gray-400">{{ formatDate(m.joinedAt) }} 加入</span>
          </div>
        </div>
      </div>
    </a-drawer>

    <!-- 创建跑团 Modal：名称唯一，一人一团 -->
    <a-modal
      v-model:open="createOpen"
      title="创建跑团"
      ok-text="创建"
      cancel-text="取消"
      :confirm-loading="creating"
      @ok="handleCreate"
    >
      <a-form layout="vertical" class="mt-4">
        <a-form-item label="跑团名称" required>
          <a-input v-model:value="createForm.name" placeholder="64 字符内，全站唯一" :maxlength="64" />
        </a-form-item>
        <a-form-item label="跑团简介">
          <a-textarea v-model:value="createForm.description" placeholder="介绍你们的跑团（可选）" :rows="3" :maxlength="255" />
        </a-form-item>
        <a-form-item label="团徽 URL">
          <a-input v-model:value="createForm.badgeUrl" placeholder="https://…（可选）" :maxlength="255" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  createClub,
  getClubContributions,
  getClubMembers,
  getClubs,
  getMyClub,
  getUserProfile,
  joinClub,
  leaveClub,
  transferClubCaptain,
  type ClubDTO,
  type ClubMemberContributionDTO,
  type ClubMemberDTO,
} from '@/api/client'

// ===== 广场列表（分页 + 关键词） =====
const clubs = ref<ClubDTO[]>([])
const loading = ref(false)
const loadError = ref('')
const keyword = ref('')
const page = ref(1)
const total = ref(0)
const PAGE_SIZE = 20

const columns = [
  { title: 'id', dataIndex: 'id', key: 'id' },
  { title: '跑团名称', dataIndex: 'name', key: 'name' },
  { title: '简介', dataIndex: 'description', key: 'description' },
  { title: '团长', dataIndex: 'creatorNickname', key: 'creatorNickname' },
  { title: '成员数', dataIndex: 'memberCount', key: 'memberCount' },
  { title: '操作', key: 'actions' },
]

const pagination = computed(() => ({
  current: page.value,
  pageSize: PAGE_SIZE,
  total: total.value,
  onChange: (p: number) => {
    page.value = p
    loadClubs()
  },
}))

/** 请求序号守卫（TASK-257 E26 范式）：快速搜索/翻页时陈旧响应不落数据态 */
let loadSeq = 0

async function loadClubs() {
  const seq = ++loadSeq
  loading.value = true
  loadError.value = ''
  try {
    const res = await getClubs(keyword.value || undefined, page.value, PAGE_SIZE)
    if (seq !== loadSeq) return
    clubs.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } catch (e: any) {
    if (seq !== loadSeq) return
    loadError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '跑团广场加载失败')
    clubs.value = []
    total.value = 0
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

function onSearch() {
  page.value = 1
  loadClubs()
}

// ===== 我的跑团状态（一人一团） =====
const myClub = ref<ClubDTO | null>(null)
const myRoleLabel = ref('')

async function loadMyClub() {
  try {
    const res = await getMyClub()
    myClub.value = res.data || null
    myRoleLabel.value = ''
    if (myClub.value) {
      // 团内角色：我的跑团 DTO 不携带本人角色，经个人资料 id 与 creatorId 判定
      const profile = await getUserProfile()
      if (profile.data && myClub.value) {
        myRoleLabel.value = profile.data.id === myClub.value.creatorId ? '团长' : '团员'
      }
    }
  } catch {
    myClub.value = null
  }
}

// ===== 成员抽屉 =====
const memberDrawerOpen = ref(false)
const memberClubName = ref('')
const memberLoading = ref(false)
const members = ref<ClubMemberDTO[]>([])
/** 成员贡献明细（TASK-276 R3）：userId → 贡献行（贡献榜 TOP N 才有条目） */
const contributions = ref<Record<number, ClubMemberContributionDTO>>({})

/** 本人是否为团长（成员抽屉内的「移交团长」仅团长可见） */
const isCaptain = computed(() => myRoleLabel.value === '团长')

/** 成员贡献标签：贡献榜命中才展示（米 → 公里 2 位小数 + 名次） */
function contributionOf(userId: number): string {
  const row = contributions.value[userId]
  if (!row) return ''
  return `贡献 ${(row.distanceMeters / 1000).toFixed(2)} km · 第${row.rank}名`
}

async function openMembers(clubId: number, name?: string) {
  memberClubName.value = name || ''
  memberDrawerOpen.value = true
  memberLoading.value = true
  members.value = []
  contributions.value = {}
  try {
    // 贡献明细是展示增强：单独 catch 归零，user-service/leaderboard-service 降级不阻断成员名单
    const [membersRes, contribRes] = await Promise.all([
      getClubMembers(clubId),
      getClubContributions(clubId).catch(() => null),
    ])
    members.value = membersRes.data || []
    const map: Record<number, ClubMemberContributionDTO> = {}
    for (const row of (contribRes && contribRes.data) || []) {
      map[row.userId] = row
    }
    contributions.value = map
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '成员列表加载失败'))
  } finally {
    memberLoading.value = false
  }
}

/** 团长移交（TASK-276 R1）：成功后本人降为团员，成员名单与广场成员数重拉 */
async function handleTransferCaptain(m: ClubMemberDTO) {
  const club = myClub.value
  if (!club) return
  try {
    await transferClubCaptain(club.id, m.userId)
    message.success(`已将团长移交给「${m.nickname || `用户${m.userId}`}」`)
    myRoleLabel.value = '团员'
    await Promise.all([loadMyClub(), loadClubs(), openMembers(club.id, memberClubName.value)])
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '移交失败'))
  }
}

// ===== 加入 / 退出 / 创建 =====
async function handleJoin(record: ClubDTO) {
  try {
    await joinClub(record.id)
    message.success(`已加入「${record.name}」`)
    await Promise.all([loadMyClub(), loadClubs()])
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '加入失败'))
  }
}

async function handleLeave() {
  if (!myClub.value) return
  try {
    await leaveClub(myClub.value.id)
    message.success('已退出跑团')
    myClub.value = null
    myRoleLabel.value = ''
    await loadClubs()
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '退出失败'))
  }
}

const createOpen = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', description: '', badgeUrl: '' })

function openCreate() {
  createForm.name = ''
  createForm.description = ''
  createForm.badgeUrl = ''
  createOpen.value = true
}

async function handleCreate() {
  if (!createForm.name.trim()) {
    message.warning('跑团名称不能为空')
    return
  }
  creating.value = true
  try {
    await createClub({
      name: createForm.name.trim(),
      description: createForm.description || undefined,
      badgeUrl: createForm.badgeUrl || undefined,
    })
    message.success('跑团创建成功，你就是团长！')
    createOpen.value = false
    await Promise.all([loadMyClub(), loadClubs()])
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '创建失败'))
  } finally {
    creating.value = false
  }
}

function formatDate(iso?: string): string {
  return iso ? String(iso).slice(0, 10) : '—'
}

onMounted(() => {
  loadMyClub()
  loadClubs()
})
</script>
