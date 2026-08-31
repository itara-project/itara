package dev.itara.observability.logging;

import dev.itara.runtime.ExchangePattern;
import dev.itara.runtime.ItaraContext;
import dev.itara.runtime.ItaraObserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Observer that logs every Itara event.
 *
 * Lives in itara-observability-logging.itara — a separate jar loaded via
 * itara.lib.dir. Not loaded by default. Add this jar to the lib dir
 * to enable logging output.
 *
 * Useful for development, debugging, and as a reference implementation
 * for custom observers.
 *
 * Calculates two durations:
 *   - Total latency:    onCallSent → onReturnReceived (caller side)
 *   - Execution time:   onCallReceived → onReturnSent (callee side)
 *
 * Both are keyed by spanId. Network time = total latency - execution time,
 * but that calculation spans two JVMs and is left to higher-level tooling.
 */
public class LoggingObserver implements ItaraObserver {

    private static final Logger log =
            Logger.getLogger(LoggingObserver.class.getName());

    private final ConcurrentHashMap<String, Long> callSentTimes
            = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> callReceivedTimes
            = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> customSpanOpenedTimes
            = new ConcurrentHashMap<>();

    @Override
    public void onCallSent(ItaraContext ctx, String componentId, String methodName,
                           String transport, ExchangePattern exchangePattern, long timestamp) {
        if (ctx != null) callSentTimes.put(ctx.getItaraSpanId(), timestamp);
        log.info("[Itara/obs] CALL_SENT     "
                + componentId + "." + methodName
                + " transport=" + transport
                + trace(ctx));
    }

    @Override
    public void onCallReceived(ItaraContext ctx, String componentId, String methodName,
                               String transport, ExchangePattern exchangePattern, long timestamp) {
        if (ctx != null) callReceivedTimes.put(ctx.getItaraSpanId(), timestamp);
        log.info("[Itara/obs] CALL_RECEIVED "
                + componentId + "." + methodName
                + " transport=" + transport
                + trace(ctx));
    }

    @Override
    public void onReturnSent(ItaraContext ctx, String componentId,
                             String methodName, long timestamp, boolean error) {
        String execution = "";
        if (ctx != null) {
            Long start = callReceivedTimes.remove(ctx.getItaraSpanId());
            if (start != null) {
                execution = " execution=" + (timestamp - start) + "ns";
            }
        }
        log.info("[Itara/obs] RETURN_SENT   "
                + componentId + "." + methodName
                + trace(ctx)
                + execution
                + (error ? " ERROR" : ""));
    }

    @Override
    public void onReturnReceived(ItaraContext ctx, String componentId,
                                 String methodName, long timestamp, boolean error) {
        String latency = "";
        if (ctx != null) {
            Long start = callSentTimes.remove(ctx.getItaraSpanId());
            if (start != null) {
                latency = " latency=" + (timestamp - start) + "ns";
            }
        }
        log.info("[Itara/obs] RETURN_RECEIVED   "
                + componentId + "." + methodName
                + trace(ctx)
                + latency
                + (error ? " ERROR" : ""));
    }

    @Override
    public void onCustomSpan(ItaraContext ctx, String name,
                             Map<String, String> attributes, long timestamp) {
        if (ctx != null) customSpanOpenedTimes.put(ctx.getItaraSpanId(), timestamp);
        log.info("[Itara/obs] CUSTOM_SPAN   " + name
                + (attributes.isEmpty() ? "" : " " + attributes)
                + trace(ctx));
    }

    @Override
    public void onCustomSpanClosed(ItaraContext ctx, String name,
                                   long timestamp, boolean error) {
        String duration = "";
        if (ctx != null) {
            Long start = customSpanOpenedTimes.remove(ctx.getItaraSpanId());
            if (start != null) {
                duration = " duration=" + (timestamp - start) + "ns";
            }
        }
        log.info("[Itara/obs] CUSTOM_SPAN_CLOSED " + name
                + trace(ctx)
                + duration
                + (error ? " ERROR" : ""));
    }

    private String trace(ItaraContext ctx) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder()
                .append(" traceId=").append(ctx.getItaraTraceId())
                .append(" spanId=").append(ctx.getItaraSpanId());
        if (ctx.getItaraParentSpanId() != null) {
            sb.append(" parentSpanId=").append(ctx.getItaraParentSpanId());
        }
        sb.append(" edgePath=").append(ctx.getEdgePath());
        return sb.toString();
    }
}
