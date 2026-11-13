package khi.pentecost.loader;

import khi.pentecost.boot.Log;
import khi.pentecost.install.MinecraftInstall.GameFiles;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads Minecraft, its libraries, and every mod. Each class passes through the
 * transformer chain first, then through Mixin. Also knows which mod a class came from, for crash blame.
 */
public final class GameClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final List<ClassTransformer> transformers = new CopyOnWriteArrayList<>();
    private volatile ClassTransformer mixin;
    private final Map<String, String> modByJarUrl = new HashMap<>();

    private GameClassLoader(URL[] urls, ClassLoader parent) {
        super("pentecost-game", urls, parent);
    }

    public static GameClassLoader create(GameFiles game, List<ModInfo> mods) throws MalformedURLException {
        return create(game, mods, List.of(), List.of());
    }

    /**
     * @param first jars and folders searched before the game jar
     * @param afterGame jars searched right after the game jar and before the mods
     */
    public static GameClassLoader create(GameFiles game, List<ModInfo> mods, List<Path> first, List<Path> afterGame)
            throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        for (Path p : first) {
            urls.add(url(p));
        }
        urls.add(url(game.clientJar()));
        for (Path p : afterGame) {
            urls.add(url(p));
        }
        Map<String, String> owners = new HashMap<>();
        for (ModInfo mod : mods) {
            URL u = url(mod.jar());
            if (!urls.contains(u)) {
                urls.add(u);
            }
            owners.putIfAbsent(u.toString(), mod.id());
        }
        for (Path lib : game.libraries()) {
            urls.add(url(lib));
        }
        GameClassLoader loader = new GameClassLoader(urls.toArray(new URL[0]), GameClassLoader.class.getClassLoader());
        loader.modByJarUrl.putAll(owners);
        return loader;
    }

    /**
     * Same as the stock lookup, with changes for one known failure: Embeddium asks for its shader files and
     * the stock lookup answers "missing" although the jar holds them (Embeddium: "Shader not found").
     * The stock lookup hides every error, so this one writes what happens to run/logs/pentecost-resources.log
     * and, for Embeddium's files only, reads the file straight from its jar when the stock lookup fails.
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        name = withoutLeadingSlash(name);
        URL url = getResource(name);
        boolean watched = name.startsWith("assets/sodium/");
        if (url != null) {
            try {
                InputStream served = url.openStream();
                if (watched) {
                    note("Resource " + name + " served from " + url);
                }
                return served;
            } catch (IOException first) {
                note("Resource " + name + " found at " + url + " but opening it failed: " + first);
                try {
                    URLConnection c = url.openConnection();
                    c.setUseCaches(false);
                    InputStream fresh = c.getInputStream();
                    note("Resource " + name + " opened again with a fresh jar handle");
                    return fresh;
                } catch (IOException second) {
                    note("Resource " + name + " failed again with a fresh jar handle: " + second);
                }
            }
        } else if (watched) {
            note("Resource " + name + " not found by the stock lookup");
        }
        if (!watched) {
            return null;
        }
        InputStream direct = openFromJars(name);
        note("Resource " + name + (direct != null ? " read straight from its jar" : " is in none of the jars"));
        return direct;
    }

    /** Looks for the file in each jar, with a jar handle of its own. Slow, so only for files that must exist. */
    private InputStream openFromJars(String name) {
        for (URL u : getURLs()) {
            if (!"file".equals(u.getProtocol()) || !u.getPath().endsWith(".jar")) {
                continue;
            }
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(Path.of(u.toURI()).toFile())) {
                java.util.zip.ZipEntry e = jf.getEntry(name);
                if (e != null && !e.isDirectory()) {
                    try (InputStream in = jf.getInputStream(e)) {
                        return new java.io.ByteArrayInputStream(in.readAllBytes());
                    }
                }
            } catch (IOException | java.net.URISyntaxException | RuntimeException e) {
                note("Reading " + name + " from " + u + " failed: " + e);
            }
        }
        return null;
    }

    private static void note(String line) {
        Log.warn(line);
        try {
            java.nio.file.Path file = khi.pentecost.boot.Dirs.run().resolve("logs").resolve("pentecost-resources.log");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, java.time.LocalTime.now() + " " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // Logging must never break the game.
        }
    }

    /**
     * Embeddium asks the loader for "/assets/sodium/shaders/..." with a leading slash.
     * A plain Java loader never matches that, Forge's loader does. Match Forge.
     */
    private static String withoutLeadingSlash(String name) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == '/') {
            i++;
        }
        return i == 0 ? name : name.substring(i);
    }

    @Override
    public URL getResource(String name) {
        return super.getResource(withoutLeadingSlash(name));
    }

    @Override
    public URL findResource(String name) {
        return super.findResource(withoutLeadingSlash(name));
    }

    /** Adds a transformer that runs before Mixin. */
    public void addTransformer(ClassTransformer t) {
        transformers.add(t);
    }

    public void setMixin(ClassTransformer t) {
        this.mixin = t;
    }

    /** Returns the id of the mod whose jar holds this file, or null. */
    public String modOfResource(String path) {
        URL res = findResource(path);
        return res == null ? null : modByJarUrl.get(jarOf(res));
    }

    /**
     * Adds a place to load classes from. Declared here, not only inherited, because Fabric tools
     * (Manningham Mills, used by Aether to add enum values) look for it on the loader's own class.
     */
    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }

    /** Returns the id of the mod whose jar holds this class, or null. */
    public String modOf(String className) {
        URL res = findResource(className.replace('.', '/') + ".class");
        if (res == null) {
            return null;
        }
        return modByJarUrl.get(jarOf(res));
    }

    /** True on a thread that is reading class bytes for Mixin, not loading a class. */
    public static final ThreadLocal<Boolean> PEEK = ThreadLocal.withInitial(() -> false);

    /** Class bytes after every transformer except Mixin. Mixin reads target classes through this. */
    public byte[] preMixinBytes(String name) throws IOException {
        String path = name.replace('.', '/') + ".class";
        URL own = findResource(path);
        try (InputStream in = own != null ? own.openStream() : getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            if (own != null) {
                PEEK.set(true);
                try {
                    for (ClassTransformer t : transformers) {
                        bytes = t.transform(name.replace('/', '.'), bytes);
                    }
                } finally {
                    PEEK.set(false);
                }
            }
            return bytes;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (name.equals("me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader")) {
            note("Embeddium's ShaderLoader is being defined by " + getName());
        }
        String path = name.replace('.', '/') + ".class";
        URL res = findResource(path);
        if (res == null) {
            ClassTransformer mx = mixin;
            byte[] made = mx == null ? null : mx.transform(name, null);
            if (made == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, made, 0, made.length, (CodeSource) null);
        }
        try (InputStream in = res.openStream()) {
            byte[] bytes = in.readAllBytes();
            for (ClassTransformer t : transformers) {
                bytes = t.transform(name, bytes);
            }
            ClassTransformer mx = mixin;
            if (mx != null) {
                bytes = mx.transform(name, bytes);
            }
            bytes = DefaultConflicts.transform(this, name, bytes);
            khi.pentecost.core.HudOwner.afterMixin(name, bytes);
            bytes = khi.pentecost.mixin.MixinReport.instrument(this, name, bytes);
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String pkg = name.substring(0, dot);
                if (getDefinedPackage(pkg) == null) {
                    try {
                        java.util.jar.Manifest mf = manifestOf(jarOf(res));
                        if (mf != null) {
                            definePackage(pkg, mf, URI.create(jarOf(res)).toURL());
                        } else {
                            definePackage(pkg, null, null, null, null, null, null, null);
                        }
                    } catch (IllegalArgumentException alreadyDefined) {
                        // Another thread defined the package first.
                    }
                }
            }
            CodeSource source = new CodeSource(URI.create(jarOf(res)).toURL(), (Certificate[]) null);
            return defineClass(name, bytes, 0, bytes.length, source);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    private final Map<String, java.util.Optional<java.util.jar.Manifest>> manifests =
            new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.jar.Manifest manifestOf(String jarUrl) {
        return manifests.computeIfAbsent(jarUrl, u -> {
            if (!u.endsWith(".jar")) {
                return java.util.Optional.empty();
            }
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(Path.of(URI.create(u)).toFile())) {
                return java.util.Optional.ofNullable(jf.getManifest());
            } catch (IOException | RuntimeException e) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    private static String jarOf(URL resource) {
        String s = resource.toString();
        if (s.startsWith("jar:")) {
            int bang = s.indexOf("!/");
            return s.substring(4, bang < 0 ? s.length() : bang);
        }
        return s;
    }

    private static URL url(Path p) throws MalformedURLException {
        return p.toAbsolutePath().normalize().toUri().toURL();
    }
}
