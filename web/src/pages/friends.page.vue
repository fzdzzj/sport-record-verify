<template>
  <div class="max-w-3xl mx-auto">
    <a-card title="好友申请与处理" class="mb-4">
      <p class="text-gray-600 mb-4">POST /user/api/friends/requests （仅 targetUserId）；GET /user/api/friends/requests 收到的待处理申请（TASK-235 一键同意/拒绝）；GET /user/api/friends 列表（仅 ACCEPTED）。冲突 5001 等错误直接展示。需登录会话（Bearer，身份由网关注入）。</p>

      <a-card title="发起申请" size="small" class="mb-4">
        <a-input v-model:value="targetUserId" placeholder="targetUserId (数字)" style="width: 200px" />
        <a-button :loading="creating" @click="onCreateRequest" class="ml-2">发起好友申请</a-button>
        <div v-if="createResult" class="mt-2 text-green-600">已创建/返回 requestId: <b>{{ createResult.id }}</b> status: {{ createResult.status }} to: {{ createResult.toUser }}</div>
        <a-alert v-if="createError" type="error" :message="createError" class="mt-2" />
      </a-card>

      <a-card title="收到的好友申请 (pending，TASK-235)" size="small" class="mb-4">
        <a-button :loading="requestsLoading" @click="loadRequests" class="mb-2">刷新申请列表</a-button>
        <a-table v-if="pendingRequests.length" :data-source="pendingRequests" :columns="requestColumns" size="small" row-key="id" :pagination="false" class="mt-2">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'actions'">
              <a-button type="primary" size="small" :loading="handlingId === record.id" :disabled="!!handlingId" @click="onAcceptRequest(record.id)">同意</a-button>
              <a-button danger size="small" class="ml-2" :loading="handlingId === record.id" :disabled="!!handlingId" @click="onRejectRequest(record.id)">拒绝</a-button>
            </template>
          </template>
        </a-table>
        <div v-else class="text-gray-500 mt-2">暂无待处理的好友申请。</div>
        <a-alert v-if="requestError" type="error" :message="requestError" class="mt-2" />
        <div class="text-xs text-gray-500 mt-1">列表来自 GET /user/api/friends/requests（仅 PENDING，to_user=当前登录用户，createdAt 倒序）；一键同意/拒绝即对该申请 POST accept|reject，无需再手工敲 requestId。</div>
      </a-card>

      <a-card title="我的好友列表 (accepted)" size="small">
        <a-button @click="loadFriends" :loading="friendsLoading">刷新列表</a-button>
        <a-table v-if="friends.length" :data-source="friends" :columns="friendColumns" size="small" row-key="userId" class="mt-2">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'actions'">
              <a-popconfirm
                title="确定解除该好友关系吗？"
                ok-text="解除"
                cancel-text="取消"
                @confirm="onDeleteFriend(record.userId)"
              >
                <a-button danger size="small" :disabled="!!deletingId">解除好友</a-button>
              </a-popconfirm>
            </template>
          </template>
        </a-table>
        <div v-else class="text-gray-500 mt-2">无好友或未刷新。</div>
        <div class="text-xs text-gray-500 mt-1">好友榜过滤依赖此关系；空列表正常。</div>
      </a-card>

      <div class="mt-4 text-xs">提示：创建申请返回 request id 后，目标用户可在「收到的好友申请」卡片一键同意/拒绝。列表仅显示已通过的好友。5001=冲突(重复/已存在)。</div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { createFriendRequest, acceptFriendRequest, rejectFriendRequest, listFriendRequests, listMyFriends, deleteFriend, type FriendDTO, type FriendRequestDTO } from '@/api/client'
import { message } from 'ant-design-vue'

const targetUserId = ref<number | null>(null)
const creating = ref(false)
const createResult = ref<FriendRequestDTO | null>(null)
const createError = ref('')

const pendingRequests = ref<FriendRequestDTO[]>([])
const requestsLoading = ref(false)
const requestError = ref('')
const handlingId = ref<number | null>(null)
const requestColumns = [
  { title: '申请ID', dataIndex: 'id', key: 'id', width: 80 },
  { title: '申请人 (fromUser)', dataIndex: 'fromUser', key: 'fromUser' },
  { title: '申请时间', dataIndex: 'createdAt', key: 'createdAt' },
  { title: '操作', key: 'actions', width: 140 },
]

const friends = ref<FriendDTO[]>([])
const friendsLoading = ref(false)
const deletingId = ref<number | null>(null)
const friendColumns = [
  { title: 'userId', dataIndex: 'userId', key: 'userId' },
  { title: 'nickname', dataIndex: 'nickname', key: 'nickname' },
  { title: 'createdAt', dataIndex: 'createdAt', key: 'createdAt' },
  { title: '操作', key: 'actions', width: 120 },
]

async function onCreateRequest() {
  if (!targetUserId.value || creating.value) return
  creating.value = true
  createError.value = ''
  createResult.value = null
  try {
    const res = await createFriendRequest(Number(targetUserId.value))
    createResult.value = res.data
  } catch (e: any) {
    createError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '申请失败')
  } finally {
    creating.value = false
  }
}

async function loadRequests() {
  requestsLoading.value = true
  requestError.value = ''
  try {
    const res = await listFriendRequests(1, 20)
    pendingRequests.value = res.data?.records || []
  } catch (e: any) {
    requestError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '加载申请列表失败')
    pendingRequests.value = []
  } finally {
    requestsLoading.value = false
  }
}

async function onAcceptRequest(id: number) {
  if (handlingId.value) return
  handlingId.value = id
  requestError.value = ''
  try {
    await acceptFriendRequest(id)
    await loadRequests()
    await loadFriends()
  } catch (e: any) {
    requestError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '操作失败')
  } finally {
    handlingId.value = null
  }
}

async function onRejectRequest(id: number) {
  if (handlingId.value) return
  handlingId.value = id
  requestError.value = ''
  try {
    await rejectFriendRequest(id)
    await loadRequests()
  } catch (e: any) {
    requestError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '操作失败')
  } finally {
    handlingId.value = null
  }
}

async function loadFriends() {
  friendsLoading.value = true
  try {
    const res = await listMyFriends(1, 20)
    friends.value = res.data?.records || []
  } catch (e: any) {
    console.error('list friends err', e)
    friends.value = []
  } finally {
    friendsLoading.value = false
  }
}

/** 解除好友关系（TASK-242 B14）：DELETE /user/api/friends/{targetUserId}，成功后刷新列表 */
async function onDeleteFriend(targetUserId: number) {
  if (deletingId.value) return
  deletingId.value = targetUserId
  try {
    await deleteFriend(targetUserId)
    message.success('已解除好友关系')
    await loadFriends()
  } catch (e: any) {
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || '解除好友失败'))
  } finally {
    deletingId.value = null
  }
}

onMounted(() => {
  loadRequests()
  loadFriends()
})
</script>