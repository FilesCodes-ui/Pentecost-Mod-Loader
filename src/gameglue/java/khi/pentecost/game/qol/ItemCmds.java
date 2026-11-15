package khi.pentecost.game.qol;

import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The item in your hand and what is in your pockets. */
final class ItemCmds {
    private static final String C = "Items";

    private ItemCmds() {}

    private static ItemStack held(ServerPlayer p) {
        ItemStack s = p.getMainHandItem();
        if (s.isEmpty()) {
            throw new Bad("Hold an item first.");
        }
        return s;
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) {
            p.drop(s, false);
        }
    }

    private static int repair(Iterable<ItemStack> stacks) {
        int n = 0;
        for (ItemStack s : stacks) {
            if (!s.isEmpty() && s.isDamaged()) {
                s.setDamageValue(0);
                n++;
            }
        }
        return n;
    }

    static void add() {
        Qol.none(C, "repair", 2, "Repair the item in your hand", c -> {
            ItemStack s = held(Qol.me(c));
            if (!s.isDamageableItem()) {
                throw new Bad("That item does not wear out.");
            }
            s.setDamageValue(0);
            return Qol.ok(c, "Repaired.");
        });

        Qol.none(C, "repairall", 2, "Repair everything you carry and wear", c -> {
            Inventory inv = Qol.me(c).getInventory();
            int n = repair(inv.items) + repair(inv.armor) + repair(inv.offhand);
            return Qol.ok(c, "Repaired " + n + " item" + (n == 1 ? "" : "s") + ".");
        });

        Qol.none(C, "durability", 0, "Show how much is left on the item in your hand", c -> {
            ItemStack s = held(Qol.me(c));
            if (!s.isDamageableItem()) {
                throw new Bad("That item does not wear out.");
            }
            int left = s.getMaxDamage() - s.getDamageValue();
            return Qol.ok(c, left + " of " + s.getMaxDamage() + " uses left (" + Math.round(100.0 * left / s.getMaxDamage()) + "%).");
        });

        Qol.none(C, "hat", 0, "Wear the item in your hand on your head", c -> {
            ServerPlayer p = Qol.me(c);
            ItemStack s = held(p);
            ItemStack old = p.getItemBySlot(EquipmentSlot.HEAD);
            p.setItemSlot(EquipmentSlot.HEAD, s.copy());
            p.setItemInHand(InteractionHand.MAIN_HAND, old);
            return Qol.ok(c, "Nice hat.");
        });

        Qol.none(C, "more", 2, "Fill the stack in your hand", c -> {
            ItemStack s = held(Qol.me(c));
            s.setCount(s.getMaxStackSize());
            return Qol.ok(c, "Stack of " + s.getCount() + ".");
        });

        Qol.none(C, "dupe", 2, "Get a copy of the item in your hand", c -> {
            ServerPlayer p = Qol.me(c);
            give(p, held(p).copy());
            return Qol.ok(c, "Copied.");
        });

        Qol.none(C, "clearhand", 0, "Delete the item in your hand", c -> {
            ServerPlayer p = Qol.me(c);
            held(p);
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return Qol.ok(c, "Gone.");
        });

        Qol.text(C, "rename", 2, "<name>", "Rename the item in your hand", (c, t) -> {
            held(Qol.me(c)).setHoverName(Component.literal(t));
            return Qol.ok(c, "Renamed to " + t + ".");
        });

        Qol.none(C, "unbreakable", 2, "Make the item in your hand unbreakable, or ordinary again", c -> {
            ItemStack s = held(Qol.me(c));
            boolean on = !s.hasTag() || !s.getTag().getBoolean("Unbreakable");
            if (on) {
                s.getOrCreateTag().putBoolean("Unbreakable", true);
            } else {
                s.getTag().remove("Unbreakable");
            }
            return Qol.ok(c, on ? "Unbreakable." : "Breakable again.");
        });

        Qol.word(C, "head", 2, "[player name]", "Get a player's head (yours when no name is given)", true, Qol.PLAYERS, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            String owner = n == null ? p.getGameProfile().getName() : n;
            ItemStack s = new ItemStack(Items.PLAYER_HEAD);
            s.getOrCreateTag().putString("SkullOwner", owner);
            give(p, s);
            return Qol.ok(c, "Here is the head of " + owner + ".");
        });

        Qol.none(C, "sort", 0, "Sort and combine the stacks in your inventory (not the hotbar)", c -> {
            ServerPlayer p = Qol.me(c);
            Inventory inv = p.getInventory();
            List<ItemStack> merged = new ArrayList<>();
            for (int i = 9; i < 36; i++) {
                ItemStack s = inv.items.get(i).copy();
                inv.items.set(i, ItemStack.EMPTY);
                if (s.isEmpty()) {
                    continue;
                }
                for (ItemStack m : merged) {
                    if (ItemStack.isSameItemSameTags(m, s) && m.getCount() < m.getMaxStackSize()) {
                        int move = Math.min(s.getCount(), m.getMaxStackSize() - m.getCount());
                        m.grow(move);
                        s.shrink(move);
                    }
                }
                if (!s.isEmpty()) {
                    merged.add(s);
                }
            }
            merged.sort(Comparator.comparing((ItemStack s) -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
                    .thenComparing(s -> -s.getCount()));
            for (int i = 0; i < merged.size(); i++) {
                inv.items.set(9 + i, merged.get(i));
            }
            p.containerMenu.broadcastChanges();
            return Qol.ok(c, "Sorted.");
        });
    }
}
