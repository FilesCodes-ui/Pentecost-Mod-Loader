package khi.pentecost.mods;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyMetTest {
    private static final Set<String> PACK = Set.of("embeddium", "oculus", "fabric_api_base", "fabric_resource_loader_v0", "create");

    @Test
    void forgePortsMeetFabricDependencies() {
        assertTrue(ModDiscovery.met("sodium", PACK));
        assertTrue(ModDiscovery.met("iris", PACK));
        assertTrue(ModDiscovery.met("indium", PACK));
    }

    @Test
    void pentecostsFabricApiMeetsFabricApiAndQuiltLibraries() {
        assertTrue(ModDiscovery.met("fabric-api", PACK));
        assertTrue(ModDiscovery.met("fabric", PACK));
        assertTrue(ModDiscovery.met("quilt_resource_loader", PACK));
        assertTrue(ModDiscovery.met("fabric-api-base", PACK));
    }

    @Test
    void missingModsStayMissing() {
        assertFalse(ModDiscovery.met("lithostitched", PACK));
        assertFalse(ModDiscovery.met("sodium", Set.of("create")));
    }
}
