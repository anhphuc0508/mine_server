package org.example.player;

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
import org.example.world.WorldManager;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Small presentation harness; requests here are explicitly labelled HARNESS. */
public final class DemoCommand extends Command {
    private final PositionCorrection movement;
    private final CombatManager combat;
    private final DemoLogger log;

    public DemoCommand(PositionCorrection movement, CombatManager combat, DemoLogger log) {
        super("demo");
        this.movement = movement;
        this.combat = combat;
        this.log = log;
        setDefaultExecutor((sender, context) -> help(sender));
        var arguments = ArgumentType.StringArray("arguments");
        addSyntax((sender, context) -> execute(sender, context.get(arguments)), arguments);
    }

    public static void preparePlayer(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(20);
        player.setFood(20);
        player.setFoodSaturation(20);
    }

    private void execute(CommandSender sender, String[] args) {
        if (args.length == 0) { help(sender); return; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) { help(sender); return; }
        if (action.equals("status")) {
            sender.sendMessage("movement=" + movement.validationEnabled()
                    + " reach=" + combat.isReachValidationEnabled()
                    + " minestomInteractionLimit=" + ServerProperties.ENFORCE_INTERACTION_LIMIT.get());
            for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                sender.sendMessage(player.getUsername() + " HP=" + player.getHealth()
                        + " authoritative=" + movement.authoritativePosition(player));
            }
            return;
        }
        if ((action.equals("movement") || action.equals("reach")) && args.length == 2) {
            if (!args[1].equals("on") && !args[1].equals("off")) {
                sender.sendMessage("Dung on hoac off."); return;
            }
            boolean enabled = args[1].equals("on");
            if (action.equals("movement")) movement.setValidationEnabled(enabled);
            else combat.setReachValidationEnabled(enabled);
            log.log("CONTROL actor=" + senderName(sender) + " " + action + "=" + enabled);
            sender.sendMessage(action + "=" + enabled + ". /demo reset de bat lai ca hai.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Lenh nay can thuc hien trong Minecraft."); return;
        }
        switch (action) {
            case "reset" -> {
                movement.setValidationEnabled(true);
                combat.setReachValidationEnabled(true);
                preparePlayer(player);
                teleport(player, WorldManager.SPAWN).thenRun(() ->
                        player.sendMessage("Da reset: HP=20, movement=on, reach=on."));
            }
            case "move" -> {
                Pos requested = movement.authoritativePosition(player).add(20, 0, 0);
                var result = movement.simulateMove(player, requested);
                player.sendMessage("HARNESS MOVE: " + (result.accepted() ? "ACCEPT" : "REJECT")
                        + " | " + result.reason() + " | requested +20 blocks X. Xem logs/demo.log.");
            }
            case "near", "far" -> {
                Player target = target(player, args);
                if (target == null) return;
                preparePlayer(player);
                preparePlayer(target);
                double gap = action.equals("near") ? 2 : 7;
                var first = teleport(player, new Pos(0.5 - gap, 100, 0.5, -90, 0));
                var second = teleport(target, new Pos(0.5, 100, 0.5, 90, 0));
                CompletableFuture.allOf(first, second).thenRun(() -> {
                    player.sendMessage("Da xep " + action + " voi " + target.getUsername()
                            + ". Danh thu hoac /demo attack " + target.getUsername());
                    target.sendMessage("Demo target: HP=20, feet gap=" + gap + " blocks.");
                    log.log("SETUP actor=" + player.getUsername() + " target=" + target.getUsername()
                            + " feetGap=" + gap + " HP=20");
                });
            }
            case "attack" -> {
                Player target = target(player, args);
                if (target == null) return;
                var result = combat.simulateAttack(player, target);
                String summary = String.format(Locale.ROOT,
                        "HARNESS ATTACK: %s | distance=%.3f range=%.3f | HP %.1f -> %.1f | %s",
                        result.accepted() ? "ACCEPT" : "REJECT", result.distance(), result.range(),
                        result.hpBefore(), result.hpAfter(), result.reason());
                player.sendMessage(summary);
                target.sendMessage(summary);
            }
            case "heal" -> {
                player.setHealth(20);
                player.sendMessage("HP=20");
            }
            default -> help(sender);
        }
    }

    private CompletableFuture<Void> teleport(Player player, Pos destination) {
        return player.teleport(destination, Vec.ZERO).thenRun(() -> movement.initialize(player));
    }

    private Player target(Player actor, String[] args) {
        if (args.length != 2) {
            actor.sendMessage("Can ten nguoi choi: /demo " + args[0] + " <ten>"); return null;
        }
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                .filter(candidate -> candidate.getUsername().equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        if (target == null || target == actor || target.getInstance() != actor.getInstance()) {
            actor.sendMessage("Chon mot nguoi choi khac dang o cung the gioi."); return null;
        }
        return target;
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getUsername() : "CONSOLE";
    }

    private static void help(CommandSender sender) {
        sender.sendMessage("T48 | /demo reset | /demo status | /demo heal");
        sender.sendMessage("Demo 1: di chuyen binh thuong; /demo move gui vi tri +20 blocks (HARNESS).");
        sender.sendMessage("Demo 2: /demo near <ten> | /demo far <ten> | /demo attack <ten> (HARNESS).");
        sender.sendMessage("Doi chung: /demo movement on|off | /demo reach on|off. Log: logs/demo.log");
        sender.sendMessage("Minestom loc packet xa truoc event; HARNESS khong phai kiem thu packet mang.");
    }
}
