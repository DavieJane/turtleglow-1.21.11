package com.example.turtleglow;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class TurtleGlow implements ModInitializer {
    private static final String TEAM_NAME = "turtleglow";
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("turtleglow.properties");

    private static volatile Formatting glowColor = Formatting.GREEN;
    private static volatile boolean enabled = true;
    // Remembers a player's original team so we can put them back afterwards.
    private static final Map<UUID, String> previousTeams = new HashMap<>();

    public static Formatting getColor() { return glowColor; }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        enabled = value;
        saveConfig();
    }

    public static void setColor(Formatting color) {
        if (color != null && color.isColor()) {
            glowColor = color;
            saveConfig();
        }
    }

        @Override
    public void onInitialize() {
        loadConfig();
        registerCommands();

        // Check every half second.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 10 != 0) return;

            Scoreboard sb = server.getScoreboard();
            Team team = sb.getTeam(TEAM_NAME);
            if (team == null) team = sb.addTeam(TEAM_NAME);
            if (team.getColor() != glowColor) team.setColor(glowColor);

            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                String name = p.getNameForScoreboard();
                Team current = sb.getScoreHolderTeam(name);

                if (isTurtled(p)) {
                    if (current != team) {
                        if (current != null) previousTeams.put(p.getUuid(), current.getName());
                        sb.addScoreHolderToTeam(name, team);
                    }
                    // Short, invisible glow that is refreshed while turtled.
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, true, false, false));
                } else if (current == team) {
                    sb.removeScoreHolderFromTeam(name, team);
                    String prev = previousTeams.remove(p.getUuid());
                    if (prev != null) {
                        Team old = sb.getTeam(prev);
                        if (old != null) sb.addScoreHolderToTeam(name, old);
                    }
                }
            }
        });
    }

    /**
     * /turtleglow                 - show status
     * /turtleglow on | off | toggle
     * /turtleglow color [color]   - show or set the glow color
     * Requires operator (gamemaster) permission.
     */
    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("turtleglow")
                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Turtle Glow is " + (enabled ? "ON" : "OFF")
                        + ", color: " + glowColor.getName()), false);
                    return 1;
                })
                .then(CommandManager.literal("on").executes(ctx -> {
                    setEnabled(true);
                    ctx.getSource().sendFeedback(() -> Text.literal("Turtle Glow enabled"), true);
                    return 1;
                }))
                .then(CommandManager.literal("off").executes(ctx -> {
                    setEnabled(false);
                    ctx.getSource().sendFeedback(() -> Text.literal("Turtle Glow disabled"), true);
                    return 1;
                }))
                .then(CommandManager.literal("toggle").executes(ctx -> {
                    setEnabled(!enabled);
                    boolean now = enabled;
                    ctx.getSource().sendFeedback(() -> Text.literal("Turtle Glow " + (now ? "enabled" : "disabled")), true);
                    return 1;
                }))
                .then(CommandManager.literal("color")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal("Turtle Glow color: " + glowColor.getName()), false);
                        return 1;
                    })
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(Formatting.getNames(true, false), builder))
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "color");
                            Formatting f = Formatting.byName(input);
                            if (f == null || !f.isColor()) {
                                ctx.getSource().sendError(Text.literal("Unknown color: " + input));
                                return 0;
                            }
                            setColor(f);
                            ctx.getSource().sendFeedback(() -> Text.literal("Turtle Glow color set to " + f.getName()), true);
                            return 1;
                        })))));
    }

    /** Turtle Master = Slowness IV + Resistance III (level II is higher), so check for at least those. */
    private static boolean isTurtled(ServerPlayerEntity p) {
        StatusEffectInstance slow = p.getStatusEffect(StatusEffects.SLOWNESS);
        StatusEffectInstance res = p.getStatusEffect(StatusEffects.RESISTANCE);
        // When the mod is toggled off nobody counts as turtled, so existing glows get cleaned up.
        return enabled && slow != null && res != null && slow.getAmplifier() >= 3 && res.getAmplifier() >= 2;
    }

    private static void loadConfig() {
        if (!Files.exists(CONFIG)) { saveConfig(); return; }
        try (Reader r = Files.newBufferedReader(CONFIG)) {
            Properties props = new Properties();
            props.load(r);
            Formatting f = Formatting.byName(props.getProperty("color", "green"));
            if (f != null && f.isColor()) glowColor = f;
            enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
        } catch (IOException ignored) { }
    }

    private static void saveConfig() {
        try (Writer w = Files.newBufferedWriter(CONFIG)) {
            Properties props = new Properties();
            props.setProperty("enabled", Boolean.toString(enabled));
            props.setProperty("color", glowColor.getName());
            props.store(w, "Turtle Glow - valid colors: black, dark_blue, dark_green, dark_aqua, dark_red, dark_purple, gold, gray, dark_gray, blue, green, aqua, red, light_purple, yellow, white");
        } catch (IOException ignored) { }
    }
}
