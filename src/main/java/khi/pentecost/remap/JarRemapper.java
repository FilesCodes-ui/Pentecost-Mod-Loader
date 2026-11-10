package khi.pentecost.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Rewrites every class in a jar through a remapper, on all CPU threads. */
public final class JarRemapper {
    private JarRemapper() {}

    public static void remap(Path in, Path out, Remapper remapper) throws IOException {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        try (ZipFile z = new ZipFile(in.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || isSignature(e.getName())) {
                    continue;
                }
                try (InputStream is = z.getInputStream(e)) {
                    entries.add(Map.entry(e.getName(), is.readAllBytes()));
                }
            }
        }
        List<Map.Entry<String, byte[]>> done = entries.parallelStream().map(e -> {
            if (!e.getKey().endsWith(".class")) {
                return e;
            }
            try {
                ClassReader cr = new ClassReader(e.getValue());
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassRemapper(cw, remapper), 0);
                String name = e.getKey();
                String prefix = "";
                // Multi-release jars keep classes under META-INF/versions/N/.
                int cut = name.indexOf(cr.getClassName() + ".class");
                if (cut > 0) {
                    prefix = name.substring(0, cut);
                }
                return Map.entry(prefix + remapper.map(cr.getClassName()) + ".class", cw.toByteArray());
            } catch (RuntimeException ex) {
                return e;
            }
        }).toList();

        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        Set<String> seen = new HashSet<>();
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.setLevel(1);
            for (Map.Entry<String, byte[]> e : done) {
                if (!seen.add(e.getKey())) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        return name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC");
    }
}
