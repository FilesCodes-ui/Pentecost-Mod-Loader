package khi.pentecost.loader;

import khi.pentecost.install.MinecraftInstall.GameFiles;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
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
 * transformer chain first. Also knows which mod a class came from, for crash blame.
 */
public final class GameClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final List<ClassTransformer> transformers = new CopyOnWriteArrayList<>();
    private final Map<String, String> modByJarUrl = new HashMap<>();

    private GameClassLoader(URL[] urls, ClassLoader parent) {
        super("pentecost-game", urls, parent);
    }

    public static GameClassLoader create(GameFiles game, List<ModInfo> mods) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        urls.add(url(game.clientJar()));
        for (Path lib : game.libraries()) {
            urls.add(url(lib));
        }
        Map<String, String> owners = new HashMap<>();
        for (ModInfo mod : mods) {
            URL u = url(mod.jar());
            if (!urls.contains(u)) {
                urls.add(u);
            }
            owners.putIfAbsent(u.toString(), mod.id());
        }
        GameClassLoader loader = new GameClassLoader(urls.toArray(new URL[0]), GameClassLoader.class.getClassLoader());
        loader.modByJarUrl.putAll(owners);
        return loader;
    }

    public void addTransformer(ClassTransformer t) {
        transformers.add(t);
    }

    /** Returns the id of the mod whose jar holds this class, or null. */
    public String modOf(String className) {
        URL res = findResource(className.replace('.', '/') + ".class");
        if (res == null) {
            return null;
        }
        return modByJarUrl.get(jarOf(res));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        URL res = findResource(path);
        if (res == null) {
            throw new ClassNotFoundException(name);
        }
        try (InputStream in = res.openStream()) {
            byte[] bytes = in.readAllBytes();
            for (ClassTransformer t : transformers) {
                bytes = t.transform(name, bytes);
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String pkg = name.substring(0, dot);
                if (getDefinedPackage(pkg) == null) {
                    try {
                        definePackage(pkg, null, null, null, null, null, null, null);
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
