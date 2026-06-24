package io.itara.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ItaraContext")
public class ItaraContextTest {

    @AfterEach
    void clearStack() {
        // Ensure no context leaks between tests
        ItaraContext.clear();
    }

    // ── Factory methods ───────────────────────────────────────────────────

    @Nested
    @DisplayName("newRoot")
    class NewRoot {

        @Test
        @DisplayName("generates non-null traceId, spanId, and requestId")
        void generatesIds() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            assertAll(
                    () -> assertNotNull(ctx.getItaraTraceId()),
                    () -> assertNotNull(ctx.getItaraSpanId()),
                    () -> assertNotNull(ctx.getRequestId())
            );
        }

        @Test
        @DisplayName("traceId is 32 hex chars")
        void traceIdIs32HexChars() {
            String traceId = ItaraContext.newRoot("gateway").getItaraTraceId();
            assertEquals(32, traceId.length());
            assertTrue(traceId.matches("[0-9a-f]{32}"),
                    "traceId must be 32 lowercase hex chars");
        }

        @Test
        @DisplayName("spanId is 16 hex chars")
        void spanIdIs16HexChars() {
            String spanId = ItaraContext.newRoot("gateway").getItaraSpanId();
            assertEquals(16, spanId.length());
            assertTrue(spanId.matches("[0-9a-f]{16}"),
                    "spanId must be 16 lowercase hex chars");
        }

        @Test
        @DisplayName("parentSpanId is null for root context")
        void parentSpanIdIsNull() {
            assertNull(ItaraContext.newRoot("gateway").getItaraParentSpanId());
        }

        @Test
        @DisplayName("correlationId is null when not provided")
        void correlationIdNullWhenAbsent() {
            assertNull(ItaraContext.newRoot("gateway").getCorrelationId());
        }

        @Test
        @DisplayName("correlationId is set when provided")
        void correlationIdSetWhenProvided() {
            assertEquals("order-123",
                    ItaraContext.newRoot("gateway", "order-123").getCorrelationId());
        }

        @Test
        @DisplayName("sourceNode is set correctly")
        void sourceNodeSet() {
            assertEquals("gateway", ItaraContext.newRoot("gateway").getSourceNode());
        }

        @Test
        @DisplayName("edgePath is empty")
        void edgePathEmpty() {
            assertTrue(ItaraContext.newRoot("gateway").getEdgePath().isEmpty());
        }

        @Test
        @DisplayName("two root contexts have different traceIds")
        void twoRootsHaveDifferentTraceIds() {
            assertNotEquals(
                    ItaraContext.newRoot("gateway").getItaraTraceId(),
                    ItaraContext.newRoot("gateway").getItaraTraceId());
        }

        @Test
        @DisplayName("two root contexts have different spanIds")
        void twoRootsHaveDifferentSpanIds() {
            assertNotEquals(
                    ItaraContext.newRoot("gateway").getItaraSpanId(),
                    ItaraContext.newRoot("gateway").getItaraSpanId());
        }
    }

    // ── Span hierarchy ────────────────────────────────────────────────────

    @Nested
    @DisplayName("newChildSpan")
    class NewChildSpan {

        @Test
        @DisplayName("inherits traceId from parent")
        void inheritsTraceId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            assertEquals(root.getItaraTraceId(), child.getItaraTraceId());
        }

        @Test
        @DisplayName("inherits requestId from parent")
        void inheritsRequestId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            assertEquals(root.getRequestId(), child.getRequestId());
        }

        @Test
        @DisplayName("generates a new spanId")
        void generatesNewSpanId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            assertNotEquals(root.getItaraSpanId(), child.getItaraSpanId());
        }

        @Test
        @DisplayName("parentSpanId is set to parent's spanId")
        void parentSpanIdIsParentSpanId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            assertEquals(root.getItaraSpanId(), child.getItaraParentSpanId());
        }

        @Test
        @DisplayName("extends edgePath with the next component")
        void extendsEdgePath() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            assertEquals(List.of("calculator"), child.getEdgePath());
        }

        @Test
        @DisplayName("edgePath grows correctly across multiple hops")
        void edgePathGrowsAcrossHops() {
            ItaraContext root     = ItaraContext.newRoot("gateway");
            ItaraContext hop1     = root.newChildSpan("order-service");
            ItaraContext hop2     = hop1.newChildSpan("calculator");
            assertEquals(List.of("order-service", "calculator"), hop2.getEdgePath());
        }

        @Test
        @DisplayName("inherits correlationId from parent")
        void inheritsCorrelationId() {
            ItaraContext root = ItaraContext.newRoot("gateway", "corr-123");
            ItaraContext child = root.newChildSpan("calculator");
            assertEquals("corr-123", child.getCorrelationId());
        }

        @Test
        @DisplayName("sourceNode is inherited unchanged")
        void sourceNodeInherited() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            assertEquals("gateway", child.getSourceNode());
        }
    }

    @Nested
    @DisplayName("newCallerSpan")
    class NewCallerSpan {

        @Test
        @DisplayName("inherits traceId from parent")
        void inheritsTraceId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext caller = root.newCallerSpan();
            assertEquals(root.getItaraTraceId(), caller.getItaraTraceId());
        }

        @Test
        @DisplayName("generates a new spanId")
        void generatesNewSpanId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext caller = root.newCallerSpan();
            assertNotEquals(root.getItaraSpanId(), caller.getItaraSpanId());
        }

        @Test
        @DisplayName("parentSpanId is set to parent's spanId")
        void parentSpanIdSet() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext caller = root.newCallerSpan();
            assertEquals(root.getItaraSpanId(), caller.getItaraParentSpanId());
        }

        @Test
        @DisplayName("edgePath is not extended — path only grows on arrival")
        void edgePathNotExtended() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext caller = root.newCallerSpan();
            assertEquals(root.getEdgePath(), caller.getEdgePath());
        }
    }

    @Nested
    @DisplayName("newCustomSpan")
    class NewCustomSpan {

        @Test
        @DisplayName("inherits traceId from parent")
        void inheritsTraceId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext custom = root.newCustomSpan();
            assertEquals(root.getItaraTraceId(), custom.getItaraTraceId());
        }

        @Test
        @DisplayName("generates a new spanId")
        void generatesNewSpanId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext custom = root.newCustomSpan();
            assertNotEquals(root.getItaraSpanId(), custom.getItaraSpanId());
        }

        @Test
        @DisplayName("parentSpanId is set to parent's spanId")
        void parentSpanIdSet() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext custom = root.newCustomSpan();
            assertEquals(root.getItaraSpanId(), custom.getItaraParentSpanId());
        }

        @Test
        @DisplayName("edgePath is not extended — custom spans are not topology hops")
        void edgePathNotExtended() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext child = root.newChildSpan("calculator");
            ItaraContext custom = child.newCustomSpan();
            assertEquals(child.getEdgePath(), custom.getEdgePath());
        }

        @Test
        @DisplayName("sourceNode is inherited unchanged")
        void sourceNodeInherited() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            ItaraContext custom = root.newCustomSpan();
            assertEquals("gateway", custom.getSourceNode());
        }

        @Test
        @DisplayName("two custom spans from same parent have different spanIds")
        void twoCustomSpansHaveDifferentSpanIds() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            assertNotEquals(
                    root.newCustomSpan().getItaraSpanId(),
                    root.newCustomSpan().getItaraSpanId());
        }

        @Test
        @DisplayName("two custom spans from same parent share the same parentSpanId")
        void twoCustomSpansShareParentSpanId() {
            ItaraContext root = ItaraContext.newRoot("gateway");
            assertEquals(
                    root.newCustomSpan().getItaraParentSpanId(),
                    root.newCustomSpan().getItaraParentSpanId());
        }
    }

    // ── Thread-local stack ────────────────────────────────────────────────

    @Nested
    @DisplayName("stack — push, pop, current")
    class Stack {

        @Test
        @DisplayName("current() returns null when stack is empty")
        void currentNullWhenEmpty() {
            assertNull(ItaraContext.current());
        }

        @Test
        @DisplayName("current() returns the most recently pushed context")
        void currentReturnsMostRecent() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            ItaraContext.push(ctx);
            assertSame(ctx, ItaraContext.current());
        }

        @Test
        @DisplayName("pop() removes and returns the top of the stack")
        void popRemovesTop() {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            ItaraContext.push(ctx);
            ItaraContext popped = ItaraContext.pop();
            assertSame(ctx, popped);
            assertNull(ItaraContext.current());
        }

        @Test
        @DisplayName("stack is LIFO — inner push shadows outer")
        void stackIsLifo() {
            ItaraContext outer = ItaraContext.newRoot("gateway");
            ItaraContext inner = outer.newCallerSpan();
            ItaraContext.push(outer);
            ItaraContext.push(inner);

            assertSame(inner, ItaraContext.current());
            ItaraContext.pop();
            assertSame(outer, ItaraContext.current());
            ItaraContext.pop();
            assertNull(ItaraContext.current());
        }

        @Test
        @DisplayName("clear() empties the stack")
        void clearEmptiesStack() {
            ItaraContext.push(ItaraContext.newRoot("gateway"));
            ItaraContext.push(ItaraContext.newRoot("gateway"));
            ItaraContext.clear();
            assertNull(ItaraContext.current());
        }

        @Test
        @DisplayName("stack is thread-local — push on one thread is not visible on another")
        void stackIsThreadLocal() throws InterruptedException {
            ItaraContext ctx = ItaraContext.newRoot("gateway");
            ItaraContext.push(ctx);

            ItaraContext[] otherThreadSaw = {null};
            Thread other = new Thread(() -> otherThreadSaw[0] = ItaraContext.current());
            other.start();
            other.join();

            assertNull(otherThreadSaw[0],
                    "Context pushed on main thread must not be visible on other threads");

            ItaraContext.pop();
        }
    }

    // ── ID generation ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("ID generation")
    class IdGeneration {

        @Test
        @DisplayName("generateItaraTraceId produces unique values")
        void traceIdIsUnique() {
            assertNotEquals(
                    ItaraContext.generateItaraTraceId(),
                    ItaraContext.generateItaraTraceId());
        }

        @Test
        @DisplayName("generateItaraSpanId produces unique values")
        void spanIdIsUnique() {
            assertNotEquals(
                    ItaraContext.generateItaraSpanId(),
                    ItaraContext.generateItaraSpanId());
        }

        @Test
        @DisplayName("generateItaraTraceId is 32 lowercase hex chars")
        void traceIdFormat() {
            assertTrue(ItaraContext.generateItaraTraceId()
                    .matches("[0-9a-f]{32}"));
        }

        @Test
        @DisplayName("generateItaraSpanId is 16 lowercase hex chars")
        void spanIdFormat() {
            assertTrue(ItaraContext.generateItaraSpanId()
                    .matches("[0-9a-f]{16}"));
        }
    }
}
