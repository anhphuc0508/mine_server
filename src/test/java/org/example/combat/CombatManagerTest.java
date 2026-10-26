package org.example.combat;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.listener.UseEntityListener;
import net.minestom.server.network.packet.client.play.ClientAttackPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** In-process manager tests; these do not send Minecraft packets over a socket. */
@EnvTest
class CombatManagerTest {
    @Test
    void listenerAttackInsideMinestomsBufferStillNeedsCustomReachValidation(Env env) {
        var instance = env.createEmptyInstance();
        var attacker = env.createPlayer(instance, new Pos(0, 64, 0));
        var target = env.createPlayer(instance, new Pos(4, 64, 0));
        target.addViewer(attacker);
        assertTrue(target.isViewer(attacker));
        var manager = new CombatManager(Entity::getPosition, ignored -> {});
        var events = new AtomicInteger();
        env.process().eventHandler().addListener(EntityAttackEvent.class, event -> {
            events.incrementAndGet();
            manager.handle(event);
        });
        float before = target.getHealth();

        UseEntityListener.attackEntityListener(new ClientAttackPacket(target.getEntityId()), attacker);

        assertEquals(1, events.get(), "This distance must pass Minestom's earlier interaction filter");
        assertEquals(before, target.getHealth());

        manager.setReachValidationEnabled(false);
        UseEntityListener.attackEntityListener(new ClientAttackPacket(target.getEntityId()), attacker);

        assertEquals(2, events.get());
        assertEquals(before - CombatManager.DEMO_DAMAGE, target.getHealth());
    }

    @Test
    void minestomsEarlierFilterStillBlocksDistantPacketsWhenCustomReachIsDisabled(Env env) {
        var instance = env.createEmptyInstance();
        var attacker = env.createPlayer(instance, new Pos(0, 64, 0));
        var target = env.createPlayer(instance, new Pos(8, 64, 0));
        target.addViewer(attacker);
        assertTrue(target.isViewer(attacker));
        var manager = new CombatManager(Entity::getPosition, ignored -> {});
        manager.setReachValidationEnabled(false);
        var events = new AtomicInteger();
        env.process().eventHandler().addListener(EntityAttackEvent.class, event -> {
            events.incrementAndGet();
            manager.handle(event);
        });
        float before = target.getHealth();

        UseEntityListener.attackEntityListener(new ClientAttackPacket(target.getEntityId()), attacker);

        assertEquals(0, events.get());
        assertEquals(before, target.getHealth());

        var direct = manager.simulateAttack(attacker, target);
        assertTrue(direct.accepted());
        assertTrue(direct.damageApplied());
        assertEquals(before - CombatManager.DEMO_DAMAGE, target.getHealth());
        assertEquals(0, events.get(), "The direct hook must not masquerade as a client attack event");
    }

    @Test
    void rejectedAttackEventDoesNotDamageTheTarget(Env env) {
        var instance = env.createEmptyInstance();
        var attacker = env.createPlayer(instance, new Pos(0, 64, 0));
        var target = env.createPlayer(instance, new Pos(8, 64, 0));
        var logs = new ArrayList<String>();
        var manager = new CombatManager(Entity::getPosition, logs::add);
        float before = target.getHealth();

        manager.handle(new EntityAttackEvent(attacker, target));

        assertEquals(before, target.getHealth());
        assertEquals(1, logs.size());
        assertTrue(logs.getFirst().contains("decision=REJECT"));
        assertTrue(logs.getFirst().contains("reason=OUT_OF_REACH"));
    }

    @Test
    void acceptedAttackEventAppliesDamage(Env env) {
        var instance = env.createEmptyInstance();
        var attacker = env.createPlayer(instance, new Pos(0, 64, 0));
        var target = env.createPlayer(instance, new Pos(2, 64, 0));
        var manager = new CombatManager(Entity::getPosition, ignored -> {});
        float before = target.getHealth();

        manager.handle(new EntityAttackEvent(attacker, target));

        assertEquals(before - CombatManager.DEMO_DAMAGE, target.getHealth());
    }

    @Test
    void directControlComparisonChangesHealthOnlyWhenValidationIsDisabled(Env env) {
        var instance = env.createEmptyInstance();
        var attacker = env.createPlayer(instance, new Pos(0, 64, 0));
        var target = env.createPlayer(instance, new Pos(8, 64, 0));
        var manager = new CombatManager(Entity::getPosition, ignored -> {});
        float before = target.getHealth();

        var rejected = manager.simulateAttack(attacker, target);
        assertFalse(rejected.accepted());
        assertFalse(rejected.damageApplied());
        assertEquals(before, rejected.hpBefore());
        assertEquals(before, rejected.hpAfter());
        assertEquals(before, target.getHealth());

        manager.setReachValidationEnabled(false);
        var accepted = manager.simulateAttack(attacker, target);
        assertTrue(accepted.accepted());
        assertTrue(accepted.damageApplied());
        assertEquals(before - CombatManager.DEMO_DAMAGE, target.getHealth());
        assertEquals("SIMULATION_DIRECT_HOOK", accepted.source());
    }

    @Test
    void usesAuthoritativePositionsForBothAttackerAndTarget(Env env) {
        var instance = env.createEmptyInstance();
        var attacker = env.createPlayer(instance, new Pos(0, 64, 0));
        var target = env.createPlayer(instance, new Pos(1, 64, 0));
        Map<Entity, Pos> positions = new IdentityHashMap<>();
        var manager = new CombatManager(positions::get, ignored -> {});
        float before = target.getHealth();

        positions.put(attacker, new Pos(-10, 64, 0));
        positions.put(target, new Pos(1, 64, 0));
        assertFalse(manager.simulateAttack(attacker, target).accepted());
        assertEquals(before, target.getHealth());

        positions.put(attacker, new Pos(0, 64, 0));
        positions.put(target, new Pos(10, 64, 0));
        assertFalse(manager.simulateAttack(attacker, target).accepted());
        assertEquals(before, target.getHealth());
    }

    @Test
    void customReachControlStillRejectsSpectatorAndProtectedTargets(Env env) {
        var instance = env.createEmptyInstance();
        var attacker = env.createPlayer(instance, new Pos(0, 64, 0));
        var target = env.createPlayer(instance, new Pos(2, 64, 0));
        var manager = new CombatManager(Entity::getPosition, ignored -> {});
        manager.setReachValidationEnabled(false);

        attacker.setGameMode(GameMode.SPECTATOR);
        assertRejectedWithoutHealthChange(manager, attacker, target);

        attacker.setGameMode(GameMode.SURVIVAL);
        target.setGameMode(GameMode.CREATIVE);
        assertRejectedWithoutHealthChange(manager, attacker, target);

        target.setGameMode(GameMode.SPECTATOR);
        assertRejectedWithoutHealthChange(manager, attacker, target);
    }

    @Test
    void attacksInDifferentInstancesAreRejectedEvenWithReachDisabled(Env env) {
        var attacker = env.createPlayer(env.createEmptyInstance(), new Pos(0, 64, 0));
        var target = env.createPlayer(env.createEmptyInstance(), new Pos(1, 64, 0));
        var manager = new CombatManager(Entity::getPosition, ignored -> {});
        manager.setReachValidationEnabled(false);

        var result = assertRejectedWithoutHealthChange(manager, attacker, target);

        assertEquals("DIFFERENT_INSTANCE", result.reason());
    }

    @Test
    void demoDamageLeavesTheTargetAliveAtTheHealthFloor(Env env) {
        var instance = env.createEmptyInstance();
        var attacker = env.createPlayer(instance, new Pos(0, 64, 0));
        var target = env.createPlayer(instance, new Pos(2, 64, 0));
        var manager = new CombatManager(Entity::getPosition, ignored -> {});
        target.setHealth(2.0f);

        var first = manager.simulateAttack(attacker, target);
        var second = manager.simulateAttack(attacker, target);

        assertTrue(first.accepted());
        assertTrue(first.damageApplied());
        assertEquals(1.0f, first.hpAfter());
        assertTrue(second.accepted());
        assertFalse(second.damageApplied());
        assertEquals(1.0f, target.getHealth());
        assertFalse(target.isDead());
    }

    private static CombatManager.AttackResult assertRejectedWithoutHealthChange(
            CombatManager manager, Player attacker, Player target) {
        float before = target.getHealth();
        var result = manager.simulateAttack(attacker, target);
        assertFalse(result.accepted());
        assertFalse(result.damageApplied());
        assertEquals(before, result.hpBefore());
        assertEquals(before, result.hpAfter());
        assertEquals(before, target.getHealth());
        return result;
    }
}
