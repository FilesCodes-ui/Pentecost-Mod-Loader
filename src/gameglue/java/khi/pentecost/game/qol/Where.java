package khi.pentecost.game.qol;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Where {
    public record Spot(ServerLevel level, Vec3 pos, float yRot, float xRot) {}

    private static final Pattern PLAYER_AXIS = Pattern.compile("^([A-Za-z0-9_]{1,16})\\.([xyzXYZ])([+-]\\d+(?:\\.\\d+)?)?$");
    private static final String HELP = "Give a player or place name (Steve, spawn, bed, death, home:name, warp:name), "
            + "or x y z. Any number can be ~ for here, or a player's coordinate such as Steve.x or Steve.y+5.";

    private Where() {}

    public static Spot of(ServerPlayer p) {
        return new Spot(p.serverLevel(), p.position(), p.getYRot(), p.getXRot());
    }

    public static Spot of(CommandSourceStack src) {
        Vec2 rot = src.getRotation();
        return new Spot(src.getLevel(), src.getPosition(), rot.y, rot.x);
    }

    public static String describe(Spot s) {
        return Math.round(s.pos().x) + " " + Math.round(s.pos().y) + " " + Math.round(s.pos().z) + " in " + dimension(s.level());
    }

    public static String dimension(ServerLevel l) {
        String d = l.dimension().location().getPath();
        return switch (d) {
            case "overworld" -> "the Overworld";
            case "the_nether" -> "the Nether";
            case "the_end" -> "the End";
            default -> l.dimension().location().toString();
        };
    }

    public static Spot look(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = Qol.self(src);
        HitResult hit = p.pick(300.0, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            throw new Bad("You are not looking at a block (it has to be within 300 blocks).");
        }
        BlockPos bp = ((BlockHitResult) hit).getBlockPos().above();
        return new Spot(p.serverLevel(), Vec3.atBottomCenterOf(bp), p.getYRot(), p.getXRot());
    }

    public static Spot resolve(CommandSourceStack src, String text) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String[] t = text.trim().split("\\s+");
        if (t.length == 1) {
            return one(src, t[0]);
        }
        if (t.length == 3) {
            Vec3 here = src.getPosition();
            double x = coordinate(src, t[0], 'x', here.x, true);
            double y = coordinate(src, t[1], 'y', here.y, false);
            double z = coordinate(src, t[2], 'z', here.z, true);
            ServerLevel level = levelOf(src, t);
            Vec2 rot = src.getRotation();
            return new Spot(level, new Vec3(x, y, z), rot.y, rot.x);
        }
        if (t.length == 4) {
            ServerPlayer p = Qol.one(src, t[0]);
            double dx = offset(t[1]);
            double dy = offset(t[2]);
            double dz = offset(t[3]);
            return new Spot(p.serverLevel(), p.position().add(dx, dy, dz), p.getYRot(), p.getXRot());
        }
        throw new Bad(HELP);
    }

    private static ServerLevel levelOf(CommandSourceStack src, String[] t) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        for (String token : t) {
            Matcher m = PLAYER_AXIS.matcher(token);
            if (m.matches()) {
                return Qol.one(src, m.group(1)).serverLevel();
            }
        }
        return src.getLevel();
    }

    private static double offset(String token) {
        if (token.equals("~")) {
            return 0.0;
        }
        try {
            return Double.parseDouble(token.startsWith("~") ? token.substring(1) : token);
        } catch (NumberFormatException e) {
            throw new Bad("\"" + token + "\" is not a number.");
        }
    }

    private static double coordinate(CommandSourceStack src, String token, char axis, double here, boolean centre)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (token.startsWith("~")) {
            return here + (token.length() == 1 ? 0.0 : number(token.substring(1)));
        }
        Matcher m = PLAYER_AXIS.matcher(token);
        if (m.matches()) {
            ServerPlayer p = Qol.one(src, m.group(1));
            char a = Character.toLowerCase(m.group(2).charAt(0));
            double v = a == 'x' ? p.getX() : a == 'y' ? p.getY() : p.getZ();
            return v + (m.group(3) == null ? 0.0 : number(m.group(3)));
        }
        double v = number(token);
        return centre && !token.contains(".") ? v + 0.5 : v;
    }

    private static double number(String token) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new Bad("\"" + token + "\" is not a number, a ~ or a player's coordinate like Steve.x. " + HELP);
        }
    }

    private static Spot one(CommandSourceStack src, String token) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        MinecraftServer server = src.getServer();
        String key = token.toLowerCase(Locale.ROOT);
        switch (key) {
            case "me", "@s", "here" -> {
                return of(src);
            }
            case "look" -> {
                return look(src);
            }
            case "spawn" -> {
                ServerLevel o = server.overworld();
                return new Spot(o, Vec3.atBottomCenterOf(o.getSharedSpawnPos()), 0.0F, 0.0F);
            }
            case "bed" -> {
                ServerPlayer p = Qol.self(src);
                BlockPos bed = p.getRespawnPosition();
                ServerLevel l = bed == null ? null : server.getLevel(p.getRespawnDimension());
                if (bed == null || l == null) {
                    throw new Bad("You have no bed or respawn anchor set.");
                }
                return new Spot(l, Vec3.atBottomCenterOf(bed), p.getYRot(), 0.0F);
            }
            case "death" -> {
                ServerPlayer p = Qol.self(src);
                var death = p.getLastDeathLocation();
                if (death.isEmpty()) {
                    throw new Bad("You have not died yet.");
                }
                ServerLevel l = server.getLevel(death.get().dimension());
                if (l == null) {
                    throw new Bad("The world you died in is not loaded.");
                }
                return new Spot(l, Vec3.atBottomCenterOf(death.get().pos()), p.getYRot(), 0.0F);
            }
            case "back" -> {
                Spot s = Store.back(Qol.self(src));
                if (s == null) {
                    throw new Bad("There is no earlier place to go back to.");
                }
                return s;
            }
            default -> {
            }
        }
        if (key.startsWith("home:")) {
            Spot s = Store.home(Qol.self(src), token.substring(5));
            if (s == null) {
                throw new Bad("You have no home called " + token.substring(5) + ".");
            }
            return s;
        }
        if (key.startsWith("warp:")) {
            Spot s = Store.warp(token.substring(5));
            if (s == null) {
                throw new Bad("There is no warp called " + token.substring(5) + ".");
            }
            return s;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(token)) {
                return of(p);
            }
        }
        throw new Bad("\"" + token + "\" is not an online player or a known place. " + HELP);
    }

    public static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        String rest = b.getRemaining();
        int done = 0;
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == ' ') {
                done++;
            }
        }
        SuggestionsBuilder sb = b.createOffset(b.getStart() + rest.lastIndexOf(' ') + 1);
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
            names.add(p.getGameProfile().getName());
        }
        List<String> out = new ArrayList<>();
        out.add("~");
        if (done == 0) {
            out.addAll(names);
            for (String n : names) {
                out.add(n + ".x");
            }
            out.addAll(List.of("me", "spawn", "bed", "death", "back", "look"));
        } else if (done <= 2) {
            String axis = done == 1 ? ".y" : ".z";
            for (String n : names) {
                out.add(n + axis);
            }
        }
        return SharedSuggestionProvider.suggest(out, sb);
    }
}
