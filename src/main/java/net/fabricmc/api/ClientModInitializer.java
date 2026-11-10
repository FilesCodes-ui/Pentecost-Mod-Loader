package net.fabricmc.api;

/** Pentecost's own copy of the Fabric client entrypoint interface. */
@FunctionalInterface
public interface ClientModInitializer {
    void onInitializeClient();
}
