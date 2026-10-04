<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import router from '../router'

const props = defineProps({
  farmId: { type: Number, default: null },
  farmName: { type: String, default: '' }
})

const CATEGORIES = [
  { value: 'BLADE', label: '叶片' },
  { value: 'GEARBOX', label: '齿轮箱' },
  { value: 'GENERATOR', label: '发电机' },
  { value: 'PITCH', label: '变桨系统' },
  { value: 'ELECTRIC', label: '电气' },
  { value: 'HYDRAULIC', label: '液压' },
  { value: 'FASTENER', label: '紧固件' },
  { value: 'LUBE', label: '润滑油脂' }
]

const parts = ref([])
const turbines = ref([])
const orders = ref([])
const replacements = ref([])
const category = ref('')
const step = ref(1)
const dialog = ref(false)
const replacing = ref(false)

const replaceForm = ref(emptyReplaceForm())
const form = ref(emptyForm())

// 每次登记生成一个幂等键：网络重试 / 重复点击沿用同一个，服务端保证同一次更换只留一条履历
function newRequestId () {
  return 'rp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}

function emptyReplaceForm () {
  return { partId: null, turbineId: null, workOrderId: null, qty: 1, operator: '', note: '', requestId: newRequestId() }
}

function emptyForm () {
  return {
    partCode: 'SP-0000',
    partName: '',
    spec: '',
    category: 'ELECTRIC',
    unit: '件',
    stockQty: 10,
    safetyStock: 4,
    location: '1 号库 A 区',
    unitPrice: 1000,
    supplier: '',
    note: ''
  }
}

const lowCount = computed(() => parts.value.filter((p) => p.lowStock).length)
const warnCount = computed(() => replacements.value.filter((r) => r.warningFlag).length)
const reviewCount = computed(() => replacements.value.filter((r) => r.reviewFlag).length)

// 机位与工单同时选择时的冲突提示（仅提示，权威校验在服务端；任何冲突都会被拒绝登记）
const replaceConflict = computed(() => {
  const o = orders.value.find((x) => x.id === replaceForm.value.workOrderId)
  if (!o || !replaceForm.value.turbineId) return ''
  if (o.turbineId === replaceForm.value.turbineId) return ''
  const t = turbines.value.find((x) => x.id === replaceForm.value.turbineId)
  const unitText = t ? `${t.unitCode} ${t.unitName}` : `id=${replaceForm.value.turbineId}`
  return `工单 ${o.orderNo} 属于机位 ${o.unitCode}，与所选机位 ${unitText} 不一致：` +
    `请把机位修正为 ${o.unitCode}，或改选 ${t ? t.unitCode : ''} 名下的工单后再登记`
})

function barMax (p) {
  return Math.max(Number(p.safetyStock || 0) * 2, Number(p.stockQty || 0) * 1.15, 1)
}
function fillPct (p) {
  return Math.min(100, (Number(p.stockQty || 0) / barMax(p)) * 100)
}
function markPct (p) {
  return Math.min(100, (Number(p.safetyStock || 0) / barMax(p)) * 100)
}

async function load () {
  parts.value = await api.part.vo(category.value || null)
  turbines.value = await api.turbine.grid(props.farmId)
  orders.value = await api.workorder.kanban(props.farmId)
  replacements.value = await api.part.replacementVOs()
}

// 只选工单时由工单补齐机位（与服务端规则一致，减少重复选择）；
// 已选机位不覆盖——机位与工单冲突必须拒绝，不能静默改值
function onReplaceOrderChange (id) {
  if (!id) return
  const o = orders.value.find((x) => x.id === id)
  if (o && !replaceForm.value.turbineId) {
    replaceForm.value.turbineId = o.turbineId
  }
}

async function changeStock (p, delta) {
  try {
    await api.part.stock(p.id, delta)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitReplace () {
  if (replacing.value) return
  replacing.value = true
  try {
    await api.part.replace(replaceForm.value)
    ElMessage.success('更换登记已完成')
    replaceForm.value = emptyReplaceForm()
    await load()
  } catch (e) {
    // 失败（含并发被拦）：重新拉取真实剩余量与已生效履历，表单选择保留便于修正后继续
    ElMessage.error(e.message)
    try {
      await load()
    } catch {
      // 刷新失败不掩盖原始错误提示
    }
  } finally {
    replacing.value = false
  }
}

async function submit () {
  try {
    await api.part.create(form.value)
    ElMessage.success('备件已创建')
    dialog.value = false
    form.value = emptyForm()
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removePart (p) {
  await ElMessageBox.confirm(`确认删除备件 ${p.partCode}？`, '删除备件', { type: 'warning' })
  try {
    await api.part.remove(p.id)
    ElMessage.success('备件已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
watch(() => props.farmId, load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">备件与更换</span>
      <span class="view-tip">库存条 + 步进器（−/+ 改数量），红标记是安全库存线；低于安全库存仍可出库但会打预警标记</span>
      <el-button size="small" style="margin-left: auto" @click="router.push('/turbine')">← 机位总览</el-button>
    </div>

    <div class="chip-bar">
      <span class="chip on">{{ farmName || '全部风场' }}</span>
      <span class="chip">备件 {{ parts.length }} 种</span>
      <span class="chip" :class="{ warn: lowCount > 0 }">低库存 {{ lowCount }}</span>
      <span class="chip" :class="{ bad: warnCount > 0 }">预警更换 {{ warnCount }}</span>
      <el-select v-model="category" size="small" clearable placeholder="全部分类" style="width: 140px" @change="load">
        <el-option v-for="c in CATEGORIES" :key="c.value" :value="c.value" :label="c.label" />
      </el-select>
      <el-select v-model="step" size="small" style="width: 92px">
        <el-option :value="1" label="步长 1" />
        <el-option :value="5" label="步长 5" />
        <el-option :value="10" label="步长 10" />
      </el-select>
      <el-button size="small" type="primary" style="margin-left: auto" @click="dialog = true">新增备件</el-button>
    </div>

    <!-- ---------- 库存条 + 步进器 ---------- -->
    <div class="panel">
      <div class="panel-head"><span class="panel-title">库存（步进器直接增减）</span></div>

      <div v-for="p in parts" :key="p.id" class="part-row" :class="{ low: p.lowStock }">
        <div>
          <div class="part-name">{{ p.partCode }} {{ p.partName }}</div>
          <div class="part-meta">
            {{ p.categoryName }} · {{ p.spec }} · {{ p.location }} · 单价 {{ Number(p.unitPrice).toLocaleString() }} 元
          </div>
        </div>

        <div>
          <div class="stock-bar" :class="{ low: p.lowStock }">
            <i :style="{ width: fillPct(p) + '%' }"></i>
            <span class="sb-mark" :style="{ left: markPct(p) + '%' }"></span>
            <span class="sb-text">库存 {{ p.stockQty }} {{ p.unit }} / 安全 {{ p.safetyStock }}</span>
          </div>
          <el-tag size="small" :type="p.lowStock ? 'warning' : 'success'" style="margin-top: 5px">
            {{ p.lowStock ? '低于安全库存' : '库存充足' }}
          </el-tag>
        </div>

        <div style="display: flex; align-items: center; gap: 8px">
          <div class="stepper">
            <button @click="changeStock(p, -step)">−</button>
            <span class="sv">{{ p.stockQty }}</span>
            <button @click="changeStock(p, step)">+</button>
          </div>
          <el-button link type="danger" size="small" @click="removePart(p)">删除</el-button>
        </div>
      </div>

      <div class="empty" v-if="!parts.length">暂无备件</div>
    </div>

    <!-- ---------- 更换登记 ---------- -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">更换登记</span>
        <span class="panel-sub">数量不得超过当前库存；出库后低于安全库存会自动打预警标记；只选工单时自动带出工单所属机位；机位与工单不一致会被拒绝并提示修正</span>
      </div>
      <el-form :inline="true" size="small">
        <el-form-item label="备件">
          <el-select v-model="replaceForm.partId" style="width: 250px">
            <el-option v-for="p in parts" :key="p.id" :value="p.id"
              :label="`${p.partCode} ${p.partName}（库存 ${p.stockQty}${p.unit}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="机位">
          <el-select v-model="replaceForm.turbineId" clearable style="width: 170px">
            <el-option v-for="t in turbines" :key="t.id" :value="t.id" :label="`${t.unitCode} ${t.unitName}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联工单">
          <el-select v-model="replaceForm.workOrderId" clearable style="width: 260px" @change="onReplaceOrderChange">
            <el-option v-for="o in orders" :key="o.id" :value="o.id" :disabled="o.status === 'CLOSED'"
              :label="`${o.orderNo} ${o.title}（${o.unitCode} · ${o.statusName}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量"><el-input-number v-model="replaceForm.qty" :min="1" :max="200" /></el-form-item>
        <el-form-item label="更换人"><el-input v-model="replaceForm.operator" style="width: 120px" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="replacing" @click="submitReplace">登记更换</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="replaceConflict" :title="replaceConflict" type="error" :closable="false" show-icon
        style="margin-top: 4px" />
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">更换记录</span>
        <span class="panel-sub" v-if="reviewCount > 0">有 {{ reviewCount }} 条历史记录机位与工单不一致，标记为「待核对」，请人工核实（系统未做改动）</span>
      </div>
      <el-table :data="replacements" size="small" max-height="300">
        <el-table-column prop="partCode" label="备件编号" width="130" />
        <el-table-column prop="partName" label="名称" min-width="150" />
        <el-table-column prop="unitCode" label="机位" width="80" />
        <el-table-column prop="orderNo" label="工单" width="130" />
        <el-table-column prop="qty" label="数量" width="70" />
        <el-table-column prop="replaceTime" label="更换时间" width="150" />
        <el-table-column prop="operator" label="更换人" width="130" />
        <el-table-column label="预警" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.warningFlag ? 'danger' : 'success'">
              {{ row.warningFlag ? '预警' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="核对" width="90">
          <template #default="{ row }">
            <el-tooltip v-if="row.reviewFlag" :content="row.reviewNote" placement="top">
              <el-tag size="small" type="danger">待核对</el-tag>
            </el-tooltip>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="stockAfter" label="剩余" width="80" />
        <el-table-column prop="note" label="说明" min-width="200" show-overflow-tooltip />
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新增备件" width="560px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="备件编号"><el-input v-model="form.partCode" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.partName" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="form.spec" /></el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category">
            <el-option v-for="c in CATEGORIES" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit" style="width: 100px" /></el-form-item>
        <el-form-item label="库存"><el-input-number v-model="form.stockQty" :min="0" /></el-form-item>
        <el-form-item label="安全库存"><el-input-number v-model="form.safetyStock" :min="0" /></el-form-item>
        <el-form-item label="位置"><el-input v-model="form.location" /></el-form-item>
        <el-form-item label="单价"><el-input-number v-model="form.unitPrice" :min="0" /></el-form-item>
        <el-form-item label="供应商"><el-input v-model="form.supplier" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
