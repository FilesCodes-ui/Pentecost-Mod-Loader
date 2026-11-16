package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Talking to people: messages, mail, announcements, titles, AFK and a few emotes. */
final class ChatCmds {
    private static final String C = "Chat";

    private record Mail(String from, String text) {}

    private static final Map<String, List<Mail>> MAIL = new HashMap<>();
    private static final Map<UUID, UUID> LAST = new HashMap<>();
    private static final Set<UUID> AFK = new HashSet<>();

    private ChatCmds() {}

    static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    static void broadcast(MinecraftServer server, ChatFormatting f, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(text).withStyle(f), false);
    }

    static void tell(ServerPlayer p, ChatFormatting f, String text) {
        p.sendSystemMessage(Component.literal(text).withStyle(f));
    }

    static void sound(ServerPlayer p, SoundEvent s, float pitch) {
        p.playNotifySound(s, SoundSource.PLAYERS, 1.0F, pitch);
    }

    /** A big title in the middle of a player's screen. */
    static void title(ServerPlayer p, String main, String sub, int in, int stay, int out) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
        if (sub != null && !sub.isEmpty()) {
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(sub)));
        }
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(main)));
    }

    private static void emote(String name, String face, String desc) {
        Qol.custom(C, name, 0, "[message]", desc, Qol.lit(name, 0)
                .executes(Qol.guard(c -> sayFace(c, face, "")))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(Qol.guard(c -> sayFace(c, face, StringArgumentType.getString(c, "text"))))));
    }

    private static int sayFace(CommandContext<CommandSourceStack> c, String face, String text) throws CommandSyntaxException {
        ServerPlayer p = Qol.me(c);
        Qol.say(c.getSource().getServer(), "<" + name(p) + "> " + (text.isEmpty() ? "" : text + " ") + face);
        return 1;
    }

    static void add() {
        Qol.text(C, "shout", 0, "<message>", "Shout a message to everyone", (c, t) -> {
            broadcast(c.getSource().getServer(), ChatFormatting.YELLOW, "[Shout] " + name(Qol.me(c)) + ": " + t);
            return 1;
        });

        Qol.text(C, "announce", 2, "<message>", "Announce something to everyone in chat and above their hotbar", (c, t) -> {
            MinecraftServer s = c.getSource().getServer();
            broadcast(s, ChatFormatting.GOLD, "[Announcement] " + t);
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                p.displayClientMessage(Component.literal(t).withStyle(ChatFormatting.GOLD), true);
                sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F);
            }
            return 1;
        });

        Qol.text(C, "titleall", 2, "<text>", "Show a big title on everyone's screen", (c, t) -> {
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                title(p, t, "", 10, 60, 20);
            }
            return 1;
        });

        Qol.text(C, "actionbar", 2, "<text>", "Show a line above everyone's hotbar", (c, t) -> {
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                p.displayClientMessage(Component.literal(t), true);
            }
            return 1;
        });

        Qol.text(C, "rainbow", 0, "<message>", "Say something in rainbow colours", (c, t) -> {
            ServerPlayer p = Qol.me(c);
            MutableComponent out = Component.literal("<" + name(p) + "> ");
            int n = Math.max(1, t.length());
            for (int i = 0; i < t.length(); i++) {
                int rgb = Mth.hsvToRgb(i / (float) n, 0.8F, 1.0F);
                out.append(Component.literal(String.valueOf(t.charAt(i))).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
            }
            c.getSource().getServer().getPlayerList().broadcastSystemMessage(out, false);
            return 1;
        });

        emote("shrug", "\u00AF\\_(\u30C4)_/\u00AF", "Shrug in chat");
        emote("tableflip", "(\u256F\u00B0\u25A1\u00B0)\u256F\uFE35 \u253B\u2501\u253B", "Flip a table in chat");
        emote("unflip", "\u252C\u2500\u252C \u30CE( \u309C-\u309C\u30CE)", "Put the table back");

        // ---- private messages
        Qol.playerText(C, "pm", 0, "<player> <message>", "Send a private message", (c, who, text) -> {
            ServerPlayer me = Qol.me(c);
            ServerPlayer t = Qol.one(c.getSource(), who);
            if (t == me) {
                throw new Bad("That is you.");
            }
            tell(me, ChatFormatting.GRAY, "[you -> " + name(t) + "] " + text);
            tell(t, ChatFormatting.GRAY, "[" + name(me) + " -> you] " + text);
            sound(t, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F);
            LAST.put(t.getUUID(), me.getUUID());
            LAST.put(me.getUUID(), t.getUUID());
            return 1;
        });

        Qol.text(C, "reply", 0, "<message>", "Answer the last player who messaged you with /pm", (c, text) -> {
            ServerPlayer me = Qol.me(c);
            UUID id = LAST.get(me.getUUID());
            ServerPlayer t = id == null ? null : c.getSource().getServer().getPlayerList().getPlayer(id);
            if (t == null) {
                throw new Bad("There is nobody to reply to.");
            }
            tell(me, ChatFormatting.GRAY, "[you -> " + name(t) + "] " + text);
            tell(t, ChatFormatting.GRAY, "[" + name(me) + " -> you] " + text);
            sound(t, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F);
            LAST.put(t.getUUID(), me.getUUID());
            return 1;
        });

        // ---- mail, kept until the world closes
        Qol.wordText(C, "mail", 0, "<player> <message>", "Leave a message for a player, even one who is offline (kept until the world closes)", Qol.PLAYERS, (c, who, text) -> {
            if (who.startsWith("@") || who.equals("*")) {
                throw new Bad("Mail goes to one player by name.");
            }
            ServerPlayer me = Qol.me(c);
            List<Mail> box = MAIL.computeIfAbsent(who.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
            if (box.size() >= 30) {
                throw new Bad(who + "'s mailbox is full.");
            }
            box.add(new Mail(name(me), text));
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                if (name(p).equalsIgnoreCase(who)) {
                    tell(p, ChatFormatting.AQUA, "You have new mail from " + name(me) + ". /inbox reads it.");
                    sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8F);
                }
            }
            return Qol.ok(c, "Mail left for " + who + ".");
        });

        Qol.none(C, "inbox", 0, "Read your mail", c -> {
            ServerPlayer me = Qol.me(c);
            List<Mail> box = MAIL.get(name(me).toLowerCase(Locale.ROOT));
            if (box == null || box.isEmpty()) {
                return Qol.ok(c, "No mail.");
            }
            Qol.ok(c, "You have " + box.size() + " message" + (box.size() == 1 ? "" : "s") + ":");
            for (Mail m : box) {
                Qol.ok(c, "  " + m.from() + ": " + m.text());
            }
            return box.size();
        });

        Qol.none(C, "clearmail", 0, "Delete your mail", c -> {
            List<Mail> box = MAIL.remove(name(Qol.me(c)).toLowerCase(Locale.ROOT));
            return Qol.ok(c, box == null ? "No mail to delete." : "Deleted " + box.size() + ".");
        });

        // ---- being away
        Qol.none(C, "afk", 0, "Tell everyone you are away, or back", c -> {
            ServerPlayer p = Qol.me(c);
            boolean away = AFK.add(p.getUUID());
            if (!away) {
                AFK.remove(p.getUUID());
            }
            broadcast(c.getSource().getServer(), ChatFormatting.GRAY, name(p) + (away ? " is now away." : " is back."));
            return 1;
        });

        Qol.none(C, "afklist", 0, "List who said they are away", c -> {
            List<String> names = new ArrayList<>();
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                if (AFK.contains(p.getUUID())) {
                    names.add(name(p));
                }
            }
            return Qol.ok(c, names.isEmpty() ? "Nobody is away." : "Away: " + String.join(", ", names));
        });

        // ---- the room
        Qol.none(C, "clearchat", 2, "Push the chat off everyone's screen", c -> {
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                for (int i = 0; i < 100; i++) {
                    p.sendSystemMessage(Component.literal(" "));
                }
            }
            return 1;
        });

        Qol.none(C, "motd", 0, "Show the server's message of the day", c ->
                Qol.ok(c, c.getSource().getServer().getMotd()));

        Qol.playerReq(C, "poke", 0, "Poke a player with a sound and a line above their hotbar", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            t.displayClientMessage(Component.literal(name(me) + " poked you.").withStyle(ChatFormatting.YELLOW), true);
            sound(t, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5F);
            return Qol.ok(c, "Poked " + name(t) + ".");
        });

        Qol.none(C, "beep", 0, "Play a sound for yourself", c -> {
            sound(Qol.me(c), SoundEvents.PLAYER_LEVELUP, 1.0F);
            return 1;
        });

        Qol.text(C, "sc", 2, "<message>", "Staff chat: a message only operators see", (c, t) -> {
            String from = c.getSource().getTextName();
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                if (p.hasPermissions(2)) {
                    tell(p, ChatFormatting.RED, "[Staff] " + from + ": " + t);
                }
            }
            return 1;
        });

        Qol.none(C, "staff", 0, "List the operators who are online", c -> {
            List<String> names = new ArrayList<>();
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                if (p.hasPermissions(2)) {
                    names.add(name(p));
                }
            }
            return Qol.ok(c, names.isEmpty() ? "No operators online." : "Operators online: " + String.join(", ", names));
        });
    }
}
