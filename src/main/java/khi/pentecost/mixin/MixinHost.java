package khi.pentecost.mixin;

import com.google.gson.JsonElement;
import khi.pentecost.boot.Log;
import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Starts Mixin, registers every mod's Mixin configs, and plugs Mixin into the class loader. */
public final class MixinHost {
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private MixinHost() {}

    public static void start(GameClassLoader loader, List<ModInfo> mods) throws Exception {
        PentecostMixinService.loader = loader;
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);

        int configs = 0;
        Map<String, ModInfo> owner = new HashMap<>();
        for (ModInfo m : mods) {
            for (String cfg : m.mixins()) {
                try {
                    Mixins.addConfiguration(cfg);
                    owner.put(cfg, m);
                    configs++;
                } catch (Throwable t) {
                    Log.error("Mixin config " + cfg + " of " + m.name() + " failed to register: " + t);
                }
            }
        }

        // Each config carries its mod id and the Mixin behavior its mod was built for.
        // Older mods rely on the older way Mixin finds local variables.
        for (Config raw : Mixins.getConfigs()) {
            ModInfo m = owner.get(raw.getName());
            if (m == null) {
                continue;
            }
            IMixinConfig config = raw.getConfig();
            config.decorate(FabricUtil.KEY_MOD_ID, m.id());
            config.decorate(FabricUtil.KEY_COMPATIBILITY, compatibility(m));
        }

        // Mixin starts in an early phase. Move it to the normal phase so every config applies.
        Method gotoPhase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        gotoPhase.setAccessible(true);
        gotoPhase.invoke(null, MixinEnvironment.Phase.INIT);
        gotoPhase.invoke(null, MixinEnvironment.Phase.DEFAULT);

        if (PentecostMixinService.factory == null) {
            throw new IllegalStateException("Mixin did not hand over its transformer factory");
        }
        IMixinTransformer transformer = PentecostMixinService.factory.createTransformer();

        // MixinExtras adds extra injector types many mods use. It needs the transformer to exist first.
        try {
            Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap").getMethod("init").invoke(null);
        } catch (ClassNotFoundException absent) {
            // MixinExtras is not on the classpath. Mods that need it will say so.
        }

        loader.setMixin((name, bytes) -> transformer.transformClassBytes(name, name, bytes));
        Log.info("Mixin host ready with " + configs + " config(s)");
    }

    /**
     * Picks the Mixin behavior level from the Fabric Loader version a mod asks for.
     * Fabric Loader uses the same rule: 0.16.0 and up, 0.12.0 and up, or older.
     */
    private static int compatibility(ModInfo m) {
        if (m.loader() == LoaderKind.FORGE) {
            return FabricUtil.COMPATIBILITY_0_10_0;
        }
        int[] v = loaderVersion(m);
        if (v == null) {
            return FabricUtil.COMPATIBILITY_0_9_2;
        }
        if (v[0] > 0 || v[1] >= 16) {
            return constant("COMPATIBILITY_0_16_0", FabricUtil.COMPATIBILITY_LATEST);
        }
        if (v[1] >= 12) {
            return FabricUtil.COMPATIBILITY_0_10_0;
        }
        return FabricUtil.COMPATIBILITY_0_9_2;
    }

    private static int[] loaderVersion(ModInfo m) {
        if (m.raw() == null || !m.raw().has("depends") || !m.raw().get("depends").isJsonObject()) {
            return null;
        }
        JsonElement dep = m.raw().getAsJsonObject("depends").get("fabricloader");
        if (dep == null) {
            return null;
        }
        String text = dep.isJsonArray() && !dep.getAsJsonArray().isEmpty()
                ? dep.getAsJsonArray().get(0).getAsString() : dep.isJsonPrimitive() ? dep.getAsString() : "";
        Matcher mt = VERSION.matcher(text);
        if (!mt.find()) {
            return null;
        }
        return new int[]{Integer.parseInt(mt.group(1)), Integer.parseInt(mt.group(2))};
    }

    private static int constant(String name, int fallback) {
        try {
            return FabricUtil.class.getField(name).getInt(null);
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }
}
