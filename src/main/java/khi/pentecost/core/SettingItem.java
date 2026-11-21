package khi.pentecost.core;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public record SettingItem(Kind kind, String group, String title, String note, BooleanSupplier on, Runnable action,
                          Supplier<String> value, int min, int max, int step, IntSupplier get, IntConsumer set) {
    public enum Kind { TOGGLE, CHOICE, SLIDER, BUTTON, INFO }

    public static SettingItem toggle(String group, String title, String note, BooleanSupplier on, Runnable flip) {
        return new SettingItem(Kind.TOGGLE, group, title, note, on, flip, null, 0, 0, 0, null, null);
    }

    public static SettingItem choice(String group, String title, String note, Supplier<String> value, Runnable next) {
        return new SettingItem(Kind.CHOICE, group, title, note, null, next, value, 0, 0, 0, null, null);
    }

    public static SettingItem slider(String group, String title, String note, int min, int max, int step,
                                     IntSupplier get, IntConsumer set, Supplier<String> text) {
        return new SettingItem(Kind.SLIDER, group, title, note, null, null, text, min, max, step, get, set);
    }

    public static SettingItem button(String group, String title, String note, String label, Runnable press) {
        return new SettingItem(Kind.BUTTON, group, title, note, null, press, () -> label, 0, 0, 0, null, null);
    }

    public static SettingItem info(String group, String title, String note, Supplier<String> value) {
        return new SettingItem(Kind.INFO, group, title, note, null, null, value, 0, 0, 0, null, null);
    }
}
