<template>
  <div class="max-w-5xl mx-auto">
    <a-card title="通知中心" class="mb-4">
      <p class="text-gray-600 mb-2">GET /user/api/notifications 分页（id 倒序）；GET /user/api/notifications/unread-count 未读数；PATCH /{id}/read 单条已读（5003=不存在/已读/非本人）；PATCH /read-all 全部已读（返回流转条数）。需登录会话（Bearer，身份由网关注入，前端不传 userId）。</p>
      <a-alert v-if="!loggedIn" type="info" message="未登录：通知需登录会话，当前仅展示空态，不发起请求。请先登录。" class="mb-2" />

      <div class="mb-2">
        <a-button :loading="loading" @click="loadList">刷新</a-button>
        <a-button class="ml-2" :loading="markAllLoading" @click="onMarkAll">全部已读</a-button>
        <a-alert v-if="infoMessage" type="success" :message="infoMessage" class="mt-2" />
        <a-alert v-if="errorMessage" type="error" :message="errorMessage" class="mt-2" />
      </div>

      <a-table
        :data-source="rows"
        :columns="columns"
        :pagination="false"
        size="small"
        row-key="id"
        class="mt-2"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'isRead'">
            <a-tag :color="record.isRead === 0 ? 'orange' : 'green'">
              {{ record.isRead === 0 ? '未读' : '已读' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button
              v-if="record.isRead === 0"
              size="small"
              :loading="readingId === record.id"
              @click="onMarkRead(record)"
            >标已读</a-button>
            <span v-else class="text-gray-400">—</span>
          </template>
        </template>
        <template #emptyText>
          {{ loggedIn ? '暂无通知' : '未登录，暂无数据' }}
        </template>
      </a-table>

      <div class="mt-2 flex justify-end">
        <a-pagination
          :current="page"
          :page-size="size"
          :total="total"
          show-size-changer
          :page-size-options="['10','20','50']"
          @change="onPageChange"
          @show-size-change="onSizeChange"
        />
      </div>
    </a-card>

    <!-- 通知偏好设置卡片（TASK-187 add-notification-preference） -->
    <a-card title="通知偏好" class="mb-4">
      <p class="text-gray-600 mb-2">按类型开关通知接收（缺行默认开启）。关闭后该类型新通知不落库、不推送、不计未读；重新开启仅对未来通知生效。</p>
      <div v-if="!loggedIn" class="text-gray-400">未登录：通知需登录会话，当前仅展示空态，不发起请求。</div>
      <div v-else>
        <div class="space-y-4 max-w-md">
          <div class="flex items-center justify-between py-2 border-b">
            <span>记录通过</span>
            <a-switch v-model:checked="prefRecordVerified" />
          </div>
          <div class="flex items-center justify-between py-2 border-b">
            <span>记录驳回</span>
            <a-switch v-model:checked="prefRecordRejected" />
          </div>
          <div class="flex items-center justify-between py-2 border-b">
            <span>好友通过</span>
            <a-switch v-model:checked="prefFriendAccepted" />
          </div>
          <div class="flex items-center justify-between py-2 border-b">
            <span>收到点赞</span>
            <a-switch v-model:checked="prefRecordLiked" />
          </div>
        </div>
        <div class="mt-4 flex items-center">
          <a-button type="primary" :loading="savingPrefs" @click="savePreferences">保存</a-button>
          <a-button class="ml-2" :loading="loadingPrefs" @click="loadPreferences">刷新偏好</a-button>
        </div>
        <a-alert v-if="prefErrorMessage" type="error" :message="prefErrorMessage" class="mt-2" />
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { message } from 'ant-design-vue'
import {
  listNotifications,
  markNotificationRead,
  markAllNotificationsRead,
  getNotificationPreferences,
  updateNotificationPreferences,
  type NotificationViewDTO,
  type NotificationPreferenceView,
} from '@/api/client'
import { hasAccessToken } from '@/utils/token'
import { refreshUnread, onNotification } from '@/composables/useNotificationBell'

const loggedIn = computed(() => hasAccessToken())

const rows = ref<NotificationViewDTO[]>([])
const loading = ref(false)
const readingId = ref<number | null>(null)
const markAllLoading = ref(false)
const infoMessage = ref('')
const errorMessage = ref('')

const page = ref(1)
const size = ref(10)
const total = ref(0)

const columns = [
  { title: 'id', dataIndex: 'id', key: 'id', width: 70 },
  { title: '类型', dataIndex: 'type', key: 'type' },
  { title: '标题', dataIndex: 'title', key: 'title' },
  { title: 'sourceId', dataIndex: 'sourceId', key: 'sourceId', width: 100 },
  { title: '已读', dataIndex: 'isRead', key: 'isRead', width: 80 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
  { title: '操作', dataIndex: 'action', key: 'action', width: 90 },
]

async function loadList() {
  if (!loggedIn.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await listNotifications(page.value, size.value)
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
    if (res.data && typeof res.data.current === 'number' && res.data.current > 0) {
      page.value = res.data.current
    }
  } catch (e: any) {
    errorMessage.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function onMarkRead(record: NotificationViewDTO) {
  if (readingId.value !== null) return
  readingId.value = record.id
  errorMessage.value = ''
  infoMessage.value = ''
  try {
    await markNotificationRead(record.id)
    record.isRead = 1
    await refreshUnread()
  } catch (e: any) {
    errorMessage.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '操作失败')
  } finally {
    readingId.value = null
  }
}

async function onMarkAll() {
  if (markAllLoading.value) return
  markAllLoading.value = true
  errorMessage.value = ''
  infoMessage.value = ''
  try {
    const res = await markAllNotificationsRead()
    const n = typeof res.data === 'number' ? res.data : 0
    infoMessage.value = `本次流转 ${n} 条`
    await loadList()
    await refreshUnread()
  } catch (e: any) {
    errorMessage.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '操作失败')
  } finally {
    markAllLoading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  loadList()
}

function onSizeChange(_current: number, s: number) {
  size.value = s
  page.value = 1
  loadList()
}

// ===== 通知偏好（TASK-187 add-notification-preference） =====
const prefRecordVerified = ref(true)
const prefRecordRejected = ref(true)
const prefFriendAccepted = ref(true)
const prefRecordLiked = ref(true)
const loadingPrefs = ref(false)
const savingPrefs = ref(false)
const prefErrorMessage = ref('')

function applyPreferences(list?: NotificationPreferenceView[]) {
  if (!list) return
  for (const item of list) {
    if (item.type === 'RECORD_VERIFIED') {
      prefRecordVerified.value = item.enabled
    } else if (item.type === 'RECORD_REJECTED') {
      prefRecordRejected.value = item.enabled
    } else if (item.type === 'FRIEND_ACCEPTED') {
      prefFriendAccepted.value = item.enabled
    } else if (item.type === 'RECORD_LIKED') {
      prefRecordLiked.value = item.enabled
    }
  }
}

async function loadPreferences() {
  if (!loggedIn.value) return
  loadingPrefs.value = true
  prefErrorMessage.value = ''
  try {
    const res = await getNotificationPreferences()
    applyPreferences(res.data)
  } catch (e: any) {
    prefErrorMessage.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '加载通知偏好失败')
  } finally {
    loadingPrefs.value = false
  }
}

async function savePreferences() {
  if (!loggedIn.value) return
  savingPrefs.value = true
  prefErrorMessage.value = ''
  try {
    const payload = [
      { type: 'RECORD_VERIFIED', enabled: prefRecordVerified.value },
      { type: 'RECORD_REJECTED', enabled: prefRecordRejected.value },
      { type: 'FRIEND_ACCEPTED', enabled: prefFriendAccepted.value },
      { type: 'RECORD_LIKED', enabled: prefRecordLiked.value },
    ]
    const res = await updateNotificationPreferences(payload)
    message.success('通知偏好保存成功')
    if (res.data) {
      applyPreferences(res.data)
    }
  } catch (e: any) {
    prefErrorMessage.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '保存通知偏好失败')
  } finally {
    savingPrefs.value = false
  }
}

let unregisterNotification: (() => void) | null = null

onMounted(() => {
  if (loggedIn.value) {
    loadList()
    refreshUnread()
    loadPreferences()
  }
  // 实时推送（TASK-185）：页面打开期间收到新通知即刷新当前页；卸载时注销
  unregisterNotification = onNotification(() => {
    if (loggedIn.value) {
      loadList()
    }
  })
})

onBeforeUnmount(() => {
  if (unregisterNotification) {
    unregisterNotification()
    unregisterNotification = null
  }
})
</script>
