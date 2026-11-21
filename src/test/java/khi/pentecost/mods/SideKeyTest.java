package khi.pentecost.mods;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SideKeyTest {
    @Test
    void keysDropVersionsAndLoaderNames() {
        assertEquals("distanthorizons", ModDiscovery.sideKey("DistantHorizons-3.3.3-1.20.1-fabric-forge.jar"));
        assertEquals("distanthorizons", ModDiscovery.sideKey("DistantHorizons-3.4.0-1.20.1-fabric-forge.jar"));
        assertEquals("architectury", ModDiscovery.sideKey("architectury-9.2.14-forge.jar"));
        assertEquals("sound-physics-remastered", ModDiscovery.sideKey("sound-physics-remastered-fabric-1.20.1-1.5.1.jar"));
        assertEquals("immediatelyfast", ModDiscovery.sideKey("ImmediatelyFast-Fabric-1.5.5+1.20.4.jar"));
    }
}
