package org.example.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import org.example.combat.CombatManager;
import org.example.diagnostics.DemoLogger;
import org.example.synchronization.PositionCorrection;
import org.example.synchronization.MovementBoundary;
import org.example.world.WorldManager;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static net.kyori.adventure.text.format.NamedTextColor.*;

/** Setup and controls for the movement boundary and client anti-cheat demos. */
public final class GameCommand extends Command {
    private static final Component PREFIX = Component.text("[T48] ", GOLD);
    private final PositionCorrection movement;
    private final MovementBoundary boundary;
    private final CombatManager combat;
    private final DemoLogger log;

    public GameCommand(PositionCorrection movement, MovementBoundary boundary, CombatManager combat, DemoLogger log) {
        super("game");
        this.movement = movement;
        this.boundary = boundary;
        this.combat = combat;
        this.log = log;
        setDefaultExecutor((sender, context) -> help(sender));
        var arguments = ArgumentType.StringArray("arguments");
        addSyntax((sender, context) -> execute(sender, context.get(arguments)), arguments);
    }

    public static void preparePlayer(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.setEnableRespawnScreen(true);
        player.setHealth(20);
        player.setFood(20);
        player.setFoodSaturation(20);
    }

    public static void welcome(Player player) {
        player.sendMessage(PREFIX.append(Component.text("Welcome! Type ", GRAY))
                .append(Component.text("/game help", AQUA))
                .append(Component.text(" for commands.", GRAY)));
    }

    private void execute(CommandSender sender, String[] args) {
        if (args.length == 0) { help(sender); return; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) { help(sender); return; }
        if (action.equals("status")) {
            send(sender, GRAY, "Movement checks: " + state(movement.validationEnabled())
                    + " | Reach checks: " + state(combat.isReachValidationEnabled())
                    + " | Built-in range check: " + state(ServerProperties.ENFORCE_INTERACTION_LIMIT.get()));
            for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                var zone = boundary.zone(player);
                send(sender, GRAY, player.getUsername() + " | Health: " + player.getHealth()
                        + "/20 | Position: " + coordinates(movement.authoritativePosition(player))
                        + " | Boundary: " + (zone == null ? "OFF" : "ON (" + zone.radius()
                        + " blocks, return point: " + coordinates(zone.origin()) + ")"));
            }
            return;
        }
        if ((action.equals("movement") || action.equals("reach")) && args.length == 2) {
            if (!args[1].equals("on") && !args[1].equals("off")) {
                send(sender, RED, "Usage: /game " + action + " <on|off>"); return;
            }
            boolean enabled = args[1].equals("on");
            if (action.equals("movement")) movement.setValidationEnabled(enabled);
            else combat.setReachValidationEnabled(enabled);
            log.log("CONTROL actor=" + senderName(sender) + " " + action + "=" + enabled);
            send(sender, GREEN, (action.equals("movement") ? "Movement" : "Reach")
                    + " checks " + (enabled ? "enabled." : "disabled."));
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, RED, "Only players can use this command."); return;
        }
        if (player.isDead()) {
            send(player, RED, "You must respawn before using this command."); return;
        }
        switch (action) {
            case "reset" -> {
                boundary.disable(player);
                movement.setValidationEnabled(true);
                combat.setReachValidationEnabled(true);
                preparePlayer(player);
                teleport(player, WorldManager.SPAWN).thenRun(() ->
                        send(player, GREEN, "Reset complete. Health restored, checks enabled, boundary disabled."));
            }
            case "boundary" -> configureBoundary(player, args);
            case "near", "edge" -> {
                Player target = target(player, args);
                if (target == null) return;
                boundary.disable(player);
                boundary.disable(target);
                preparePlayer(player);
                preparePlayer(target);
                int gap = action.equals("near") ? 2 : 4;
                var first = teleport(player, new Pos(0.5 - gap, 100, 0.5, -90, 0));
                var second = teleport(target, new Pos(0.5, 100, 0.5, 90, 0));
                CompletableFuture.allOf(first, second).thenRun(() -> {
                    send(player, GREEN, "You are now " + gap + " blocks from " + target.getUsername() + ".");
                    send(player, YELLOW, action.equals("near") ? "Face the target and left-click to attack."
                            : "Enable Reach in your client, then face the target and left-click to attack.");
                    send(target, GREEN, "Paired with " + player.getUsername()
                            + " for the combat demo. Distance: " + gap + " blocks. Health restored.");
                    log.log("SETUP actor=" + player.getUsername() + " target=" + target.getUsername()
                            + " feetGap=" + gap + " HP=20");
                });
            }
            case "heal" -> {
                player.setHealth(20);
                send(player, GREEN, "Your health has been restored.");
            }
            default -> help(sender);
        }
    }

    private CompletableFuture<Void> teleport(Player player, Pos destination) {
        return player.teleport(destination, Vec.ZERO).thenRun(() -> movement.initialize(player));
    }

    private void configureBoundary(Player player, String[] args) {
        if (args.length > 2) {
            send(player, RED, "Usage: /game boundary [on|off|status|1-20]");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("off")) {
            boundary.disable(player);
            send(player, GREEN, "Movement boundary disabled. You can now leave the marked area.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            var zone = boundary.zone(player);
            send(player, GRAY, zone == null ? "Movement boundary: OFF."
                    : "Movement boundary: ON | Range: " + zone.radius()
                    + " blocks on X/Z | Return point: " + coordinates(zone.origin()));
            return;
        }
        int distance = 10;
        if (args.length == 2 && !args[1].equalsIgnoreCase("on")) {
            try { distance = Integer.parseInt(args[1]); }
            catch (NumberFormatException exception) {
                send(player, RED, "Usage: /game boundary [on|off|status|1-20]");
                return;
            }
        }
        if (distance < 1 || distance > 20) {
            send(player, RED, "Boundary range must be between 1 and 20 blocks.");
            return;
        }
        int radius = distance;
        boundary.disable(player);
        teleport(player, WorldManager.SPAWN).thenRun(() -> {
            WorldManager.showBoundary(player.getInstance(), WorldManager.SPAWN, radius);
            boundary.enable(player, radius);
            send(player, GREEN, "Movement boundary enabled. Stay within " + radius + " blocks of the center on X/Z.");
            send(player, YELLOW, "Cross the yellow border and you will return to the center gold block.");
            send(player, GRAY, "Use /game boundary off to disable it.");
        }).exceptionally(failure -> {
            log.log("BOUNDARY_SETUP_FAILED player=" + player.getUsername() + " reason="
                    + failure.getClass().getSimpleName());
            send(player, RED, "Unable to set the movement boundary. Please try again.");
            return null;
        });
    }

    private Player target(Player actor, String[] args) {
        if (args.length != 2) {
            send(actor, RED, "Usage: /game " + args[0] + " <player>"); return null;
        }
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                .filter(candidate -> candidate.getUsername().equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        if (target == null) {
            send(actor, RED, "Player not found."); return null;
        }
        if (target == actor) {
            send(actor, RED, "You cannot target yourself."); return null;
        }
        if (target.getInstance() != actor.getInstance()) {
            send(actor, RED, "That player is in another world."); return null;
        }
        if (target.isDead()) {
            send(actor, RED, "That player must respawn first."); return null;
        }
        return target;
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getUsername() : "CONSOLE";
    }

    private static void help(CommandSender sender) {
        send(sender, GOLD, "----- Commands -----");
        helpLine(sender, "/game boundary <1-20>", "Set your movement boundary.");
        helpLine(sender, "/game boundary off|status", "Disable or inspect your boundary.");
        helpLine(sender, "/game near <player>", "Stand 2 blocks from a player.");
        helpLine(sender, "/game edge <player>", "Stand 4 blocks from a player.");
        helpLine(sender, "/game movement <on|off>", "Toggle movement checks.");
        helpLine(sender, "/game reach <on|off>", "Toggle reach checks.");
        helpLine(sender, "/game reset", "Return to spawn and reset your demo state.");
        helpLine(sender, "/game heal", "Restore your health.");
        helpLine(sender, "/game status", "View player and validation status.");
    }

    private static void send(CommandSender sender, NamedTextColor color, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, color)));
    }

    private static void helpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(PREFIX.append(Component.text(command, AQUA))
                .append(Component.text(" - " + description, GRAY)));
    }

    private static String state(boolean enabled) {
        return enabled ? "ON" : "OFF";
    }

    private static String coordinates(Pos position) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", position.x(), position.y(), position.z());
    }
}
