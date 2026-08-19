package io.itara.agent;

import com.example.conflicta.api.ConflictAService;
import com.example.conflictb.api.ConflictBService;
import io.itara.agent.authentication.NoopAuthentication;
import io.itara.agent.authorization.NoopAuthorization;
import io.itara.agent.config.ComponentNode;
import io.itara.agent.config.WiringConfig;
import io.itara.agent.metadata.ItaraMetadataIndex;
import io.itara.runtime.ComponentScope;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.authentication.AuthenticationConfig;
import io.itara.spi.authentication.ItaraAuthentication;
import io.itara.spi.authentication.ItaraAuthenticationConfig;
import io.itara.spi.authorization.AuthorizationConfig;
import io.itara.spi.authorization.ItaraAuthorization;
import io.itara.spi.authorization.ItaraAuthorizationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Classloader isolation — real conflicting-dependency components")
class ClassloaderIsolationIntegrationTest {

    private static final String COMPONENTS_DIR = "target/isolation-fixtures";

    @TempDir
    Path metadataDir;

    @BeforeEach
    void reset() {
        ActivatorScanner.instance().reset();
        ItaraRegistry.instance().reset();
        ComponentScope.resetForTest();
        ObservabilityFacade.initialize();
    }

    @BeforeEach
    void buildMetadataIndex() throws IOException {
        Files.writeString(metadataDir.resolve("conflict-a-component.itara"), """
                [artifact]
                kind        = "component"
                id          = "conflict-a"
                version     = "1.0-SNAPSHOT"
                api-version = "1.0-SNAPSHOT"
                """);
        Files.writeString(metadataDir.resolve("conflict-b-component.itara"), """
                [artifact]
                kind        = "component"
                id          = "conflict-b"
                version     = "1.0-SNAPSHOT"
                api-version = "1.0-SNAPSHOT"
                """);

        System.setProperty(ItaraMetadataIndex.METADATA_DIR_PROPERTY, metadataDir.toString());
        ItaraMetadataIndex.instance().build();
    }

    @Test
    @DisplayName("two components with conflicting transitive dependencies colocate, call each other correctly, "
            + "and TCCL is correctly set to each target's own classloader during dispatch — via ComponentScope, "
            + "not a registry-internal swap")
    void colocatesAndDispatchesCorrectly() {
        ComponentNode nodeA = new ComponentNode();
        nodeA.setId("conflictANode");
        nodeA.setComponent("conflict-a");

        ComponentNode nodeB = new ComponentNode();
        nodeB.setId("conflictBNode");
        nodeB.setComponent("conflict-b");

        WiringConfig config = new WiringConfig();
        config.setNodes(List.of(nodeA, nodeB));
        config.setLocalNodeIds(List.of("conflictANode", "conflictBNode"));

        // This module's own classpath contains nothing beyond what a real
        // system classloader would — no unrelated fixtures, no unrelated
        // frameworks — so the ambient test classloader is a faithful
        // stand-in with no workaround needed.
        ClassLoader systemClassLoader = Thread.currentThread().getContextClassLoader();
        ActivatorScanner.instance().scan(systemClassLoader, config, COMPONENTS_DIR);

        ItaraRegistry registry = ItaraRegistry.instance();
        for (String componentId : new String[]{ "conflict-a", "conflict-b" }) {
            ActivatedComponent activated = ActivatorScanner.instance().getActivatedComponent(componentId);
            ClassLoader componentClassLoader = ActivatorScanner.instance().getClassLoader(componentId);
            registry.registerActivator(componentId, activated.getActivatorClass());
        }

        final ItaraAuthentication NOOP_AUTHENTICATION = new NoopAuthentication();
        final ItaraAuthenticationConfig NOOP_AUTHENTICATION_CONFIG =
                new NoopAuthentication.Factory().parseConfig(AuthenticationConfig.builder().build());
        final ItaraAuthorization NOOP_AUTHORIZATION = new NoopAuthorization();
        final ItaraAuthorizationConfig NOOP_AUTHORIZATION_CONFIG =
                new NoopAuthorization.Factory().parseConfig(AuthorizationConfig.builder().build());

        // One ComponentScope per local node — built once, the same way
        // ItaraAgent builds them, and shared by every proxy that serves
        // that node (ADR 0021).
        ComponentScope scopeA = new ComponentScope.Factory()
                .nodeId("conflictANode")
                .componentId("conflict-a")
                .classLoader(ActivatorScanner.instance().getClassLoader("conflict-a"))
                .build();
        ComponentScope scopeB = new ComponentScope.Factory()
                .nodeId("conflictBNode")
                .componentId("conflict-b")
                .classLoader(ActivatorScanner.instance().getClassLoader("conflict-b"))
                .build();

        // A pseudo-caller scope for the test's own top-level access only —
        // never a real node, never resolved through get(). Only used as
        // ItaraLocalProxyHandler's required fromScope for the two
        // test-harness proxies below (ADR 0021 — a proxy must hold a
        // captured scope reference regardless of whether anything reads it
        // yet).
        ComponentScope testHarnessScope = new ComponentScope.Factory()
                .nodeId("testHarnessNode")
                .componentId("test-harness")
                .classLoader(systemClassLoader)
                .build();

        // The one connection that actually matters for this test: conflict-a
        // calling conflict-b, internally, from within its own activator.
        // Registering this is what makes registry.get("conflict-b", ...)
        // resolve correctly when it's called from conflict-a's own scope.
        ConflictBService aToB = (ConflictBService) Proxy.newProxyInstance(
                systemClassLoader,
                new Class<?>[]{ ConflictBService.class },
                new ItaraLocalProxyHandler("conn-a-to-b", "conflict-b", registry, scopeB, scopeA,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG)
        );
        registry.registerConnectionProxy("conn-a-to-b", aToB);
        registry.registerOutboundConnection("conflictANode", "conflict-b", "conn-a-to-b");

        // Test-harness-only access to each component directly — bypasses
        // get()'s scope requirement entirely via getConnectionProxy(),
        // exactly as its own javadoc describes: for tests that already
        // know exactly which connection they want. Calling through either
        // of these still exercises the real ItaraLocalProxyHandler path —
        // scope opens, lazy activation runs under it, TCCL is set — same
        // as any other call. Only the top-level "who's asking" bookkeeping
        // is a stand-in.
        ConflictAService conflictA = (ConflictAService) Proxy.newProxyInstance(
                systemClassLoader,
                new Class<?>[]{ ConflictAService.class },
                new ItaraLocalProxyHandler("conn-test-to-a", "conflict-a", registry, scopeA, testHarnessScope,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG)
        );
        ConflictBService conflictB = (ConflictBService) Proxy.newProxyInstance(
                systemClassLoader,
                new Class<?>[]{ ConflictBService.class },
                new ItaraLocalProxyHandler("conn-test-to-b", "conflict-b", registry, scopeB, testHarnessScope,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG)
        );

        // Real cross-component call, real conflicting dependency versions.
        // conflict-a's activation happens here, lazily, under scopeA — and
        // its own internal registry.get("conflict-b", ...) call resolves
        // via the outbound connection registered above.
        String result = conflictA.describe();

        assertTrue(result.contains("v1"), "expected conflict-a's own dependency version marker in: " + result);
        assertTrue(result.contains("V2"), "expected conflict-b's own dependency version marker in: " + result);

        // TCCL correctness — each component's own captured TCCL must match
        // exactly the classloader ActivatorScanner registered it under.
        // This is now proven via ComponentScopeHandle, opened inside
        // ItaraLocalProxyHandler.invoke() around each call — not any
        // registry-internal classloader swap.
        assertSame(ActivatorScanner.instance().getClassLoader("conflict-b"), conflictB.captureClassLoader());
        assertSame(ActivatorScanner.instance().getClassLoader("conflict-a"), conflictA.captureClassLoader());
    }
}
