package net.fabricmc.loader.api.metadata;

import net.fabricmc.api.EnvType;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
public enum ModEnvironment {
    CLIENT,
    SERVER,
    UNIVERSAL;

    public boolean matches(EnvType type) {
        return switch (this) {
            case CLIENT -> type == EnvType.CLIENT;
            case SERVER -> type == EnvType.SERVER;
            case UNIVERSAL -> true;
        };
    }
}
