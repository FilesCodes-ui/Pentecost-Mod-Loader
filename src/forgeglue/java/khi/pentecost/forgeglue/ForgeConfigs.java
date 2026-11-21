package khi.pentecost.forgeglue;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import khi.pentecost.core.SettingItem;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ForgeConfigs {
    private ForgeConfigs() {}

    public static boolean has(String modId) {
        for (ModConfig.Type type : ModConfig.Type.values()) {
            Set<ModConfig> set = ConfigTracker.INSTANCE.configSets().get(type);
            if (set == null) {
                continue;
            }
            for (ModConfig mc : set) {
                if (mc.getModId().equals(modId) && mc.getSpec() instanceof ForgeConfigSpec spec && spec.isLoaded()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static List<SettingItem> items(String modId) {
        List<SettingItem> out = new ArrayList<>();
        for (ModConfig.Type type : ModConfig.Type.values()) {
            Set<ModConfig> set = ConfigTracker.INSTANCE.configSets().get(type);
            if (set == null) {
                continue;
            }
            for (ModConfig mc : set) {
                if (!mc.getModId().equals(modId) || !(mc.getSpec() instanceof ForgeConfigSpec spec) || !spec.isLoaded()) {
                    continue;
                }
                String file = type.name().charAt(0) + type.name().substring(1).toLowerCase(Locale.ROOT);
                walk(spec, spec.getValues(), new ArrayList<>(), file, out);
            }
        }
        return out;
    }

    private static void walk(ForgeConfigSpec spec, UnmodifiableConfig cfg, List<String> path, String file,
                             List<SettingItem> out) {
        for (Map.Entry<String, Object> e : cfg.valueMap().entrySet()) {
            List<String> here = new ArrayList<>(path);
            here.add(e.getKey());
            Object v = e.getValue();
            if (v instanceof UnmodifiableConfig sub) {
                walk(spec, sub, here, file, out);
                continue;
            }
            if (!(v instanceof ForgeConfigSpec.ConfigValue<?> cv)) {
                continue;
            }
            String group = path.isEmpty() ? file : file + ": " + nice(String.join(" / ", path));
            String title = nice(e.getKey());
            ForgeConfigSpec.ValueSpec vs = null;
            try {
                vs = spec.getRaw(here);
            } catch (RuntimeException ignored) {
            }
            String note = vs != null && vs.getComment() != null ? firstLines(vs.getComment()) : "";
            try {
                out.add(row(cv, vs, group, title, note));
            } catch (RuntimeException | LinkageError ex) {
                out.add(SettingItem.info(group, title, note, () -> String.valueOf(safeGet(cv))));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SettingItem row(ForgeConfigSpec.ConfigValue<?> cv, ForgeConfigSpec.ValueSpec vs, String group,
                                   String title, String note) {
        Object current = cv.get();
        if (current instanceof Boolean) {
            ForgeConfigSpec.ConfigValue<Boolean> b = (ForgeConfigSpec.ConfigValue<Boolean>) cv;
            return SettingItem.toggle(group, title, note, b::get, () -> {
                b.set(!b.get());
                b.save();
            });
        }
        if (current instanceof Enum<?> en) {
            ForgeConfigSpec.ConfigValue<Enum> ev = (ForgeConfigSpec.ConfigValue<Enum>) cv;
            Object[] all = en.getDeclaringClass().getEnumConstants();
            return SettingItem.choice(group, title, note, () -> nice(ev.get().name()), () -> {
                int i = Arrays.asList(all).indexOf(ev.get());
                ev.set((Enum) all[(i + 1) % all.length]);
                ev.save();
            });
        }
        Object min = null;
        Object max = null;
        if (vs != null && vs.getRange() != null) {
            min = vs.getRange().getMin();
            max = vs.getRange().getMax();
        }
        if (current instanceof Integer cur) {
            ForgeConfigSpec.ConfigValue<Integer> iv = (ForgeConfigSpec.ConfigValue<Integer>) cv;
            int lo = min instanceof Integer a ? a : Math.min(0, cur);
            int hi = max instanceof Integer b ? b : Integer.MAX_VALUE;
            int top = (long) hi - lo <= 100_000 ? hi : (int) Math.min(hi, Math.max((long) lo + 10, (long) Math.abs(cur) * 4L));
            int step = Math.max(1, (top - lo) / 200);
            return SettingItem.slider(group, title, note, lo, top, step, iv::get, n -> {
                iv.set(n);
                iv.save();
            }, () -> String.valueOf(iv.get()));
        }
        if (current instanceof Double cur) {
            ForgeConfigSpec.ConfigValue<Double> dv = (ForgeConfigSpec.ConfigValue<Double>) cv;
            double lo = min instanceof Double a && !Double.isInfinite(a) ? a : Math.min(0, cur);
            double hi = max instanceof Double b && !Double.isInfinite(b) ? b : Double.MAX_VALUE;
            double top = hi - lo < 1e6 ? hi : Math.min(hi, Math.max(lo + 10, Math.abs(cur) * 4));
            if (top <= lo) {
                top = lo + 1;
            }
            double span = top - lo;
            return SettingItem.slider(group, title, note, 0, 1000, 1,
                    () -> (int) Math.round(Math.max(0, Math.min(1, (dv.get() - lo) / span)) * 1000),
                    n -> {
                        dv.set(lo + span * n / 1000.0);
                        dv.save();
                    }, () -> trim(dv.get()));
        }
        return SettingItem.info(group, title, note, () -> String.valueOf(safeGet(cv)));
    }

    private static Object safeGet(ForgeConfigSpec.ConfigValue<?> cv) {
        try {
            return cv.get();
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.3f", d);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String firstLines(String comment) {
        String c = comment.replace('\n', ' ').replaceAll("\\s*Range:.*$", "").replaceAll("\\s*Allowed Values:.*$", "")
                .replaceAll("^[\\s.]+", "").trim();
        return c.length() > 220 ? c.substring(0, 217) + "..." : c;
    }

    static String nice(String key) {
        String s = key.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? key : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
