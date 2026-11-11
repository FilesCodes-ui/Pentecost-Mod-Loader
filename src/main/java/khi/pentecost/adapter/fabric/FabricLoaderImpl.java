package khi.pentecost.adapter.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import khi.pentecost.boot.Dirs;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import khi.pentecost.remap.Mappings;
import khi.pentecost.remap.TextRemapper;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Pentecost's answer to every FabricLoader call. Reads the shared mod list. */
public final class FabricLoaderImpl implements FabricLoader {
    public static final FabricLoaderImpl INSTANCE = new FabricLoaderImpl();

    private final Map<String, Object> instances = new ConcurrentHashMap<>();
    private final Map<String, Container> containers = new ConcurrentHashMap<>();
    private final Share share = new Share();
    private volatile Object gameInstance;

    private FabricLoaderImpl() {}

    // ---------------------------------------------------------------- entrypoints

    /** Creates the object an entrypoint string names: "pkg.Class", "pkg.Class::field", or "pkg.Class::method". */
    public <T> T create(String value, Class<T> type) throws Exception {
        int sep = value.indexOf("::");
        String className = sep < 0 ? value : value.substring(0, sep);
        Class<?> c = Class.forName(className, true, PentecostRuntime.loader);
        if (sep < 0) {
            Object o = instances.get(className);
            if (o == null) {
                var ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                o = ctor.newInstance();
                Object old = instances.putIfAbsent(className, o);
                o = old != null ? old : o;
            }
            return type.isInstance(o) ? type.cast(o) : null;
        }
        String member = value.substring(sep + 2);
        for (Field f : c.getDeclaredFields()) {
            if (f.getName().equals(member) && Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                Object o = f.get(null);
                return type.isInstance(o) ? type.cast(o) : null;
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(member) && type.isInterface()) {
                m.setAccessible(true);
                Object target = Modifier.isStatic(m.getModifiers()) ? null : create(className, Object.class);
                return type.cast(Proxy.newProxyInstance(PentecostRuntime.loader, new Class<?>[]{type},
                        (proxy, called, args) -> called.getDeclaringClass() == Object.class
                                ? called.invoke(m, args) : m.invoke(target, args)));
            }
        }
        throw new NoSuchMethodException("Entrypoint member " + value + " was not found");
    }

    @Override
    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        List<EntrypointContainer<T>> out = new ArrayList<>();
        for (ModInfo m : PentecostRuntime.mods) {
            if (m.loader() != LoaderKind.FABRIC && m.loader() != LoaderKind.QUILT) {
                continue;
            }
            for (String value : m.entrypoints(key)) {
                try {
                    T o = create(value, type);
                    if (o != null) {
                        ModContainer provider = container(m);
                        out.add(new EntrypointContainer<>() {
                            @Override
                            public T getEntrypoint() {
                                return o;
                            }

                            @Override
                            public ModContainer getProvider() {
                                return provider;
                            }

                            @Override
                            public String getDefinition() {
                                return value;
                            }
                        });
                    }
                } catch (Throwable t) {
                    khi.pentecost.boot.Log.error("Entrypoint " + value + " of " + m.name() + " failed: " + t);
                }
            }
        }
        return out;
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EntrypointContainer<T> c : getEntrypointContainers(key, type)) {
            out.add(c.getEntrypoint());
        }
        return out;
    }

    @Override
    public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
        for (T t : getEntrypoints(key, type)) {
            invoker.accept(t);
        }
    }

    // ---------------------------------------------------------------- mods

    private Container container(ModInfo m) {
        return containers.computeIfAbsent(m.id(), k -> new Container(m));
    }

    @Override
    public Optional<ModContainer> getModContainer(String id) {
        for (ModInfo m : PentecostRuntime.mods) {
            if (m.id().equals(id)) {
                return Optional.of(container(m));
            }
        }
        for (ModInfo m : PentecostRuntime.mods) {
            if (m.raw() != null && m.raw().has("provides") && m.raw().get("provides").isJsonArray()) {
                for (com.google.gson.JsonElement e : m.raw().getAsJsonArray("provides")) {
                    if (e.isJsonPrimitive() && e.getAsString().equals(id)) {
                        return Optional.of(container(m));
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Collection<ModContainer> getAllMods() {
        List<ModContainer> out = new ArrayList<>();
        for (ModInfo m : PentecostRuntime.mods) {
            if (m.loader() != LoaderKind.LIBRARY) {
                out.add(container(m));
            }
        }
        return out;
    }

    @Override
    public boolean isModLoaded(String id) {
        if (id.equals("minecraft") || id.equals("java") || id.equals("fabricloader")) {
            return true;
        }
        return getModContainer(id).isPresent();
    }

    // ---------------------------------------------------------------- environment

    @Override
    public ObjectShare getObjectShare() {
        return share;
    }

    @Override
    public MappingResolver getMappingResolver() {
        return Resolver.INSTANCE;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }

    @Override
    public EnvType getEnvironmentType() {
        return PentecostRuntime.client ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public Object getGameInstance() {
        return gameInstance;
    }

    public void setGameInstance(Object game) {
        this.gameInstance = game;
    }

    @Override
    public Path getGameDir() {
        Path dir = PentecostRuntime.gameDir;
        return dir != null ? dir : Dirs.run();
    }

    @Override
    public File getGameDirectory() {
        return getGameDir().toFile();
    }

    @Override
    public Path getConfigDir() {
        return Dirs.mk(getGameDir().resolve("config"));
    }

    @Override
    public File getConfigDirectory() {
        return getConfigDir().toFile();
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return sanitize ? new String[0] : PentecostRuntime.launchArgs.clone();
    }

    // ---------------------------------------------------------------- versions

    public static Version parseVersion(String s) throws VersionParsingException {
        if (s == null || s.isEmpty()) {
            throw new VersionParsingException("Version must be a non-empty string");
        }
        return new Ver(s);
    }

    private record Ver(String text) implements Version {
        @Override
        public String getFriendlyString() {
            return text;
        }

        @Override
        public int compareTo(Version other) {
            String[] a = text.split("[.+-]");
            String[] b = other.getFriendlyString().split("[.+-]");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                String x = i < a.length ? a[i] : "0";
                String y = i < b.length ? b[i] : "0";
                int c;
                try {
                    c = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
                } catch (NumberFormatException e) {
                    c = x.compareTo(y);
                }
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    // ---------------------------------------------------------------- object share

    private static final class Share implements ObjectShare {
        private final Map<String, Object> values = new ConcurrentHashMap<>();
        private final Map<String, List<BiConsumer<String, Object>>> waiting = new ConcurrentHashMap<>();

        @Override
        public Object get(String key) {
            return values.get(key);
        }

        @Override
        public void whenAvailable(String key, BiConsumer<String, Object> consumer) {
            Object v = values.get(key);
            if (v != null) {
                consumer.accept(key, v);
            } else {
                waiting.computeIfAbsent(key, k -> new ArrayList<>()).add(consumer);
            }
        }

        @Override
        public Object put(String key, Object value) {
            Object old = values.put(key, value);
            fire(key, value);
            return old;
        }

        @Override
        public Object putIfAbsent(String key, Object value) {
            Object old = values.putIfAbsent(key, value);
            if (old == null) {
                fire(key, value);
            }
            return old;
        }

        @Override
        public Object remove(String key) {
            return values.remove(key);
        }

        private void fire(String key, Object value) {
            List<BiConsumer<String, Object>> list = waiting.remove(key);
            if (list != null) {
                for (BiConsumer<String, Object> c : list) {
                    c.accept(key, value);
                }
            }
        }
    }

    // ---------------------------------------------------------------- mapping resolver

    /** The game runs in Mojang names. Mods ask in intermediary names, and this converts. */
    private static final class Resolver implements MappingResolver {
        static final Resolver INSTANCE = new Resolver();
        private Mappings.NameTable table;
        private TextRemapper text;
        private Map<String, String> reverse;

        private synchronized void load() {
            if (table == null) {
                try {
                    table = PentecostRuntime.remap.modTable();
                } catch (Exception e) {
                    throw new IllegalStateException("Name tables failed to load", e);
                }
                text = new TextRemapper(table);
                reverse = new HashMap<>();
                table.classes().forEach((k, v) -> reverse.putIfAbsent(v, k));
            }
        }

        @Override
        public Collection<String> getNamespaces() {
            return List.of("named", "intermediary");
        }

        @Override
        public String getCurrentRuntimeNamespace() {
            return "named";
        }

        @Override
        public String mapClassName(String namespace, String className) {
            if (!"intermediary".equals(namespace)) {
                return className;
            }
            load();
            return text.mapClass(className.replace('.', '/')).replace('/', '.');
        }

        @Override
        public String unmapClassName(String targetNamespace, String className) {
            if (!"intermediary".equals(targetNamespace)) {
                return className;
            }
            load();
            return reverse.getOrDefault(className.replace('.', '/'), className.replace('.', '/')).replace('/', '.');
        }

        @Override
        public String mapFieldName(String namespace, String owner, String name, String descriptor) {
            if (!"intermediary".equals(namespace)) {
                return name;
            }
            load();
            return table.fields().getOrDefault(name, name);
        }

        @Override
        public String mapMethodName(String namespace, String owner, String name, String descriptor) {
            if (!"intermediary".equals(namespace)) {
                return name;
            }
            load();
            return table.methods().getOrDefault(name, name);
        }
    }

    // ---------------------------------------------------------------- containers and metadata

    private static final class Container implements ModContainer {
        private final ModInfo mod;
        private final Meta meta;
        private Path rootPath;

        Container(ModInfo mod) {
            this.mod = mod;
            this.meta = new Meta(mod);
        }

        @Override
        public ModMetadata getMetadata() {
            return meta;
        }

        @Override
        public synchronized Path getRootPath() {
            if (rootPath == null) {
                try {
                    FileSystem fs = FileSystems.newFileSystem(mod.jar(), (ClassLoader) null);
                    rootPath = fs.getPath("/");
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot open " + mod.jar(), e);
                }
            }
            return rootPath;
        }

        @Override
        public List<Path> getRootPaths() {
            return List.of(getRootPath());
        }

        @Override
        public Optional<Path> findPath(String file) {
            Path p = getRootPath().resolve(file.replace('\\', '/'));
            return Files.exists(p) ? Optional.of(p) : Optional.empty();
        }

        @Override
        public Path getPath(String file) {
            return getRootPath().resolve(file.replace('\\', '/'));
        }

        @Override
        public net.fabricmc.loader.api.metadata.ModOrigin getOrigin() {
            Path jar = mod.jar();
            return new net.fabricmc.loader.api.metadata.ModOrigin() {
                @Override
                public Kind getKind() {
                    return Kind.PATH;
                }

                @Override
                public List<Path> getPaths() {
                    return List.of(jar);
                }

                @Override
                public String getParentModId() {
                    throw new UnsupportedOperationException("kind is PATH");
                }

                @Override
                public String getParentSubLocation() {
                    throw new UnsupportedOperationException("kind is PATH");
                }
            };
        }

        @Override
        public Optional<ModContainer> getContainingMod() {
            return Optional.empty();
        }

        @Override
        public Collection<ModContainer> getContainedMods() {
            return List.of();
        }

        @Override
        public String toString() {
            return mod.id() + " " + mod.version();
        }
    }

    private static final class Meta implements ModMetadata {
        private final ModInfo mod;
        private final JsonObject raw;

        Meta(ModInfo mod) {
            this.mod = mod;
            this.raw = mod.raw() == null ? new JsonObject() : mod.raw();
        }

        private String str(String key, String fallback) {
            return raw.has(key) && raw.get(key).isJsonPrimitive() ? raw.get(key).getAsString() : fallback;
        }

        @Override
        public String getType() {
            return "fabric";
        }

        @Override
        public String getId() {
            return mod.id();
        }

        @Override
        public Collection<String> getProvides() {
            List<String> out = new ArrayList<>();
            if (raw.has("provides") && raw.get("provides").isJsonArray()) {
                for (JsonElement e : raw.getAsJsonArray("provides")) {
                    out.add(e.getAsString());
                }
            }
            return out;
        }

        @Override
        public Version getVersion() {
            return new Ver(mod.version());
        }

        @Override
        public net.fabricmc.loader.api.metadata.ModEnvironment getEnvironment() {
            String env = str("environment", "*");
            return switch (env) {
                case "client" -> net.fabricmc.loader.api.metadata.ModEnvironment.CLIENT;
                case "server" -> net.fabricmc.loader.api.metadata.ModEnvironment.SERVER;
                default -> net.fabricmc.loader.api.metadata.ModEnvironment.UNIVERSAL;
            };
        }

        @Override
        public Collection<net.fabricmc.loader.api.metadata.ModDependency> getDependencies() {
            List<net.fabricmc.loader.api.metadata.ModDependency> out = new ArrayList<>();
            for (var kind : net.fabricmc.loader.api.metadata.ModDependency.Kind.values()) {
                if (!raw.has(kind.getKey()) || !raw.get(kind.getKey()).isJsonObject()) {
                    continue;
                }
                for (String id : raw.getAsJsonObject(kind.getKey()).keySet()) {
                    out.add(new net.fabricmc.loader.api.metadata.ModDependency() {
                        @Override
                        public Kind getKind() {
                            return kind;
                        }

                        @Override
                        public String getModId() {
                            return id;
                        }

                        @Override
                        public boolean matches(Version version) {
                            return true;
                        }
                    });
                }
            }
            return out;
        }

        @Override
        public String getName() {
            return mod.name();
        }

        @Override
        public String getDescription() {
            return str("description", "");
        }

        private Collection<Person> people(String key) {
            List<Person> out = new ArrayList<>();
            if (raw.has(key) && raw.get(key).isJsonArray()) {
                for (JsonElement e : raw.getAsJsonArray(key)) {
                    String name = e.isJsonPrimitive() ? e.getAsString()
                            : e.isJsonObject() && e.getAsJsonObject().has("name")
                            ? e.getAsJsonObject().get("name").getAsString() : null;
                    if (name != null) {
                        out.add(new Person() {
                            @Override
                            public String getName() {
                                return name;
                            }

                            @Override
                            public ContactInformation getContact() {
                                return contact(null);
                            }
                        });
                    }
                }
            }
            return out;
        }

        @Override
        public Collection<Person> getAuthors() {
            return people("authors");
        }

        @Override
        public Collection<Person> getContributors() {
            return people("contributors");
        }

        private static ContactInformation contact(JsonObject o) {
            Map<String, String> map = new LinkedHashMap<>();
            if (o != null) {
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        map.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
            return new ContactInformation() {
                @Override
                public Optional<String> get(String key) {
                    return Optional.ofNullable(map.get(key));
                }

                @Override
                public Map<String, String> asMap() {
                    return map;
                }
            };
        }

        @Override
        public ContactInformation getContact() {
            return contact(raw.has("contact") && raw.get("contact").isJsonObject() ? raw.getAsJsonObject("contact") : null);
        }

        @Override
        public Collection<String> getLicense() {
            List<String> out = new ArrayList<>();
            JsonElement l = raw.get("license");
            if (l != null && l.isJsonPrimitive()) {
                out.add(l.getAsString());
            } else if (l != null && l.isJsonArray()) {
                for (JsonElement e : l.getAsJsonArray()) {
                    out.add(e.getAsString());
                }
            }
            return out;
        }

        @Override
        public Optional<String> getIconPath(int size) {
            return Optional.ofNullable(str("icon", null));
        }

        private JsonObject custom() {
            return raw.has("custom") && raw.get("custom").isJsonObject() ? raw.getAsJsonObject("custom") : new JsonObject();
        }

        @Override
        public boolean containsCustomValue(String key) {
            return custom().has(key);
        }

        @Override
        public CustomValue getCustomValue(String key) {
            JsonElement e = custom().get(key);
            return e == null ? null : Cv.of(e);
        }

        @Override
        public Map<String, CustomValue> getCustomValues() {
            Map<String, CustomValue> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : custom().entrySet()) {
                out.put(e.getKey(), Cv.of(e.getValue()));
            }
            return out;
        }

        @Override
        public boolean containsCustomElement(String key) {
            return containsCustomValue(key);
        }
    }

    /** A value from the "custom" block of fabric.mod.json. */
    private static class Cv implements CustomValue {
        final JsonElement e;

        Cv(JsonElement e) {
            this.e = e;
        }

        static CustomValue of(JsonElement e) {
            if (e.isJsonObject()) {
                return new CvObj(e);
            }
            if (e.isJsonArray()) {
                return new CvArr(e);
            }
            return new Cv(e);
        }

        @Override
        public CvType getType() {
            if (e.isJsonObject()) {
                return CvType.OBJECT;
            }
            if (e.isJsonArray()) {
                return CvType.ARRAY;
            }
            if (e.isJsonNull()) {
                return CvType.NULL;
            }
            var p = e.getAsJsonPrimitive();
            return p.isBoolean() ? CvType.BOOLEAN : p.isNumber() ? CvType.NUMBER : CvType.STRING;
        }

        @Override
        public CvObject getAsObject() {
            throw new ClassCastException("Not an object");
        }

        @Override
        public CvArray getAsArray() {
            throw new ClassCastException("Not an array");
        }

        @Override
        public String getAsString() {
            return e.getAsString();
        }

        @Override
        public Number getAsNumber() {
            return e.getAsNumber();
        }

        @Override
        public boolean getAsBoolean() {
            return e.getAsBoolean();
        }
    }

    private static final class CvObj extends Cv implements CustomValue.CvObject {
        CvObj(JsonElement e) {
            super(e);
        }

        @Override
        public CvObject getAsObject() {
            return this;
        }

        @Override
        public int size() {
            return e.getAsJsonObject().size();
        }

        @Override
        public boolean containsKey(String key) {
            return e.getAsJsonObject().has(key);
        }

        @Override
        public CustomValue get(String key) {
            JsonElement v = e.getAsJsonObject().get(key);
            return v == null ? null : Cv.of(v);
        }

        @Override
        public Iterator<Map.Entry<String, CustomValue>> iterator() {
            Map<String, CustomValue> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                m.put(x.getKey(), Cv.of(x.getValue()));
            }
            return m.entrySet().iterator();
        }
    }

    private static final class CvArr extends Cv implements CustomValue.CvArray {
        CvArr(JsonElement e) {
            super(e);
        }

        @Override
        public CvArray getAsArray() {
            return this;
        }

        @Override
        public int size() {
            return e.getAsJsonArray().size();
        }

        @Override
        public CustomValue get(int index) {
            return Cv.of(e.getAsJsonArray().get(index));
        }

        @Override
        public Iterator<CustomValue> iterator() {
            List<CustomValue> l = new ArrayList<>();
            JsonArray arr = e.getAsJsonArray();
            for (JsonElement x : arr) {
                l.add(Cv.of(x));
            }
            return l.iterator();
        }
    }
}
