package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Choices;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MenuMods {
    record Entry(String id, String title, String note, Runnable select) {}

    private record Cumulus(Object menu, String key, String name, String screenClass, boolean vanilla) {}

    private static final String TITLE = TitleScreen.class.getName();
    private static final String SWAP = "screen:" + TITLE;
    private static final String PENTECOST = "pentecost";

    private static String selected = PENTECOST;
    private static boolean changed;

    private MenuMods() {}

    static String selected() {
        return selected;
    }

    static boolean takeChanged() {
        boolean c = changed;
        changed = false;
        return c;
    }

    private static String modName(String id) {
        ModInfo m = id == null ? null : Ui.mod(id);
        return m != null ? m.name() : id == null ? "a mod" : id;
    }

    static List<Entry> entries() {
        List<Cumulus> cumulus = cumulusMenus();
        Map<String, String> swaps = new LinkedHashMap<>(Choices.swapsOf(TITLE));
        for (Cumulus c : cumulus) {
            swaps.values().remove(c.screenClass());
        }
        String active = activeKey();
        boolean swapOn = false;
        String swapPicked = null;
        for (String owner : swaps.keySet()) {
            if (Choices.isEnabled(owner, SWAP)) {
                swapOn = true;
                if (swapPicked == null) {
                    swapPicked = owner;
                }
            }
        }
        Cumulus cumulusPicked = null;
        if (!swapOn) {
            for (Cumulus c : cumulus) {
                if (!c.vanilla() && c.key().equals(active)) {
                    cumulusPicked = c;
                }
            }
        }
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(PENTECOST, "Pentecost", "Pentecost's own main menu. "
                + "Turn Pentecost menus off under Menus for the game's menu.",
                () -> pick(null, null, cumulus, swaps)));
        for (String owner : swaps.keySet()) {
            out.add(new Entry("swap:" + owner, modName(owner), "The main menu from " + modName(owner) + ".",
                    () -> pick(owner, null, cumulus, swaps)));
        }
        for (Cumulus c : cumulus) {
            if (c.vanilla()) {
                continue;
            }
            String owner = PentecostRuntime.loader == null || c.screenClass().isEmpty() ? null
                    : PentecostRuntime.loader.modOf(c.screenClass());
            out.add(new Entry("cumulus:" + c.key(), c.name(), "The main menu from " + modName(owner != null ? owner : c.name()) + ".",
                    () -> pick(null, c, cumulus, swaps)));
        }
        selected = swapPicked != null ? "swap:" + swapPicked
                : cumulusPicked != null ? "cumulus:" + cumulusPicked.key() : PENTECOST;
        return out;
    }

    private static void pick(String swapOwner, Cumulus cum, List<Cumulus> all, Map<String, String> swaps) {
        for (String owner : swaps.keySet()) {
            Choices.setOff(owner, SWAP, !owner.equals(swapOwner));
        }
        Cumulus target = cum;
        if (target == null) {
            for (Cumulus c : all) {
                if (c.vanilla()) {
                    target = c;
                }
            }
        }
        if (target != null) {
            setActive(target);
        }
        if (cum != null && !cum.screenClass().isEmpty() && PentecostRuntime.loader != null) {
            String owner = PentecostRuntime.loader.modOf(cum.screenClass());
            if (owner != null) {
                Choices.setOff(owner, SWAP, false);
            }
        }
        selected = swapOwner != null ? "swap:" + swapOwner : cum != null ? "cumulus:" + cum.key() : PENTECOST;
        changed = true;
    }

    private static final String[] PLACES = {"", "topLeft", "bottomRight", "bottomLeft", "off"};
    private static final String[] PLACE_NAMES = {"Top right", "Top left", "Bottom right", "Bottom left", "Off"};

    static String placeLabel() {
        String now = khi.pentecost.control.Settings.get(khi.pentecost.control.Settings.MENU_BUTTONS);
        for (int i = 0; i < PLACES.length; i++) {
            if (PLACES[i].equals(now)) {
                return PLACE_NAMES[i];
            }
        }
        return PLACE_NAMES[0];
    }

    static void nextPlace() {
        String now = khi.pentecost.control.Settings.get(khi.pentecost.control.Settings.MENU_BUTTONS);
        int at = 0;
        for (int i = 0; i < PLACES.length; i++) {
            if (PLACES[i].equals(now)) {
                at = i;
            }
        }
        khi.pentecost.control.Settings.set(khi.pentecost.control.Settings.MENU_BUTTONS, PLACES[(at + 1) % PLACES.length]);
    }

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, true, Minecraft.class.getClassLoader());
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static List<Cumulus> cumulusMenus() {
        List<Cumulus> out = new ArrayList<>();
        Class<?> menus = find("com.aetherteam.cumulus.api.Menus");
        if (menus == null) {
            return out;
        }
        try {
            Object holder = menus.getField("MINECRAFT").get(null);
            Object vanilla = holder.getClass().getMethod("get").invoke(holder);
            for (Object m : (List<?>) menus.getMethod("getMenus").invoke(null)) {
                Object name = m.getClass().getMethod("getName").invoke(m);
                Object screen = m.getClass().getMethod("getScreen").invoke(m);
                out.add(new Cumulus(m, m.toString(), name instanceof Component c ? c.getString() : m.toString(),
                        screen == null ? "" : screen.getClass().getName(), m == vanilla));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot read the Cumulus menu list: " + e);
            out.clear();
        }
        return out;
    }

    private static Object activeValue() throws ReflectiveOperationException {
        Class<?> config = find("com.aetherteam.cumulus.CumulusConfig");
        Object client = config.getField("CLIENT").get(null);
        return client.getClass().getField("active_menu").get(client);
    }

    private static String activeKey() {
        if (find("com.aetherteam.cumulus.CumulusConfig") == null) {
            return "";
        }
        try {
            Object value = activeValue();
            return String.valueOf(value.getClass().getMethod("get").invoke(value));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "";
        }
    }

    private static void setActive(Cumulus c) {
        try {
            Object value = activeValue();
            value.getClass().getMethod("set", Object.class).invoke(value, c.key());
            try {
                value.getClass().getMethod("save").invoke(value);
            } catch (NoSuchMethodException e) {
            }
            Class<?> clientClass = find("com.aetherteam.cumulus.client.CumulusClient");
            Class<?> menuClass = find("com.aetherteam.cumulus.api.Menu");
            if (clientClass != null && menuClass != null) {
                Object helper = clientClass.getField("MENU_HELPER").get(null);
                helper.getClass().getMethod("setActiveMenu", menuClass).invoke(helper, c.menu());
                helper.getClass().getMethod("setShouldFade", boolean.class).invoke(helper, false);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot switch the Cumulus menu to " + c.key() + ": " + e);
        }
    }
}
