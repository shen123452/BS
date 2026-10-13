export function attemptKey(patientId) { return `hospital.booking.${patientId}` }
export function makeAttempt(schedule, symptomDescription, patientName, triageResultId = null) {
  return { schedule, patientName, payload: { scheduleId: schedule.id, clientRequestId: crypto.randomUUID(), symptomDescription: symptomDescription.trim(), ...(triageResultId ? { triageResultId } : {}) } }
}
export function definiteFailure(error) { return error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429 }
