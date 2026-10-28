package org.example.player;

import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import org.example.synchronization.MovementBoundary;
import org.example.synchronization.PositionCorrection;
import org.example.world.WorldManager;

import java.util.function.Consumer;

/** Keeps the vanilla death/respawn flow and clears movement state between lives. */
public final class PlayerLifecycle {
    private final PositionCorrection movement;
    private final MovementBoundary boundary;
    private final Consumer<String> log;

    public PlayerLifecycle(PositionCorrection movement, MovementBoundary boundary, Consumer<String> log) {
        this.movement = movement;
        this.boundary = boundary;
        this.log = log;
    }

    public void onSpawn(PlayerSpawnEvent event) {
        Player player = event.getPlayer();
        clearTracking(player);
        movement.initialize(player);
        if (event.isFirstSpawn()) {
            GameCommand.preparePlayer(player);
            GameCommand.welcome(player);
            log.accept("JOIN player=" + player.getUsername() + " position=" + player.getPosition());
        }
    }

    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        clearTracking(player);
        // Minestom sends the vanilla death screen and death message after this event.
        // Wait for the client's Respawn button instead of immediately reviving the player.
        log.accept("DEATH player=" + player.getUsername() + " position=" + player.getPosition());
    }

    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        clearTracking(player);
        event.setRespawnPosition(WorldManager.SPAWN);
        player.setVelocity(Vec.ZERO);
        GameCommand.preparePlayer(player);
        // The event precedes Minestom's teleport. Let the next movement packet initialize
        // tracking at the actual spawn, rather than storing the previous death position.
        log.accept("RESPAWN player=" + player.getUsername() + " position=" + event.getRespawnPosition());
    }

    public void onDisconnect(PlayerDisconnectEvent event) {
        clearTracking(event.getPlayer());
    }

    private void clearTracking(Player player) {
        boundary.forget(player);
        movement.forget(player);
    }
}
