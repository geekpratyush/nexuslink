package com.nexuslink.ui.metrics;

import com.nexuslink.core.di.AppContext;
import com.nexuslink.core.metrics.MetricsCollector;

/**
 * UI-side convenience for recording request metrics from any protocol view without each view
 * re-implementing the {@link AppContext} lookup. A no-op when no {@link MetricsCollector} is
 * registered (e.g. in unit tests), so call sites stay unconditional.
 */
public final class Metrics {

    private Metrics() {}

    /** The registered {@link MetricsCollector}, or {@code null} if none. */
    public static MetricsCollector collector() {
        return AppContext.get().isRegistered(MetricsCollector.class)
                ? AppContext.get().resolve(MetricsCollector.class) : null;
    }

    /** Records a completed request on {@code channel}; silently ignored when no collector is registered. */
    public static void record(String channel, long latencyMs, boolean success, long bytes) {
        MetricsCollector c = collector();
        if (c != null) c.record(channel, latencyMs, success, bytes);
    }
}
