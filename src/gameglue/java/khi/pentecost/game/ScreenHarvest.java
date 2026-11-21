package khi.pentecost.game;

import khi.pentecost.core.SettingItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ScreenHarvest {
    private final Screen screen;
    private final List<SettingItem> items = new ArrayList<>();
    private AbstractButton save;
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    private ScreenHarvest(Screen screen) {
        this.screen = screen;
    }

    static ScreenHarvest of(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        try {
            screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        } catch (RuntimeException | LinkageError e) {
            khi.pentecost.boot.Log.warn("Could not read the settings screen " + screen.getClass().getName() + ": " + e);
            return null;
        }
        ScreenHarvest h = new ScreenHarvest(screen);
        String group = clean(screen.getTitle().getString());
        h.walk(screen, group.isEmpty() ? "Settings" : group, null, null, 0);
        return h;
    }

    List<SettingItem> items() {
        return items;
    }

    Screen screen() {
        return screen;
    }

    void leave() {
        AbstractButton b = save;
        if (b != null) {
            try {
                b.onPress();
            } catch (RuntimeException | LinkageError e) {
                khi.pentecost.boot.Log.warn("The settings screen's save button failed: " + e);
            }
        } else {
            try {
                screen.removed();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private void walk(GuiEventListener node, String group, String label, Object entry, int depth) {
        if (depth > 12 || !seen.add(node)) {
            return;
        }
        if (node instanceof AbstractWidget w) {
            add(w, group, label, entry);
        }
        if (node instanceof ContainerEventHandler c) {
            String inner = label;
            Object innerEntry = entry;
            if (!(node instanceof Screen) && !(node instanceof AbstractWidget)) {
                String own = labelOf(node);
                if (own != null) {
                    inner = own;
                    innerEntry = node;
                }
            }
            List<? extends GuiEventListener> kids;
            try {
                kids = c.children();
            } catch (RuntimeException e) {
                return;
            }
            for (GuiEventListener k : new ArrayList<>(kids)) {
                walk(k, group, inner, innerEntry, depth + 1);
            }
        }
    }

    private void add(AbstractWidget w, String group, String label, Object entry) {
        String text = clean(w.getMessage().getString());
        if (isReset(w)) {
            return;
        }
        String title = label != null && !label.isEmpty() && !label.equals(text) ? label : text;
        if (w instanceof AbstractButton b && isSave(w)) {
            save = b;
            return;
        }
        if (isBack(w)) {
            return;
        }
        if (w instanceof AbstractSliderButton s) {
            items.add(SettingItem.slider(group, title.isEmpty() ? "Slider" : stripValue(title), "", 0, 1000, 5,
                    () -> (int) Math.round(sliderValue(s) * 1000),
                    n -> setSlider(s, n / 1000.0), () -> valuePart(clean(s.getMessage().getString()))));
        } else if (w instanceof CycleButton<?> cb) {
            items.add(SettingItem.choice(group, stripValue(title), "", () -> valuePart(clean(cb.getMessage().getString())),
                    cb::onPress));
        } else if (w instanceof Checkbox box) {
            items.add(SettingItem.toggle(group, title, "", box::selected, box::onPress));
        } else if (w instanceof AbstractButton b) {
            if (text.isEmpty()) {
                return;
            }
            if (label != null && !label.isEmpty() && !label.equals(text)) {
                items.add(SettingItem.choice(group, label, "", () -> clean(b.getMessage().getString()), b::onPress));
            } else {
                items.add(SettingItem.button(group, text, "", "Press", b::onPress));
            }
        } else if (w instanceof EditBox e) {
            SettingItem number = numberBox(e, group, title.isEmpty() ? "Number" : title, entry);
            items.add(number != null ? number
                    : SettingItem.info(group, title.isEmpty() ? "Text" : title, "Edit it in the mod's own screen.", e::getValue));
        }
    }

    private static boolean isReset(AbstractWidget w) {
        String key = keyOf(w);
        String t = clean(w.getMessage().getString()).toLowerCase(Locale.ROOT);
        return key.equals("controls.reset") || key.endsWith(".reset") || t.equals("reset") || t.equals("reset to default");
    }

    private static SettingItem numberBox(EditBox e, String group, String title, Object entry) {
        String v = e.getValue().trim();
        double now;
        try {
            now = Double.parseDouble(v);
        } catch (NumberFormatException ex) {
            return null;
        }
        boolean whole = !v.contains(".") && !v.contains("e") && !v.contains("E");
        double[] lim = limits(entry);
        double lo = lim != null ? lim[0] : Math.min(0, now);
        double hi = lim != null ? lim[1] : Math.max(Math.abs(now) * 4, whole ? 64 : 1);
        if (!(hi > lo) || Double.isInfinite(hi) || Double.isInfinite(lo) || hi - lo > 1e7) {
            lo = Math.min(0, now);
            hi = Math.max(Math.abs(now) * 4, whole ? 64 : 1);
        }
        final double min = lo, max = hi;
        if (whole && max - min <= 100_000) {
            int a = (int) Math.floor(min), b = (int) Math.ceil(max);
            int step = Math.max(1, (b - a) / 200);
            return SettingItem.slider(group, title, "", a, b, step,
                    () -> (int) Math.round(parse(e.getValue(), now)),
                    n -> type(e, Integer.toString(n)), () -> e.getValue().trim());
        }
        return SettingItem.slider(group, title, "", 0, 1000, 5,
                () -> (int) Math.round((parse(e.getValue(), now) - min) / (max - min) * 1000),
                n -> {
                    double d = min + (max - min) * n / 1000.0;
                    type(e, whole ? Long.toString(Math.round(d)) : String.format(Locale.ROOT, "%.3f", d).replaceAll("0+$", "").replaceAll("\\.$", ".0"));
                }, () -> e.getValue().trim());
    }

    private static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void type(EditBox e, String text) {
        try {
            e.setValue(text);
        } catch (RuntimeException | LinkageError ex) {
            khi.pentecost.boot.Log.warn("Could not set a mod's number box: " + ex);
        }
    }

    private static double[] limits(Object entry) {
        if (entry == null) {
            return null;
        }
        double[] own = limitsOf(entry);
        if (own != null) {
            return own;
        }
        for (Class<?> k = entry.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType().isPrimitive() || f.getType() == String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object o = f.get(entry);
                    if (o == null || o instanceof GuiEventListener || o instanceof Component) {
                        continue;
                    }
                    double[] l = limitsOf(o);
                    if (l != null) {
                        return l;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static double[] limitsOf(Object o) {
        Double lo = number(o, "getMin", "getMinValue", "min", "minimum", "minValue");
        Double hi = number(o, "getMax", "getMaxValue", "max", "maximum", "maxValue");
        return lo != null && hi != null && hi > lo ? new double[]{lo, hi} : null;
    }

    private static Double number(Object o, String... names) {
        for (String n : names) {
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                try {
                    Method m = k.getDeclaredMethod(n);
                    m.setAccessible(true);
                    if (m.invoke(o) instanceof Number v) {
                        return v.doubleValue();
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
                try {
                    Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    if (f.get(o) instanceof Number v) {
                        return v.doubleValue();
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static String labelOf(Object entry) {
        for (String name : new String[]{"getFieldName", "getDisplayName", "getName", "name", "getLabel", "getTitle"}) {
            try {
                Method m = entry.getClass().getMethod(name);
                if (m.getParameterCount() == 0) {
                    Object v = m.invoke(entry);
                    if (v instanceof Component c && !c.getString().isBlank()) {
                        return clean(c.getString());
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
        for (Class<?> k = entry.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Component.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(entry);
                        if (v instanceof Component c && !c.getString().isBlank()) {
                            return clean(c.getString());
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static boolean isSave(AbstractWidget w) {
        String key = keyOf(w);
        String t = w.getMessage().getString().toLowerCase(Locale.ROOT);
        return key.equals("gui.done") || key.equals("gui.ok") || t.startsWith("save") || t.equals("done") || t.equals("apply")
                || t.contains("save & quit") || t.contains("save and quit");
    }

    private static boolean isBack(AbstractWidget w) {
        String key = keyOf(w);
        String t = w.getMessage().getString().toLowerCase(Locale.ROOT);
        return key.equals("gui.cancel") || key.equals("gui.back") || t.equals("cancel") || t.equals("back")
                || t.equals("close") || t.contains("discard");
    }

    private static String keyOf(AbstractWidget w) {
        return w.getMessage().getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    private static double sliderValue(AbstractSliderButton s) {
        try {
            Field f = field(s.getClass(), "value");
            return f == null ? 0 : f.getDouble(s);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static void setSlider(AbstractSliderButton s, double v) {
        try {
            Field f = field(s.getClass(), "value");
            if (f == null) {
                return;
            }
            f.setDouble(s, Math.max(0, Math.min(1, v)));
            call(s, "applyValue");
            call(s, "updateMessage");
        } catch (ReflectiveOperationException | RuntimeException e) {
            khi.pentecost.boot.Log.warn("Could not move a mod's slider: " + e);
        }
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static void call(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                m.invoke(o);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
    }

    private static String stripValue(String s) {
        int colon = s.indexOf(':');
        return colon > 0 ? s.substring(0, colon).trim() : s;
    }

    private static String valuePart(String s) {
        int colon = s.indexOf(':');
        return colon > 0 ? s.substring(colon + 1).trim() : s;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("§.", "").trim();
    }
}
