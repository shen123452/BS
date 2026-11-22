import { createApp, defineAsyncComponent } from 'vue'
import { createPinia } from 'pinia'
import { ElLoading } from 'element-plus/es/components/loading/index.mjs'
import ElDescriptionsItem from 'element-plus/es/components/descriptions/src/description-item.mjs'
import 'element-plus/dist/index.css'
import './styles/global.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
const components = {
  ElAlert: () => import('element-plus/es/components/alert/index.mjs').then(m => m.ElAlert),
  ElButton: () => import('element-plus/es/components/button/index.mjs').then(m => m.ElButton),
  ElCol: () => import('element-plus/es/components/col/index.mjs').then(m => m.ElCol),
  ElDatePicker: () => import('element-plus/es/components/date-picker/index.mjs').then(m => m.ElDatePicker),
  ElDescriptions: () => import('element-plus/es/components/descriptions/index.mjs').then(m => m.ElDescriptions),
  ElDialog: () => import('element-plus/es/components/dialog/index.mjs').then(m => m.ElDialog),
  ElDrawer: () => import('element-plus/es/components/drawer/index.mjs').then(m => m.ElDrawer),
  ElEmpty: () => import('element-plus/es/components/empty/index.mjs').then(m => m.ElEmpty),
  ElForm: () => import('element-plus/es/components/form/index.mjs').then(m => m.ElForm),
  ElFormItem: () => import('element-plus/es/components/form/index.mjs').then(m => m.ElFormItem),
  ElIcon: () => import('element-plus/es/components/icon/index.mjs').then(m => m.ElIcon),
  ElInput: () => import('element-plus/es/components/input/index.mjs').then(m => m.ElInput),
  ElSelect: () => import('element-plus/es/components/select/index.mjs').then(m => m.ElSelect),
  ElOption: () => import('element-plus/es/components/select/index.mjs').then(m => m.ElOption),
  ElPagination: () => import('element-plus/es/components/pagination/index.mjs').then(m => m.ElPagination),
  ElRadioButton: () => import('element-plus/es/components/radio/index.mjs').then(m => m.ElRadioButton),
  ElRadioGroup: () => import('element-plus/es/components/radio/index.mjs').then(m => m.ElRadioGroup),
  ElRow: () => import('element-plus/es/components/row/index.mjs').then(m => m.ElRow),
  ElTable: () => import('element-plus/es/components/table/index.mjs').then(m => m.ElTable),
  ElTableColumn: () => import('element-plus/es/components/table/index.mjs').then(m => m.ElTableColumn),
  ElTooltip: () => import('element-plus/es/components/tooltip/index.mjs').then(m => m.ElTooltip),
}
for (const [name, loader] of Object.entries(components)) app.component(name, defineAsyncComponent(loader))
// ElDescriptions reads child vnode names before mounting; its item must retain its identity.
app.component('ElDescriptionsItem', ElDescriptionsItem)
app.use(ElLoading)
app.mount('#app')
