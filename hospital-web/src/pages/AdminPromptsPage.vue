<script setup>
import { onMounted, ref } from 'vue'
import { get, patch, post } from '../lib/http.js'
import ErrorPanel from '../components/ErrorPanel.vue'

const templates = ref([]), current = ref(null), draft = ref(''), loading = ref(false), saving = ref(false)
const error = ref(null), actionError = ref(null), note = ref(''), statusText = ref('')
const rollbackVisible = ref(false), rollbackTarget = ref(null)

async function loadList() {
  error.value = null
  try { templates.value = await get('/admin/prompt-templates'); if (!current.value && templates.value.length) await open(templates.value[0].code) }
  catch (e) { error.value = e }
}
async function open(code) {
  loading.value = true; actionError.value = null; statusText.value = ''; note.value = ''
  try {
    current.value = await get(`/admin/prompt-templates/${code}`)
    draft.value = current.value.draftContent
  } catch (e) { actionError.value = e } finally { loading.value = false }
}
async function saveDraft() {
  if (saving.value || !current.value) return
  saving.value = true; actionError.value = null; statusText.value = ''
  try { await patch(`/admin/prompt-templates/${current.value.code}/draft`, { content: draft.value }); statusText.value = '草稿已保存，尚未发布。' }
  catch (e) { actionError.value = e } finally { saving.value = false }
}
async function publish() {
  if (saving.value || !current.value) return
  saving.value = true; actionError.value = null
  try {
    await post(`/admin/prompt-templates/${current.value.code}/publish`, { changeNote: note.value })
    statusText.value = '已发布新修订，下一次新问诊/新导诊生效；运行中的任务沿用开始时版本。'
    await Promise.all([open(current.value.code), loadList()])
  } catch (e) { actionError.value = e } finally { saving.value = false }
}
async function rollback() {
  if (!rollbackTarget.value) return
  saving.value = true; actionError.value = null
  try {
    await post(`/admin/prompt-templates/${current.value.code}/rollback`, { targetRevision: rollbackTarget.value })
    rollbackVisible.value = false; statusText.value = `已回滚到修订 ${rollbackTarget.value}，草稿同步更新。`
    await open(current.value.code)
  } catch (e) { actionError.value = e } finally { saving.value = false }
}
onMounted(loadList)
</script>

<template>
  <div class="page-heading"><div><h1 tabindex="-1">Prompt 模板</h1><p>草稿可编辑；发布生成不可变修订。回滚只切换当前指针，不删除历史。运行固定开始时的修订。</p></div><el-button :loading="loading" @click="loadList">刷新</el-button></div>
  <ErrorPanel :error="error" @retry="loadList" /><ErrorPanel :error="actionError" />
  <div class="prompt-layout">
    <section class="panel prompt-list">
      <h2>模板</h2>
      <ul>
        <li v-for="t in templates" :key="t.code">
          <button type="button" :class="{ current: current?.code === t.code }" @click="open(t.code)">
            <strong>{{ t.name }}</strong>
            <small>{{ t.code }} · {{ t.currentRevision ? `修订 ${t.currentRevision}` : '未发布' }}</small>
          </button>
        </li>
      </ul>
    </section>
    <section v-if="current" class="panel prompt-editor" :aria-busy="saving">
      <div class="section-head"><h2>{{ current.name }}</h2><span class="muted">当前修订 {{ current.currentRevision || '—' }}</span></div>
      <label for="prompt-draft">草稿内容</label>
      <el-input id="prompt-draft" v-model="draft" type="textarea" :rows="14" :maxlength="12000" show-word-limit />
      <div class="form-actions">
        <el-button :loading="saving" @click="saveDraft">保存草稿</el-button>
        <label for="prompt-note" class="sr-only">发布说明</label>
        <el-input id="prompt-note" v-model="note" :maxlength="500" placeholder="发布说明（可留空）" />
        <el-button type="primary" :loading="saving" @click="publish">发布新修订</el-button>
        <el-button :disabled="!current.revisions?.length" @click="rollbackVisible = true">回滚…</el-button>
      </div>
      <p v-if="statusText" role="status">{{ statusText }}</p>
      <h2>修订历史</h2>
      <div class="table-container">
        <el-table :data="current.revisions || []" empty-text="尚未发布任何修订">
          <el-table-column prop="revision" label="修订" width="80" />
          <el-table-column prop="changeNote" label="说明" min-width="200" />
          <el-table-column prop="length" label="字符数" width="100" />
          <el-table-column prop="publishedAt" label="发布时间" min-width="170" />
          <el-table-column label="内容哈希" min-width="180"><template #default="{row}"><code class="hash">{{ row.contentHash.slice(0, 16) }}…</code></template></el-table-column>
        </el-table>
      </div>
      <p class="muted">导诊模板必须保留 departmentId、riskLevel、evidenceChunkIds 契约字段；预问诊模板必须保留拨打120/急诊安全引导，否则发布会被拒绝。</p>
    </section>
  </div>

  <el-dialog v-model="rollbackVisible" title="回滚到历史修订" width="480px">
    <label for="rollback-target">目标修订</label>
    <el-select id="rollback-target" v-model="rollbackTarget" placeholder="选择历史修订">
      <el-option v-for="r in current?.revisions || []" :key="r.revision" :value="r.revision" :label="`修订 ${r.revision} · ${r.changeNote || '无说明'}`" />
    </el-select>
    <p class="muted">回滚不删除任何修订；下一次新请求使用回滚后的内容。</p>
    <div class="form-actions"><el-button type="primary" :disabled="!rollbackTarget" :loading="saving" @click="rollback">确认回滚</el-button></div>
  </el-dialog>
</template>

<style scoped>
.prompt-layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 16px; align-items: start; }
.prompt-list ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.prompt-list button { width: 100%; text-align: left; padding: 10px 12px; border: 1px solid var(--color-border, #d7dfea); border-radius: 8px; background: #fff; cursor: pointer; display: grid; gap: 4px; }
.prompt-list button.current { border-color: #1d4ed8; background: #eff6ff; }
.prompt-list small { color: #475569; }
.form-actions { display: flex; gap: 12px; align-items: center; margin: 12px 0; flex-wrap: wrap; }
.form-actions .el-input { max-width: 280px; }
.hash { font-size: 12px; }
@media (max-width: 768px) { .prompt-layout { grid-template-columns: 1fr; } }
</style>
