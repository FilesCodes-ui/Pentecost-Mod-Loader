package net.fabricmc.loader.api.metadata;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
public interface Person {
    String getName();

    ContactInformation getContact();
}
