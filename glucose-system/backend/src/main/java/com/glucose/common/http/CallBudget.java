package com.glucose.common.http;

import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import java.time.Duration;

/** A request deadline shared by sequential embedding and chat calls on this thread. */
public final class CallBudget implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Long previous;
    private CallBudget(Duration duration) {
        previous = DEADLINE.get();
        long end = System.nanoTime() + duration.toNanos();
        DEADLINE.set(previous == null ? end : Math.min(previous, end));
    }
    public static CallBudget start(Duration duration) { return new CallBudget(duration); }
    public static int timeout(int configuredMs) {
        Long deadline = DEADLINE.get();
        long remaining = deadline == null ? configuredMs : (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "回答时间预算已用尽");
        return (int) Math.max(1, Math.min(Math.max(1, configuredMs), remaining));
    }
    @Override public void close() { if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous); }
}
