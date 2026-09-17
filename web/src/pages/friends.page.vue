<template>
  <div class="max-w-3xl mx-auto">
    <a-card title="好友申请与处理" class="mb-4">
      <p class="text-gray-600 mb-4">POST /user/api/friends/requests （仅 targetUserId）；POST /.../accept|reject ；GET /user/api/friends 列表（仅 ACCEPTED）。冲突 5001 等错误直接展示。需登录会话（Bearer）。</p>

      <a-card title="发起申请" size="small" class="mb-4">
        <a-input v-model:value="targetUserId" placeholder="targetUserId (数字)" style="width: 200px" />
        <a-button :loading="creating" @click="onCreateRequest" class="ml-2">发起好友申请</a-button>
        <div v-if="createResult" class="mt-2 text-green-600">已创建/返回 requestId: <b>{{ createResult.id }}</b> status: {{ createResult.status }} to: {{ createResult.toUser }}</div>
        <a-alert v-if="createError" type="error" :message="createError" class="mt-2" />
      </a-card>

      <a-card title="处理申请 (输入 request id)" size="small" class="mb-4">
        <a-input v-model:value="requestIdToHandle" placeholder="requestId (从 create 返回或其它途径)" style="width:180px" />
        <a-button :loading="handling" @click="onAccept" class="ml-2">同意</a-button>
        <a-button :loading="handling" @click="onReject" class="ml-2">拒绝</a-button>
        <a-alert v-if="handleResult" type="success" :message="`处理成功: ${handleResult.status}`" class="mt-2" />
        <a-alert v-if="handleError" type="error" :message="handleError" class="mt-2" />
      </a-card>

      <a-card title="我的好友列表 (accepted)" size="small">
        <a-button @click="loadFriends" :loading="friendsLoading">刷新列表</a-button>
        <a-table v-if="friends.length" :data-source="friends" :columns="friendColumns" size="small" row-key="userId" class="mt-2" />
        <div v-else class="text-gray-500 mt-2">无好友或未刷新。</div>
        <div class="text-xs text-gray-500 mt-1">好友榜过滤依赖此关系；空列表正常。</div>
      </a-card>

      <div class="mt-4 text-xs">提示：创建申请返回 request id 后，目标用户可用该 id 同意/拒绝。列表仅显示已通过的好友。5001=冲突(重复/已存在)。</div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import api, { createFriendRequest, acceptFriendRequest, rejectFriendRequest, listMyFriends, type FriendDTO, type FriendRequestDTO, type PageResult } from '@/api/client'

const targetUserId = ref<number | null>(null)
const creating = ref(false)
const createResult = ref<FriendRequestDTO | null>(null)
const createError = ref('')

const requestIdToHandle = ref<number | null>(null)
const handling = ref(false)
const handleResult = ref<FriendRequestDTO | null>(null)
const handleError = ref('')

const friends = ref<FriendDTO[]>([])
const friendsLoading = ref(false)
const friendColumns = [
  { title: 'userId', dataIndex: 'userId', key: 'userId' },
  { title: 'nickname', dataIndex: 'nickname', key: 'nickname' },
  { title: 'createdAt', dataIndex: 'createdAt', key: 'createdAt' },
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

async function onAccept() {
  if (!requestIdToHandle.value || handling.value) return
  handling.value = true
  handleError.value = ''
  handleResult.value = null
  try {
    const res = await acceptFriendRequest(Number(requestIdToHandle.value))
    handleResult.value = res.data
    await loadFriends()
  } catch (e: any) {
    handleError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '操作失败')
  } finally {
    handling.value = false
  }
}

async function onReject() {
  if (!requestIdToHandle.value || handling.value) return
  handling.value = true
  handleError.value = ''
  handleResult.value = null
  try {
    const res = await rejectFriendRequest(Number(requestIdToHandle.value))
    handleResult.value = res.data
  } catch (e: any) {
    handleError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '操作失败')
  } finally {
    handling.value = false
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
</script>
