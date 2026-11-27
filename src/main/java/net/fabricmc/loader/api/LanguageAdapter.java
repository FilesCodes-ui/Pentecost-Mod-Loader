package net.fabricmc.loader.api;

import khi.pentecost.adapter.fabric.FabricLoaderImpl;

/** Creates an entrypoint object from a "class" or "class::member" string, like Fabric's default adapter. */
public interface LanguageAdapter {
    <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException;

    static LanguageAdapter getDefault() {
        return new LanguageAdapter() {
            @Override
            public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
                try {
                    return FabricLoaderImpl.INSTANCE.create(value, type);
                } catch (Exception e) {
                    throw new LanguageAdapterException(e);
                }
            }
        };
    }
}
