package khi.pentecost.mixin;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PentecostPropertyService implements IGlobalPropertyService {
    private record Key(String name) implements IPropertyKey {}

    private static final Map<String, Object> PROPS = new ConcurrentHashMap<>();

    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) PROPS.get(((Key) key).name());
    }

    public void setProperty(IPropertyKey key, Object value) {
        if (value == null) {
            PROPS.remove(((Key) key).name());
        } else {
            PROPS.put(((Key) key).name(), value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object v = PROPS.get(((Key) key).name());
        return v == null ? defaultValue : (T) v;
    }

    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object v = PROPS.get(((Key) key).name());
        return v == null ? defaultValue : v.toString();
    }
}
