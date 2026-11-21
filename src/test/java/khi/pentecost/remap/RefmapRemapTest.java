package khi.pentecost.remap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RefmapRemapTest {
    private static final String REFMAP = "{\n  \"mappings\": {\n    \"a/MixinLevelRenderer\": {\n"
            + "      \"renderChunkLayer\": \"Lnet/minecraft/client/renderer/LevelRenderer;m_172993_(I)V\"\n    }\n  }\n}\n";

    @Test
    void convertsRefmapWithoutData(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(in))) {
            z.putNextEntry(new ZipEntry("mod.mixins-refmap.json"));
            z.write(REFMAP.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Mappings.NameTable t = new Mappings.NameTable(Map.of(), Map.of("m_172993_", "renderChunkLayer"), Map.of(), 0);
        Path out = dir.resolve("out.jar");
        JarRemapper.remap(in, out, new NameRemapper(t), new TextRemapper(t));
        String s;
        try (ZipFile z = new ZipFile(out.toFile())) {
            s = new String(z.getInputStream(z.getEntry("mod.mixins-refmap.json")).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(s.contains("LevelRenderer;renderChunkLayer(I)V"), s);
        assertFalse(s.contains("m_172993_"), s);
    }
}
