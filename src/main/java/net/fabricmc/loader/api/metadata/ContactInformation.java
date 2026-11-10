package net.fabricmc.loader.api.metadata;

import java.util.Map;
import java.util.Optional;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
public interface ContactInformation {
    Optional<String> get(String key);

    Map<String, String> asMap();
}
