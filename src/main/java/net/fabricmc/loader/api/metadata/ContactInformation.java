package net.fabricmc.loader.api.metadata;

import java.util.Map;
import java.util.Optional;

public interface ContactInformation {
    Optional<String> get(String key);

    Map<String, String> asMap();
}
