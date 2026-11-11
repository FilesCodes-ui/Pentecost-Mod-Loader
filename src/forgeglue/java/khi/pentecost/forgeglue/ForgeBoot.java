package khi.pentecost.forgeglue;

import com.electronwill.nightconfig.core.Config;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLanguageProvider;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ImmediateWindowHandler;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.VersionInfo;
import net.minecraftforge.fml.loading.moddiscovery.BackgroundScanHandler;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.fml.loading.moddiscovery.NightConfigWrapper;
import net.minecraftforge.fml.loading.targets.ForgeClientLaunchHandler;
import net.minecraftforge.fml.lowcodemod.LowCodeModLanguageProvider;
import net.minecraftforge.fml.mclanguageprovider.MinecraftModLanguageProvider;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.IModLanguageProvider;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Stands in for the bottom layer of Forge's loader. Forge's launcher normally finds the mod files,
 * reads them, and hands the list to the rest of Forge. Pentecost found the mods already,
 * so this class builds the same list from Pentecost's mods and hands it over.
 * From there Forge's own code runs its normal mod lifecycle.
 */
public final class ForgeBoot {
    private ForgeBoot() {}

    public static void prepare(Path gameDir, Path gameJar, Path forgeJar, List<Path> modJars,
                               String mcVersion, String forgeVersion) throws Exception {
        FMLPaths.loadAbsolutePaths(gameDir);
        FMLConfig.load();

        set("dist", Dist.CLIENT);
        set("naming", "mcp");
        set("gamePath", gameDir);
        set("versionInfo", new VersionInfo(forgeVersion, mcVersion, "20230612.114412", "net.minecraftforge"));
        set("launchHandlerName", "forgeclient");
        set("commonLaunchHandler", new ForgeClientLaunchHandler());
        set("production", true);
        set("moduleLayerManager", (IModuleLayerManager) layer -> Optional.of(ModuleLayer.boot()));

        Map<String, IModLanguageProvider> languages = new LinkedHashMap<>();
        languages.put("javafml", new FMLJavaModLanguageProvider());
        languages.put("lowcodefml", new LowCodeModLanguageProvider());
        languages.put("minecraft", new MinecraftModLanguageProvider());

        List<ModFile> files = new ArrayList<>();
        ModFile minecraft = new ModFile(SecureJar.from(gameJar), new Provider(false), ForgeBoot::minecraftInfo);
        files.add(minecraft);
        files.add(new ModFile(SecureJar.from(forgeJar), new Provider(true), ModFileParser::modsTomlParser));
        for (Path jar : modJars) {
            files.add(new ModFile(SecureJar.from(jar), new Provider(true), ModFileParser::modsTomlParser));
        }

        List<ModFile> usable = new ArrayList<>();
        for (ModFile f : files) {
            if (!f.identifyMods()) {
                continue;
            }
            List<IModLanguageProvider> loaders = new ArrayList<>();
            for (IModFileInfo.LanguageSpec spec : f.getModFileInfo().requiredLanguageLoaders()) {
                IModLanguageProvider p = languages.get(spec.languageName());
                if (p == null) {
                    throw new IllegalStateException(f.getFileName() + " needs the mod language '"
                            + spec.languageName() + "', which Pentecost does not provide yet");
                }
                loaders.add(p);
            }
            Field lf = ModFile.class.getDeclaredField("loaders");
            lf.setAccessible(true);
            lf.set(f, loaders);
            usable.add(f);
        }

        // Forge reports loading progress through this handler. Give it a progress bar to write to.
        Field progress = ImmediateWindowHandler.class.getDeclaredField("earlyProgress");
        progress.setAccessible(true);
        progress.set(null, net.minecraftforge.fml.loading.progress.StartupNotificationManager.addProgressBar("EARLY", 0));

        FMLLoader.progressWindowTick = () -> { };

        LoadingModList list = LoadingModList.of(usable, sort(usable), null);
        list.setBrokenFiles(List.of());
        set("loadingModList", list);
        FMLLoader.backgroundScanHandler = new BackgroundScanHandler(usable);
        list.addForScanning(FMLLoader.backgroundScanHandler);

        // Forge shows an early loading window through this handler. The plain window path is enough here.
        Field provider = ImmediateWindowHandler.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(null, new PlainWindow());

        System.out.println("[Pentecost] Forge layer ready: " + list.getMods().size() + " mod(s) handed to Forge "
                + forgeVersion);
    }

    private static void set(String field, Object value) throws Exception {
        Field f = FMLLoader.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    /** Orders mods so that each mod comes after the mods it depends on. Minecraft and Forge come first. */
    private static List<ModInfo> sort(List<ModFile> files) {
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (ModFile f : files) {
            for (IModInfo info : f.getModInfos()) {
                byId.putIfAbsent(info.getModId(), (ModInfo) info);
            }
        }
        Set<String> done = new LinkedHashSet<>();
        List<ModInfo> out = new ArrayList<>();
        for (String first : List.of("minecraft", "forge")) {
            visit(first, byId, done, out);
        }
        for (String id : byId.keySet()) {
            visit(id, byId, done, out);
        }
        return out;
    }

    private static void visit(String id, Map<String, ModInfo> byId, Set<String> done, List<ModInfo> out) {
        ModInfo info = byId.get(id);
        if (info == null || !done.add(id)) {
            return;
        }
        for (IModInfo.ModVersion dep : info.getDependencies()) {
            if (dep.getOrdering() != IModInfo.Ordering.BEFORE) {
                visit(dep.getModId(), byId, done, out);
            }
        }
        out.add(info);
    }

    /** Describes the game itself as a mod named "minecraft", the way Forge does. */
    private static IModFileInfo minecraftInfo(IModFile file) {
        Config conf = Config.inMemory();
        conf.set("modLoader", "minecraft");
        conf.set("loaderVersion", "1");
        conf.set("license", "Mojang Studios, All Rights Reserved");
        Config mod = Config.inMemory();
        mod.set("modId", "minecraft");
        mod.set("version", FMLLoader.versionInfo().mcVersion());
        mod.set("displayName", "Minecraft");
        mod.set("description", "Minecraft");
        conf.set("mods", List.of(mod));
        NightConfigWrapper wrapper = new NightConfigWrapper(conf);
        try {
            Method setFile = NightConfigWrapper.class.getDeclaredMethod("setFile", IModFileInfo.class);
            setFile.setAccessible(true);
            Consumer<IModFileInfo> link = info -> {
                try {
                    setFile.invoke(wrapper, info);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
            return new ModFileInfo((ModFile) file, wrapper, link, List.of());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Tells Forge how to walk the classes of a mod file. The game jar itself is not scanned. */
    private record Provider(boolean scan) implements IModProvider {
        @Override
        public String name() {
            return "pentecost";
        }

        @Override
        public void scanFile(IModFile file, Consumer<Path> pathConsumer) {
            if (!scan) {
                return;
            }
            try (Stream<Path> s = Files.find(file.getSecureJar().getRootPath(), Integer.MAX_VALUE,
                    (p, a) -> p.getNameCount() > 0 && p.getFileName().toString().endsWith(".class"))) {
                s.forEach(pathConsumer);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public void initArguments(Map<String, ?> arguments) {
        }

        @Override
        public boolean isValid(IModFile file) {
            return true;
        }
    }
}
