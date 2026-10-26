package org.example.combat;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.event.entity.EntityAttackEvent;
import org.example.validation.CombatValidator;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owns the demo's decision and damage; EntityAttackEvent does not apply damage itself. */
public final class CombatManager {
    public static final float DEMO_DAMAGE = 4.0f;
    public static final float MINIMUM_HEALTH = 1.0f;

    private final Function<Entity, Pos> authoritativePosition;
    private final Consumer<String> logger;
    private volatile boolean reachValidationEnabled = true;

    public CombatManager(Function<Entity, Pos> authoritativePosition, Consumer<String> logger) {
        this.authoritativePosition = Objects.requireNonNull(authoritativePosition);
        this.logger = Objects.requireNonNull(logger);
    }

    /** Real client attacks which passed Minestom's built-in interaction filter. */
    public void handle(EntityAttackEvent event) {
        if (event.getEntity() instanceof Player attacker) {
            attack(attacker, event.getTarget(), "CLIENT_EVENT");
        }
    }

    /**
     * Calls our combat path directly using both entities' authoritative positions.
     * This is a presentation/test hook, not a forged client packet and not proof
     * that a normal Minecraft client can attack at this distance.
     */
    public AttackResult simulateAttack(Player attacker, LivingEntity target) {
        return attack(attacker, target, "SIMULATION_DIRECT_HOOK");
    }

    public boolean isReachValidationEnabled() {
        return reachValidationEnabled;
    }

    /** Toggles only our custom rule. Minestom's earlier packet filter stays enabled. */
    public void setReachValidationEnabled(boolean enabled) {
        reachValidationEnabled = enabled;
    }

    private AttackResult attack(Player attacker, Entity target, String source) {
        Objects.requireNonNull(attacker);
        Objects.requireNonNull(target);
        Pos attackerPosition = positionOf(attacker);
        Pos targetPosition = positionOf(target);
        boolean customReach = reachValidationEnabled;
        CombatValidator.Result validation = CombatValidator.validate(
                attackerPosition, attacker.getEyeHeight(), targetPosition, target.getBoundingBox(),
                attacker.getGameMode(), attacker.getAttributeValue(Attribute.ENTITY_INTERACTION_RANGE), customReach);
        float before = target instanceof LivingEntity living ? living.getHealth() : Float.NaN;

        String rejection = null;
        if (attacker == target) {
            rejection = "SELF_ATTACK";
        } else if (attacker.getInstance() == null || attacker.getInstance() != target.getInstance()) {
            rejection = "DIFFERENT_INSTANCE";
        } else if (attacker.isDead()) {
            rejection = "DEAD_ATTACKER";
        } else if (!(target instanceof Player)) {
            rejection = "DEMO_PLAYERS_ONLY";
        } else if (((Player) target).isDead()) {
            rejection = "DEAD_TARGET";
        } else if (((Player) target).getGameMode() == GameMode.SPECTATOR) {
            rejection = "SPECTATOR_TARGET";
        } else if (((Player) target).getGameMode() == GameMode.CREATIVE) {
            rejection = "CREATIVE_TARGET";
        } else if (!validation.allowed()) {
            rejection = validation.reason();
        }

        boolean accepted = rejection == null;
        boolean damageApplied = false;
        String reason = accepted ? validation.reason() : rejection;
        if (accepted) {
            LivingEntity living = (LivingEntity) target;
            float amount = Math.min(DEMO_DAMAGE, Math.max(0.0f, before - MINIMUM_HEALTH));
            if (amount > 0) {
                boolean damageAccepted = living.damage(Damage.fromPlayer(attacker, amount));
                damageApplied = damageAccepted && living.getHealth() < before;
                if (!damageAccepted) {
                    reason += ":DAMAGE_BLOCKED";
                }
            } else {
                reason += ":HEALTH_FLOOR";
            }
        }

        float after = target instanceof LivingEntity living ? living.getHealth() : Float.NaN;
        AttackResult result = new AttackResult(accepted, reason, validation.distance(), validation.range(),
                before, after, damageApplied, source);
        logger.accept(String.format(Locale.ROOT,
                "ATTACK source=%s attacker=%s target=%s customReach=%s mode=%s "
                        + "distance=%.3f range=%.3f decision=%s reason=%s hp=%.1f->%.1f damage=%.1f "
                        + "attackerPos=(%.2f,%.2f,%.2f) targetPos=(%.2f,%.2f,%.2f)",
                source, attacker.getUsername(), target instanceof Player player ? player.getUsername() : target.getEntityId(),
                customReach, attacker.getGameMode(), result.distance(), result.range(),
                result.accepted() ? "ACCEPT" : "REJECT", result.reason(), before, after,
                Float.isFinite(before) && Float.isFinite(after) ? before - after : 0.0f,
                attackerPosition.x(), attackerPosition.y(), attackerPosition.z(),
                targetPosition.x(), targetPosition.y(), targetPosition.z()));
        return result;
    }

    private Pos positionOf(Entity entity) {
        Pos authoritative = authoritativePosition.apply(entity);
        return authoritative != null ? authoritative : entity.getPosition();
    }

    public record AttackResult(boolean accepted, String reason, double distance, double range,
                               float hpBefore, float hpAfter, boolean damageApplied, String source) {
    }
}
