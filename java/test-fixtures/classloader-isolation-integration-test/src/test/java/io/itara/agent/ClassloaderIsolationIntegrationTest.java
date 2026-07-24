package io.itara.agent;

import com.example.conflicta.api.ConflictAService;
import com.example.conflictb.api.ConflictBService;
import io.itara.agent.config.ComponentNode;
import io.itara.agent.config.WiringConfig;
import io.itara.agent.metadata.ItaraMetadataIndex;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
            + "and TCCL is correctly set to each target's own classloader during dispatch")
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
            Class<?> contractClass = componentId.equals("conflict-a") ? ConflictAService.class : ConflictBService.class;
            registry.registerActivator(componentId, activated.getActivatorClass(), contractClass, componentClassLoader);
        }

        // Real cross-component call, real conflicting dependency versions.
        ConflictAService conflictA = registry.get("conflict-a", ConflictAService.class);
        String result = conflictA.describe();

        assertTrue(result.contains("v1"), "expected conflict-a's own dependency version marker in: " + result);
        assertTrue(result.contains("V2"), "expected conflict-b's own dependency version marker in: " + result);

        // TCCL correctness — each component's own captured TCCL must match
        // exactly the classloader ActivatorScanner registered it under.
        ConflictBService conflictB = registry.get("conflict-b", ConflictBService.class);

        assertSame(ActivatorScanner.instance().getClassLoader("conflict-b"), conflictB.captureClassLoader());
        assertSame(ActivatorScanner.instance().getClassLoader("conflict-a"), conflictA.captureClassLoader());
    }
}
