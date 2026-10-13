<script setup>
import { states } from '../lib/helpers.js'
defineProps({ status: String })
</script>
<template><span class="status-badge" :class="status?.toLowerCase()">{{ states[status] || status || '—' }}</span></template>
