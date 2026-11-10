package khi.testmod.fabric;

import net.fabricmc.api.ModInitializer;

public class TestFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.out.println("[test-fabric] Fabric entrypoint ran inside Pentecost");
    }
}
