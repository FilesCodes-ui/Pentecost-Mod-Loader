package khi.pentecost.game.qol;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;

/** Workstations and chests you can open from anywhere. Each one stays open however far you are from a block. */
final class MenuCmds {
    private static final String C = "Menus";

    private interface Maker {
        AbstractContainerMenu make(int id, Inventory inv);
    }

    /** The 36 slots of someone's inventory as a chest: the main grid first, the hotbar last. */
    private static final class InventoryView implements Container {
        private final Inventory inv;

        InventoryView(Inventory inv) {
            this.inv = inv;
        }

        private static int slot(int i) {
            return i < 27 ? i + 9 : i - 27;
        }

        @Override
        public int getContainerSize() {
            return 36;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < 36; i++) {
                if (!inv.getItem(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int i) {
            return inv.getItem(slot(i));
        }

        @Override
        public ItemStack removeItem(int i, int n) {
            return inv.removeItem(slot(i), n);
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) {
            return inv.removeItemNoUpdate(slot(i));
        }

        @Override
        public void setItem(int i, ItemStack s) {
            inv.setItem(slot(i), s);
        }

        @Override
        public void setChanged() {
            inv.setChanged();
        }

        @Override
        public boolean stillValid(Player p) {
            return true;
        }

        @Override
        public void clearContent() {
        }
    }

    private MenuCmds() {}

    private static void open(ServerPlayer p, String titleKey, Maker m) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> m.make(id, inv), Component.translatable(titleKey)));
    }

    private static void station(String name, String desc, String titleKey, Maker m) {
        Qol.none(C, name, 2, desc, c -> {
            open(Qol.me(c), titleKey, m);
            return 1;
        });
    }

    static void add() {
        station("craft", "Open a crafting table", "container.crafting", (id, inv) -> new CraftingMenu(id, inv, ContainerLevelAccess.NULL) {
            @Override
            public boolean stillValid(Player p) {
                return true;
            }
        });
        station("anvil", "Open an anvil", "container.repair", (id, inv) -> new AnvilMenu(id, inv, ContainerLevelAccess.NULL) {
            @Override
            public boolean stillValid(Player p) {
                return true;
            }
        });
        station("grind", "Open a grindstone", "container.grindstone_title", (id, inv) -> new GrindstoneMenu(id, inv, ContainerLevelAccess.NULL) {
            @Override
            public boolean stillValid(Player p) {
                return true;
            }
        });
        station("loom", "Open a loom", "container.loom", (id, inv) -> new LoomMenu(id, inv, ContainerLevelAccess.NULL) {
            @Override
            public boolean stillValid(Player p) {
                return true;
            }
        });
        station("cart", "Open a cartography table", "container.cartography_table", (id, inv) -> new CartographyTableMenu(id, inv, ContainerLevelAccess.NULL) {
            @Override
            public boolean stillValid(Player p) {
                return true;
            }
        });
        station("stonecut", "Open a stonecutter", "container.stonecutter", (id, inv) -> new StonecutterMenu(id, inv, ContainerLevelAccess.NULL) {
            @Override
            public boolean stillValid(Player p) {
                return true;
            }
        });
        station("smith", "Open a smithing table", "container.upgrade", (id, inv) -> new SmithingMenu(id, inv, ContainerLevelAccess.NULL) {
            @Override
            public boolean stillValid(Player p) {
                return true;
            }
        });
        station("enchtable", "Open an enchanting table (no bookshelves, so low levels only)", "container.enchant",
                (id, inv) -> new EnchantmentMenu(id, inv, ContainerLevelAccess.NULL) {
                    @Override
                    public boolean stillValid(Player p) {
                        return true;
                    }
                });

        Qol.player(C, "ec", 2, "Open your ender chest, or another player's", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            open(me, "container.enderchest", (id, inv) -> ChestMenu.threeRows(id, inv, t.getEnderChestInventory()));
            return 1;
        });

        Qol.playerReq(C, "invsee", 2, "Open a player's inventory and change it", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            if (t == me) {
                throw new Qol.Bad("That is your own inventory.");
            }
            me.openMenu(new SimpleMenuProvider((id, inv, pl) ->
                    new ChestMenu(MenuType.GENERIC_9x4, id, inv, new InventoryView(t.getInventory()), 4),
                    Component.literal(t.getGameProfile().getName() + "'s inventory")));
            return 1;
        });

        Qol.none(C, "trash", 0, "Open a bin: whatever you leave in it is gone when you close it", c -> {
            ServerPlayer me = Qol.me(c);
            me.openMenu(new SimpleMenuProvider((id, inv, pl) ->
                    new ChestMenu(MenuType.GENERIC_9x3, id, inv, new SimpleContainer(27), 3), Component.literal("Trash (emptied when closed)")));
            return 1;
        });
    }
}
