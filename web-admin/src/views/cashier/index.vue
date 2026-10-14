<template>
  <div class="cashier">
    <!-- 顶部条 -->
    <div class="cashier-header">
      <div class="header-left">
        <el-icon :size="20" color="#fff"><ShoppingCart /></el-icon>
        <span class="title">收银台</span>
        <el-tag size="small" effect="dark" type="info">{{ userStore.storeName }}</el-tag>
        <el-tag size="small" effect="dark" type="info">收银员：{{ userStore.realName }}</el-tag>
        <el-tag v-if="shiftNo" size="small" effect="dark" type="success">班次 {{ shiftNo }}</el-tag>
      </div>
      <div class="header-right">
        <el-button size="small" @click="openShiftSummary">
          <el-icon><Histogram /></el-icon>交班汇总
        </el-button>
        <el-button size="small" @click="router.push('/dashboard')">
          <el-icon><Back /></el-icon>返回后台
        </el-button>
      </div>
    </div>

    <div class="cashier-body">
      <!-- ① 会员区 -->
      <div class="panel member-panel">
        <div class="panel-title">① 会员</div>
        <el-input
          v-model="memberKeyword"
          placeholder="手机号后4位 / 会员号 / 姓名"
          clearable
          @keyup.enter="searchMember"
          class="mb-12"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button class="mb-12" style="width: 100%" @click="searchMember" :loading="searching">
          检索会员
        </el-button>
        <el-button
          v-if="!member"
          style="width: 100%"
          type="info"
          plain
          @click="useGuestMode"
        >
          散客消费（不记积分）
        </el-button>

        <!-- 检索结果 -->
        <div v-if="searchResults.length && !member" class="search-results">
          <div
            v-for="m in searchResults"
            :key="m.id"
            class="member-option"
            @click="selectMember(m)"
          >
            <div class="option-main">{{ m.nickname || m.realName || m.memberNo }}</div>
            <div class="option-sub">
              {{ m.phoneMasked }} · {{ m.levelName || '普通会员' }} · 余额 ¥{{ fmt(m.balance) }}
            </div>
          </div>
        </div>

        <!-- 已选会员 -->
        <div v-if="member" class="member-card">
          <div class="member-name">
            {{ member.nickname || member.realName }}
            <el-tag size="small" effect="dark">{{ member.levelName || '普通会员' }}</el-tag>
          </div>
          <div class="member-meta">{{ member.memberNo }} · {{ member.phoneMasked }}</div>
          <div class="member-assets">
            <div>
              <div class="asset-label">本金</div>
              <div class="asset-num">¥{{ fmt(member.balance) }}</div>
            </div>
            <div>
              <div class="asset-label">赠送</div>
              <div class="asset-num" style="color: var(--brand-warning)">¥{{ fmt(member.giftBalance) }}</div>
            </div>
            <div>
              <div class="asset-label">积分</div>
              <div class="asset-num" style="color: var(--brand-success)">{{ member.points ?? 0 }}</div>
            </div>
          </div>
          <el-button link type="danger" size="small" @click="member = null">取消选择</el-button>
        </div>

        <!-- 挂单 -->
        <el-divider>挂单（{{ holdOrders.length }}）</el-divider>
        <el-empty v-if="!holdOrders.length" description="暂无挂单" :image-size="50" />
        <div v-else class="hold-list">
          <div v-for="h in holdOrders" :key="h.holdNo" class="hold-item">
            <div class="hold-main">
              <div class="hold-title">
                {{ h.memberName || '散客' }}
                <el-tag v-if="h.expired" size="small" type="danger" effect="plain">已超时</el-tag>
              </div>
              <div class="text-muted hold-sub">
                {{ h.itemCount }} 项 · ¥{{ fmt(h.totalAmount) }} · {{ (h.createdAt || '').slice(5, 16) }}
              </div>
            </div>
            <div class="hold-actions">
              <el-button link type="primary" size="small" @click="resumeHold(h)">取单</el-button>
              <el-button link type="danger" size="small" @click="cancelHold(h)">弃单</el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- ② 商品区 -->
      <div class="panel goods-panel">
        <div class="panel-title">
          ② 商品 / 服务
          <el-input
            v-model="goodsKeyword"
            placeholder="搜索商品名称/编码"
            size="small"
            style="width: 220px"
            clearable
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
        <el-tabs v-model="activeCategory">
          <el-tab-pane label="全部" name="all" />
          <el-tab-pane
            v-for="c in categories"
            :key="c.id"
            :label="c.cateName"
            :name="String(c.id)"
          />
        </el-tabs>
        <div class="goods-grid">
          <div
            v-for="p in filteredProducts"
            :key="p.id"
            class="goods-card"
            @click="addToCart(p)"
          >
            <div class="goods-name">{{ p.productName }}</div>
            <div class="goods-price">¥{{ fmt(p.memberPrice || p.price) }}</div>
            <div class="goods-unit">{{ p.unit || '件' }}</div>
          </div>
          <div class="goods-card free-amount" @click="addFreeAmount">
            <div class="goods-name">自由金额</div>
            <div class="goods-price">手动输入</div>
            <div class="goods-unit">服务/临时定价</div>
          </div>
        </div>
      </div>

      <!-- ③ 结算区 -->
      <div class="panel settle-panel">
        <div class="panel-title">③ 结算</div>

        <!-- 购物车 -->
        <div class="cart">
          <el-empty v-if="!cart.length" description="购物车为空" :image-size="60" />
          <div v-for="(item, idx) in cart" :key="idx" class="cart-item">
            <div class="cart-name">
              {{ item.productName }}
              <el-input-number
                v-model="item.qty"
                :min="0.01"
                :precision="2"
                size="small"
                controls-position="right"
                style="width: 100px; margin-left: 6px"
                @change="recalc"
              />
            </div>
            <div class="cart-price">¥{{ fmt(item.price * item.qty) }}</div>
            <el-button link type="danger" size="small" @click="removeFromCart(idx)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>

        <!-- 优惠 -->
        <div class="settle-lines">
          <div class="line">
            <span>原价合计</span>
            <span class="amount">¥{{ fmt(calc?.totalAmount ?? cartTotal) }}</span>
          </div>
          <div class="line">
            <span>会员折扣</span>
            <span class="amount amount-negative">-¥{{ fmt(calc?.discountAmount ?? 0) }}</span>
          </div>
          <div class="line">
            <span>
              优惠券
              <el-button
                link
                type="primary"
                size="small"
                @click="openCouponPicker"
                :disabled="!member"
              >
                {{ selectedCoupons.length ? `已选 ${selectedCoupons.length} 张` : '选择' }}
              </el-button>
            </span>
            <span class="amount amount-negative">-¥{{ fmt(calc?.couponAmount ?? 0) }}</span>
          </div>
          <div class="line">
            <span>
              积分抵扣
              <span class="text-muted" v-if="calc">(最多 {{ calc.maxUsablePoints }} 分)</span>
            </span>
            <span>
              <el-input-number
                v-model="usePoints"
                :min="0"
                :max="calc?.maxUsablePoints ?? 0"
                :step="100"
                size="small"
                controls-position="right"
                style="width: 120px"
                :disabled="!member"
                @change="doPreview"
              />
              <span class="amount amount-negative" style="margin-left: 8px">
                -¥{{ fmt(calc?.pointDeductAmount ?? 0) }}
              </span>
            </span>
          </div>
          <el-divider style="margin: 8px 0" />
          <div class="line total">
            <span>应收</span>
            <span class="amount">¥{{ fmt(calc?.payableAmount ?? cartTotal) }}</span>
          </div>
        </div>

        <!-- 支付方式 -->
        <div class="pay-section">
          <div class="panel-subtitle">支付方式</div>
          <el-radio-group v-model="payMethod" class="pay-radios">
            <el-radio-button :value="1" :disabled="!member">
              储值余额
            </el-radio-button>
            <el-radio-button :value="2">现金</el-radio-button>
            <el-radio-button :value="3">线下扫码</el-radio-button>
            <el-radio-button :value="4">组合支付</el-radio-button>
          </el-radio-group>

          <div v-if="payMethod === 3" class="mt-8">
            <el-input v-model="outTradeNo" placeholder="扫码凭证号（选填）" size="small" />
          </div>

          <div v-if="payMethod === 4" class="mt-8 combine">
            <div class="combine-row">
              <span>余额支付</span>
              <el-input-number v-model="combineBalance" :min="0" :precision="2" size="small" />
            </div>
            <div class="combine-row">
              <span>现金支付</span>
              <el-input-number v-model="combineCash" :min="0" :precision="2" size="small" />
            </div>
            <div class="text-muted">
              合计 ¥{{ fmt(combineBalance + combineCash) }} / 应收 ¥{{ fmt(calc?.payableAmount ?? 0) }}
            </div>
          </div>

          <div v-if="payMethod === 2" class="mt-8">
            <el-input-number v-model="receivedAmount" :min="0" :precision="2" size="small" placeholder="实收" />
            <span class="text-muted" style="margin-left: 8px">
              找零 ¥{{ changeAmount }}
            </span>
          </div>
        </div>

        <!-- 操作 -->
        <div class="settle-actions">
          <el-button size="large" @click="holdOrder" :disabled="!cart.length">挂单</el-button>
          <el-button size="large" @click="clearCart" :disabled="!cart.length">清空</el-button>
          <el-button
            type="primary"
            size="large"
            style="flex: 1"
            :loading="settling"
            :disabled="!cart.length"
            @click="settle"
          >
            结算并打印 ¥{{ fmt(calc?.payableAmount ?? cartTotal) }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- 结算成功 -->
    <el-dialog v-model="ticketVisible" title="结算成功" width="420px">
      <div class="ticket" v-if="ticket">
        <div class="ticket-title">XX 会员中心 · 收银小票</div>
        <div class="ticket-line">订单号：{{ ticket.orderNo }}</div>
        <div class="ticket-line">会员：{{ member?.nickname || member?.realName || '散客' }}</div>
        <el-divider style="margin: 8px 0" />
        <div v-for="(it, i) in ticket.items" :key="i" class="ticket-line">
          {{ it.productName }} × {{ it.qty }} = ¥{{ fmt(it.totalAmount) }}
        </div>
        <el-divider style="margin: 8px 0" />
        <div class="ticket-line">原价：¥{{ fmt(ticket.totalAmount) }}</div>
        <div class="ticket-line">折扣：-¥{{ fmt(ticket.discountAmount) }}</div>
        <div class="ticket-line">券抵扣：-¥{{ fmt(ticket.couponAmount) }}</div>
        <div class="ticket-line">积分抵扣：-¥{{ fmt(ticket.pointDeductAmount) }}（{{ ticket.pointDeductPoints }} 分）</div>
        <div class="ticket-line big">实收：¥{{ fmt(ticket.paidAmount) }}</div>
        <div class="ticket-line" v-if="ticket.changeAmount">找零：¥{{ fmt(ticket.changeAmount) }}</div>
        <el-divider style="margin: 8px 0" />
        <div class="ticket-line">本单获得积分：{{ ticket.pointsGained }}</div>
        <div class="ticket-line" v-if="ticket.account">
          余额：¥{{ fmt(ticket.account.balance) }}（赠送 ¥{{ fmt(ticket.account.giftBalance) }}）
        </div>
        <div class="ticket-line">积分余额：{{ ticket.account?.points ?? 0 }}</div>
      </div>
      <template #footer>
        <el-button @click="ticketVisible = false">关闭</el-button>
        <el-button type="primary" @click="printTicket">打印小票</el-button>
        <el-button @click="newOrder">再来一单</el-button>
      </template>
    </el-dialog>

    <!-- 选券 -->
    <el-dialog v-model="couponVisible" title="选择优惠券" width="520px">
      <el-alert
        v-if="calc?.unavailableCoupons?.length"
        type="warning"
        :closable="false"
        class="mb-12"
        :title="`${calc.unavailableCoupons.length} 张券不可用：` + calc.unavailableCoupons.map((c) => `${c.tplName || c.couponNo}（${c.reason}）`).join('；')"
      />
      <el-empty v-if="!availableCoupons.length" description="该会员暂无可用券" />
      <el-checkbox-group v-model="selectedCoupons">
        <div v-for="c in availableCoupons" :key="c.couponNo" class="coupon-row">
          <el-checkbox :value="c.couponNo">
            <span class="coupon-name">{{ c.tplName }}</span>
            <span class="text-muted">
              满 {{ fmt(c.minAmount) }} 可用 · 至 {{ (c.validEnd || '').slice(0, 10) }}
            </span>
          </el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="couponVisible = false">取消</el-button>
        <el-button type="primary" @click="applyCoupons">确定</el-button>
      </template>
    </el-dialog>

    <!-- 交班汇总 -->
    <el-dialog v-model="shiftVisible" title="交班汇总" width="560px">
      <el-descriptions :column="2" border size="small" v-if="shiftData">
        <el-descriptions-item label="门店">{{ shiftData.storeName }}</el-descriptions-item>
        <el-descriptions-item label="日期">{{ shiftData.statDate }}</el-descriptions-item>
        <el-descriptions-item label="订单数">{{ shiftData.orderCount }}</el-descriptions-item>
        <el-descriptions-item label="收款合计">¥{{ fmt(shiftData.paidAmount) }}</el-descriptions-item>
        <el-descriptions-item label="充值金额">¥{{ fmt(shiftData.rechargeAmount) }}</el-descriptions-item>
        <el-descriptions-item label="退款金额">¥{{ fmt(shiftData.refundAmount) }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="shiftPayMethods" size="small" border class="mt-12">
        <el-table-column prop="payMethodText" label="支付方式" />
        <el-table-column prop="orderCount" label="笔数" width="90" />
        <el-table-column label="金额" align="right">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  memberApi,
  orderApi,
  productApi,
  type MemberVO,
  type Product,
  type SettleResult
} from '@/api'
import { http } from '@/api/request'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()

/* ---------- 会员 ---------- */
const memberKeyword = ref('')
const searching = ref(false)
const searchResults = ref<MemberVO[]>([])
const member = ref<MemberVO | null>(null)
const isGuest = ref(false)

/* ---------- 商品 ---------- */
const products = ref<Product[]>([])
const categories = ref<Array<{ id: number; cateName: string }>>([])
const goodsKeyword = ref('')
const activeCategory = ref('all')

const filteredProducts = computed(() => {
  let list = products.value
  if (activeCategory.value !== 'all') {
    list = list.filter((p) => String(p.categoryId) === activeCategory.value)
  }
  if (goodsKeyword.value) {
    const kw = goodsKeyword.value.toLowerCase()
    list = list.filter(
      (p) =>
        p.productName.toLowerCase().includes(kw) ||
        (p.productCode || '').toLowerCase().includes(kw)
    )
  }
  return list
})

/* ---------- 购物车与结算 ---------- */
interface CartItem {
  itemType: number
  productId?: number
  productName: string
  price: number
  qty: number
}

const cart = ref<CartItem[]>([])
const calc = ref<SettleResult | null>(null)
const usePoints = ref(0)
const selectedCoupons = ref<string[]>([])
interface CashierCoupon {
  couponNo: string
  tplName?: string
  couponType?: number
  faceValue?: number
  minAmount?: number
  validEnd?: string
  useRuleDesc?: string
}
const availableCoupons = ref<CashierCoupon[]>([])
const couponVisible = ref(false)
const payMethod = ref(1)
const receivedAmount = ref(0)
const outTradeNo = ref('')
const combineBalance = ref(0)
const combineCash = ref(0)
const settling = ref(false)
const ticketVisible = ref(false)
const ticket = ref<SettleResult | null>(null)
const shiftVisible = ref(false)
const shiftData = ref<{
  storeName?: string
  statDate?: string
  orderCount?: number
  paidAmount?: number
  rechargeAmount?: number
  refundAmount?: number
  payMethodDetail?: Array<{ payMethod?: number; payMethodText?: string; orderCount?: number; amount?: number }>
} | null>(null)
const shiftNo = ref('')
const holdOrders = ref<
  Array<{
    holdNo: string
    memberId?: number
    memberName?: string
    storeName?: string
    totalAmount: number
    itemCount: number
    createdAt: string
    expireTime?: string
    expired: boolean
    note?: string
    items: Array<Record<string, unknown>>
  }>
>([])

const cartTotal = computed(() => cart.value.reduce((s, i) => s + i.price * i.qty, 0))
const changeAmount = computed(() => {
  const payable = calc.value?.payableAmount ?? cartTotal.value
  return receivedAmount.value > payable ? (receivedAmount.value - payable).toFixed(2) : '0.00'
})
const shiftPayMethods = computed(
  () => (shiftData.value?.payMethodDetail as Array<Record<string, unknown>>) || []
)

function fmt(v?: number | string | null) {
  if (v === undefined || v === null) return '0.00'
  return Number(v).toFixed(2)
}

async function searchMember() {
  if (!memberKeyword.value.trim()) {
    ElMessage.warning('请输入检索关键词')
    return
  }
  searching.value = true
  try {
    searchResults.value = await memberApi.searchForCashier(memberKeyword.value.trim())
    if (!searchResults.value.length) {
      ElMessage.info('未找到会员，可直接散客消费或先建档')
    }
  } finally {
    searching.value = false
  }
}

function selectMember(m: MemberVO) {
  member.value = m
  searchResults.value = []
  isGuest.value = false
  doPreview()
}

function useGuestMode() {
  member.value = null
  isGuest.value = true
  usePoints.value = 0
  selectedCoupons.value = []
  payMethod.value = 2
  doPreview()
}

function addToCart(p: Product) {
  const exists = cart.value.find((c) => c.productId === p.id)
  if (exists) {
    exists.qty += 1
  } else {
    cart.value.push({
      itemType: p.productType === 2 ? 2 : 1,
      productId: p.id,
      productName: p.productName,
      price: Number(p.memberPrice || p.price),
      qty: 1
    })
  }
  recalc()
}

async function addFreeAmount() {
  const { value } = await ElMessageBox.prompt('请输入金额', '自由金额', {
    inputPattern: /^\d+(\.\d{1,2})?$/,
    inputErrorMessage: '请输入正确金额'
  })
  cart.value.push({
    itemType: 4,
    productName: '自由金额服务',
    price: Number(value),
    qty: 1
  })
  recalc()
}

function removeFromCart(idx: number) {
  cart.value.splice(idx, 1)
  recalc()
}

function clearCart() {
  cart.value = []
  usePoints.value = 0
  selectedCoupons.value = []
  calc.value = null
}

function recalc() {
  doPreview()
}

/** 试算（服务端计算，前端不自行算钱） */
async function doPreview() {
  if (!cart.value.length) {
    calc.value = null
    return
  }
  try {
    calc.value = await orderApi.preview({
      memberId: member.value?.id,
      orderType: 1,
      storeId: userStore.userInfo?.storeId,
      items: cart.value.map((c) => ({
        itemType: c.itemType,
        productId: c.productId,
        productName: c.productName,
        price: c.price,
        qty: c.qty
      })),
      couponNos: selectedCoupons.value,
      usePoints: usePoints.value
    })
    // 默认全额余额支付
    const payable = calc.value.payableAmount
    combineBalance.value = payable
    combineCash.value = 0
    receivedAmount.value = payable
  } catch {
    // 试算失败时保留上次结果，错误提示由拦截器给出
  }
}

async function openCouponPicker() {
  if (!member.value) return
  availableCoupons.value = (await http.get<CashierCoupon[]>(
    `/admin/marketing/coupons?memberId=${member.value.id}&status=1&pageNum=1&pageSize=50`
  ).catch(() => []) as unknown as CashierCoupon[]) || []
  if (!availableCoupons.value.length) {
    // 接口未实现时给出明确提示，不阻塞收银
    ElMessage.info('券列表接口未开放，可在会员详情中查看券包')
  }
  couponVisible.value = true
}

function applyCoupons() {
  couponVisible.value = false
  doPreview()
}

async function settle() {
  if (!member.value && !isGuest.value) {
    ElMessage.warning('请先选择会员，或点击"散客消费"')
    return
  }
  const payable = Number(calc.value?.payableAmount ?? cartTotal.value)

  // 组装支付明细
  const payments: Array<Record<string, unknown>> = []
  if (payMethod.value === 1) {
    if (!member.value) {
      ElMessage.warning('余额支付必须选择会员')
      return
    }
    payments.push({ payMethod: 1, amount: payable })
  } else if (payMethod.value === 2) {
    payments.push({ payMethod: 2, amount: payable })
  } else if (payMethod.value === 3) {
    payments.push({ payMethod: 3, amount: payable, outTradeNo: outTradeNo.value || undefined })
  } else {
    const total = combineBalance.value + combineCash.value
    if (Math.abs(total - payable) > 0.001) {
      ElMessage.warning(`组合支付合计 ¥${fmt(total)} 与应收 ¥${fmt(payable)} 不一致`)
      return
    }
    if (combineBalance.value > 0) {
      if (!member.value) {
        ElMessage.warning('余额支付必须选择会员')
        return
      }
      payments.push({ payMethod: 1, amount: combineBalance.value })
    }
    if (combineCash.value > 0) {
      payments.push({ payMethod: 2, amount: combineCash.value })
    }
  }

  settling.value = true
  try {
    const res = await orderApi.settle({
      memberId: member.value?.id,
      orderType: 1,
      storeId: userStore.userInfo?.storeId,
      items: cart.value.map((c) => ({
        itemType: c.itemType,
        productId: c.productId,
        productName: c.productName,
        price: c.price,
        qty: c.qty
      })),
      couponNos: selectedCoupons.value,
      usePoints: usePoints.value,
      payments,
      receivedAmount: payMethod.value === 2 ? receivedAmount.value : undefined,
      shiftNo: shiftNo.value || undefined
    })
    ticket.value = res
    ticketVisible.value = true
    if (!shiftNo.value) {
      shiftNo.value = 'SH' + new Date().toISOString().slice(0, 10).replace(/-/g, '') + '-01'
    }
  } finally {
    settling.value = false
  }
}

function newOrder() {
  ticketVisible.value = false
  clearCart()
  member.value = null
  isGuest.value = false
  memberKeyword.value = ''
  outTradeNo.value = ''
}

function printTicket() {
  window.print()
}

async function holdOrder() {
  if (!cart.value.length) return
  try {
    const res = await orderApi.hold({
      memberId: member.value?.id,
      memberName: member.value?.nickname || member.value?.realName,
      storeId: userStore.userInfo?.storeId,
      shiftNo: shiftNo.value || undefined,
      items: cart.value.map((c) => ({
        itemType: c.itemType,
        productId: c.productId,
        productName: c.productName,
        price: c.price,
        qty: c.qty
      }))
    })
    ElMessage.success(`已挂单 ${res.holdNo}（${res.itemCount} 项，保留 24 小时）`)
    clearCart()
    member.value = null
    isGuest.value = false
    await loadHolds()
  } catch (e) {
    // 错误提示已由请求层统一处理
  }
}

async function resumeHold(h: { holdNo: string }) {
  try {
    const res = await orderApi.resumeHold(h.holdNo)
    // 重建购物车
    cart.value = res.items.map((it) => ({
      itemType: it.itemType,
      productId: it.productId,
      productName: it.productName,
      price: Number(it.price),
      qty: Number(it.qty)
    }))
    // 恢复会员
    if (res.memberId) {
      const found = await memberApi.searchForCashier(String(res.memberId)).catch(() => [])
      const matched = found.find((m) => m.id === res.memberId)
      if (matched) {
        member.value = matched
        isGuest.value = false
      }
    } else {
      member.value = null
      isGuest.value = true
    }
    ElMessage.success(`已取单 ${res.holdNo}，共 ${res.itemCount} 项`)
    await loadHolds()
    await doPreview()
  } catch (e) {
    // 忽略错误
  }
}

async function cancelHold(h: { holdNo: string }) {
  const { value } = await ElMessageBox.prompt('请填写弃单原因', '弃单确认', {
    inputValue: '客户离开',
    inputValidator: (v) => (v && v.trim() ? true : '弃单原因必填')
  })
  await orderApi.cancelHold(h.holdNo, value)
  ElMessage.success('已弃单')
  await loadHolds()
}

async function loadHolds() {
  try {
    holdOrders.value = await orderApi.holdList({
      storeId: userStore.userInfo?.storeId,
      onlyMine: false
    })
  } catch {
    holdOrders.value = []
  }
}

async function openShiftSummary() {
  const storeId = userStore.userInfo?.storeId
  if (!storeId) {
    ElMessage.warning('当前账号未绑定门店')
    return
  }
  shiftData.value = (await orderApi.shiftSummary(storeId)) as Record<string, unknown>
  shiftVisible.value = true
}

onMounted(async () => {
  const [prod, cates] = await Promise.all([
    productApi.all().catch(() => [] as Product[]),
    productApi.categories().catch(() => [])
  ])
  products.value = prod
  categories.value = cates as Array<{ id: number; cateName: string }>
  if (!prod.length) {
    ElMessage.warning('暂无商品数据，可使用"自由金额"直接收银')
  }
  // 进入收银台时加载本店挂单
  await loadHolds()
})
</script>

<style scoped>
.cashier {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--page-bg);
}

.cashier-header {
  height: 52px;
  background: #1f2d3d;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  color: #fff;
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.cashier-body {
  flex: 1;
  display: grid;
  grid-template-columns: 300px 1fr 400px;
  gap: 10px;
  padding: 10px;
  overflow: hidden;
}

.panel {
  background: #fff;
  border-radius: var(--card-radius);
  padding: 12px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #1f2d3d;
}

.panel-subtitle {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin: 8px 0;
}

.search-results {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  max-height: 220px;
  overflow-y: auto;
}

.member-option {
  padding: 8px 10px;
  cursor: pointer;
  border-bottom: 1px solid #f2f6fc;
}

.member-option:hover {
  background: var(--brand-primary-light);
}

.option-main {
  font-weight: 600;
}

.option-sub {
  font-size: 12px;
  color: #909399;
}

.member-card {
  border: 1px solid var(--brand-primary);
  background: var(--brand-primary-light);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}

.member-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.member-meta {
  font-size: 12px;
  color: #909399;
  margin: 4px 0 8px;
}

.member-assets {
  display: flex;
  justify-content: space-between;
  text-align: center;
  margin-bottom: 8px;
}

.asset-label {
  font-size: 12px;
  color: #909399;
}

.asset-num {
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.hold-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8rpx 0;
  border-bottom: 1px solid #f5f7fa;
}

.hold-item:last-child {
  border-bottom: none;
}

.hold-list {
  max-height: 220px;
  overflow-y: auto;
}

.hold-main {
  flex: 1;
  min-width: 0;
}

.hold-title {
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.hold-sub {
  font-size: 12px;
  margin-top: 2px;
}

.hold-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.goods-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
  margin-top: 8px;
}

.goods-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
}

.goods-card:hover {
  border-color: var(--brand-primary);
  box-shadow: 0 2px 8px rgba(26, 115, 232, 0.15);
}

.goods-name {
  font-weight: 600;
  font-size: 14px;
}

.goods-price {
  color: var(--brand-danger);
  font-weight: 700;
  margin: 6px 0 2px;
  font-variant-numeric: tabular-nums;
}

.goods-unit {
  font-size: 12px;
  color: #c0c4cc;
}

.free-amount {
  border-style: dashed;
}

.cart {
  flex: 1;
  min-height: 120px;
  max-height: 260px;
  overflow-y: auto;
  border-bottom: 1px dashed #e4e7ed;
  margin-bottom: 8px;
}

.cart-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px solid #f5f7fa;
}

.cart-name {
  flex: 1;
  font-size: 13px;
}

.cart-price {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  margin-right: 6px;
}

.settle-lines {
  font-size: 13px;
}

.line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 0;
}

.line.total {
  font-size: 16px;
  font-weight: 700;
  color: var(--brand-danger);
}

.pay-section {
  margin-top: 10px;
}

.pay-radios {
  width: 100%;
  display: flex;
}

.pay-radios :deep(.el-radio-button) {
  flex: 1;
}

.pay-radios :deep(.el-radio-button__inner) {
  width: 100%;
  min-height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.combine-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.settle-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.settle-actions .el-button--large {
  min-height: 50px;
  font-size: 15px;
}

.coupon-row {
  padding: 6px 0;
  border-bottom: 1px solid #f5f7fa;
}

.coupon-name {
  font-weight: 600;
  margin-right: 8px;
}

.ticket {
  font-family: 'Courier New', monospace;
  font-size: 13px;
}

.ticket-title {
  text-align: center;
  font-weight: 700;
  margin-bottom: 8px;
}

.ticket-line {
  line-height: 1.9;
}

.ticket-line.big {
  font-size: 16px;
  font-weight: 700;
}
</style>
