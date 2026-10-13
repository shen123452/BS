<script setup>defineProps({ error: Object })</script>
<template><div v-if="error" class="error-panel" role="alert"><strong>{{ error.message }}</strong><slot /><el-button v-if="$attrs.onRetry" size="small" @click="$emit('retry')">重新加载</el-button><details v-if="error.traceId"><summary>查看问题编号</summary><code>{{ error.traceId }}</code></details></div></template>
