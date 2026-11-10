package net.fabricmc.loader.api.metadata;

import net.fabricmc.loader.api.Version;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
public interface ModMetadata {
    String getType();

    String getId();

    Collection<String> getProvides();

    Version getVersion();

    ModEnvironment getEnvironment();

    Collection<ModDependency> getDependencies();

    default Collection<ModDependency> getDepends() {
        return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.DEPENDS).toList();
    }

    default Collection<ModDependency> getRecommends() {
        return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.RECOMMENDS).toList();
    }

    default Collection<ModDependency> getSuggests() {
        return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.SUGGESTS).toList();
    }

    default Collection<ModDependency> getConflicts() {
        return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.CONFLICTS).toList();
    }

    default Collection<ModDependency> getBreaks() {
        return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.BREAKS).toList();
    }

    String getName();

    String getDescription();

    Collection<Person> getAuthors();

    Collection<Person> getContributors();

    ContactInformation getContact();

    Collection<String> getLicense();

    Optional<String> getIconPath(int size);

    boolean containsCustomValue(String key);

    CustomValue getCustomValue(String key);

    Map<String, CustomValue> getCustomValues();

    boolean containsCustomElement(String key);
}
