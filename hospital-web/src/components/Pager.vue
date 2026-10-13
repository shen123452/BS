<script setup>defineProps({ page: Number, total: Number, pageSize: { type: Number, default: 20 } }); defineEmits(['change'])</script>
<template><div v-if="total > pageSize" class="pager"><span>共 {{ total }} 条</span><el-pagination :current-page="page" :total="total" :page-size="pageSize" layout="prev, pager, next" @current-change="$emit('change', $event)" /></div></template>
