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

const replaceForm = ref({ partId: null, turbineId: null, workOrderId: null, qty: 1, operator: '', note: '' })
const form = ref(emptyForm())

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

async function changeStock (p, delta) {
  try {
    await api.part.stock(p.id, delta)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitReplace () {
  try {
    await api.part.replace(replaceForm.value)
    ElMessage.success('更换登记已完成')
    replaceForm.value = { partId: null, turbineId: null, workOrderId: null, qty: 1, operator: '', note: '' }
    await load()
  } catch (e) {
    ElMessage.error(e.message)
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
        <span class="panel-sub">数量不得超过当前库存；出库后低于安全库存会自动打预警标记</span>
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
          <el-select v-model="replaceForm.workOrderId" clearable style="width: 200px">
            <el-option v-for="o in orders" :key="o.id" :value="o.id" :label="`${o.orderNo} ${o.title}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量"><el-input-number v-model="replaceForm.qty" :min="1" :max="200" /></el-form-item>
        <el-form-item label="更换人"><el-input v-model="replaceForm.operator" style="width: 120px" /></el-form-item>
        <el-form-item><el-button type="primary" @click="submitReplace">登记更换</el-button></el-form-item>
      </el-form>
    </div>

    <div class="panel">
      <div class="panel-head"><span class="panel-title">更换记录</span></div>
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
