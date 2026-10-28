package org.example.t48client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** A real modified client for the local or LAN T48 demo server. No /game command is sent here. */
public final class T48Client implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("T48Client");
    public static final double BLINK_DISTANCE = 20.0;
    public static final double REACH_DISTANCE = 4.0;
    private static final double SPEED_PER_TICK = 0.75;

    private KeyMapping menuKey;
    private KeyMapping blinkKey;
    private KeyMapping speedKey;
    private KeyMapping reachKey;
    private boolean speed;
    private boolean reach;
    private boolean lastAttackDown;
    private long lastBlinkAt;
    private Object lastConnection;
    private String notice = "All modules OFF. Right Shift opens the menu.";

    @Override
    public void onInitializeClient() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("t48client", "tools"));
        menuKey = key("menu", GLFW.GLFW_KEY_RIGHT_SHIFT, category);
        blinkKey = key("blink", GLFW.GLFW_KEY_B, category);
        speedKey = key("speed", GLFW.GLFW_KEY_V, category);
        reachKey = key("reach", GLFW.GLFW_KEY_R, category);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("t48client", "status"), (graphics, delta) -> renderHud(graphics));
        LOGGER.info("T48 client loaded for Minecraft 26.2. Right Shift: menu; B: blink; V: speed; R: reach.");
    }

    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.t48client." + name,
                InputConstants.Type.KEYSYM, code, category));
    }

    private void tick(Minecraft client) {
        var connection = client.getConnection();
        if (lastConnection != connection) {
            stopModules(client);
            lastConnection = connection;
            notice = "All modules OFF. Right Shift opens the menu.";
        }

        while (menuKey.consumeClick()) {
            if (client.player != null && client.gui.screen() == null) {
                client.gui.setScreen(new ToolScreen(this));
            }
        }

        boolean allowed = isDemoConnection(client);
        if (!allowed) {
            stopModules(client);
        }

        while (speedKey.consumeClick()) {
            if (client.gui.screen() == null) toggleSpeed(client);
        }
        while (reachKey.consumeClick()) {
            if (client.gui.screen() == null) toggleReach(client);
        }
        while (blinkKey.consumeClick()) {
            if (client.gui.screen() == null) blink(client);
        }

        if (!allowed || client.player == null || client.gui.screen() != null) {
            lastAttackDown = false;
            return;
        }
        if (speed) applySpeed(client);

        boolean attackDown = client.options.keyAttack.isDown();
        if (reach && attackDown && !lastAttackDown) attackExtendedTarget(client);
        lastAttackDown = attackDown;
    }

    /** Uses the actual connected socket, not a hostname supplied by a server. */
    public boolean isDemoConnection(Minecraft client) {
        var connection = client.getConnection();
        if (client.player == null || client.level == null || connection == null) return false;
        return DemoNetwork.isAllowed(connection.getConnection().getRemoteAddress());
    }

    private boolean requireDemo(Minecraft client) {
        if (isDemoConnection(client)) return true;
        inform(client, "Connect to your demo using localhost or the server's LAN IP to activate modules.");
        return false;
    }

    public void toggleSpeed(Minecraft client) {
        if (!requireDemo(client)) return;
        speed = !speed;
        if (!speed) stopHorizontalMotion(client);
        inform(client, "Speed " + state(speed) + " | WASD moves at about 15 blocks/s.");
    }

    public void toggleReach(Minecraft client) {
        if (!requireDemo(client)) return;
        reach = !reach;
        inform(client, "Reach " + state(reach) + " | Aim at a player within 4m and LEFT CLICK.");
    }

    public void stopModules(Minecraft client) {
        if (speed) stopHorizontalMotion(client);
        speed = false;
        reach = false;
        lastAttackDown = false;
    }

    public void disableAll(Minecraft client) {
        stopModules(client);
        inform(client, "All modules OFF.");
    }

    private static void stopHorizontalMotion(Minecraft client) {
        if (client.player != null) {
            var velocity = client.player.getDeltaMovement();
            client.player.setDeltaMovement(0.0, velocity.y, 0.0);
        }
    }

    private static void applySpeed(Minecraft client) {
        var player = client.player;
        if (player == null || !player.isAlive() || player.isPassenger()) return;
        int forward = (client.options.keyUp.isDown() ? 1 : 0) - (client.options.keyDown.isDown() ? 1 : 0);
        int strafe = (client.options.keyLeft.isDown() ? 1 : 0) - (client.options.keyRight.isDown() ? 1 : 0);
        if (forward == 0 && strafe == 0) return;
        double radians = Math.toRadians(player.getYRot());
        double normalizer = Math.hypot(forward, strafe);
        double x = (-Math.sin(radians) * forward + Math.cos(radians) * strafe) / normalizer;
        double z = (Math.cos(radians) * forward + Math.sin(radians) * strafe) / normalizer;
        // Vanilla physics moves the real LocalPlayer and emits its normal position packets next tick.
        player.setDeltaMovement(x * SPEED_PER_TICK, player.getDeltaMovement().y, z * SPEED_PER_TICK);
    }

    public void blink(Minecraft client) {
        if (!requireDemo(client) || client.player == null) return;
        long now = System.nanoTime();
        if (now - lastBlinkAt < 700_000_000L) return;
        lastBlinkAt = now;
        var player = client.player;
        double radians = Math.toRadians(player.getYRot());
        Vec3 from = player.position();
        Vec3 requested = from.add(-Math.sin(radians) * BLINK_DISTANCE, 0,
                Math.cos(radians) * BLINK_DISTANCE);
        // First change the actual client position. A rejected request is corrected by the real server packet.
        player.setPos(requested);
        player.connection.send(new ServerboundMovePlayerPacket.PosRot(requested,
                player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
        LOGGER.info("WIRE send=ServerboundMovePlayerPacket.PosRot action=BLINK localBefore={} localRequested={}",
                from, requested);
        inform(client, "Blink: moved locally +20m; position packet sent. Watch the server correction.");
    }

    private void attackExtendedTarget(Minecraft client) {
        var player = client.player;
        if (player == null || client.level == null || !player.isAlive()) return;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH_DISTANCE));
        var obstruction = client.level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double closest = obstruction.getType() == HitResult.Type.MISS
                ? REACH_DISTANCE : eye.distanceTo(obstruction.getLocation());
        AbstractClientPlayer selected = null;
        for (var target : client.level.players()) {
            if (target == player || !target.isAlive() || target.isSpectator()) continue;
            var hit = target.getBoundingBox().clip(eye, end);
            if (hit.isEmpty()) continue;
            double distance = eye.distanceTo(hit.get());
            if (distance <= closest) {
                closest = distance;
                selected = target;
            }
        }
        if (selected == null) {
            inform(client, "Reach: aim at a player within 4m, with no block in the way.");
            return;
        }
        // The unmodified client already handles normal hits; avoid a second attack in vanilla range.
        if (closest <= player.entityInteractionRange()) return;
        player.connection.send(new ServerboundAttackPacket(selected.getId()));
        player.swing(InteractionHand.MAIN_HAND);
        player.resetAttackStrengthTicker();
        LOGGER.info("WIRE send=ServerboundAttackPacket target={} entityId={} eyeToBox={}",
                selected.getName().getString(), selected.getId(), closest);
        inform(client, String.format(Locale.ROOT, "Reach packet -> %s (%.2fm). Server decides damage.",
                selected.getName().getString(), closest));
    }

    private void inform(Minecraft client, String message) {
        notice = message;
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[T48 Client] " + message));
    }

    private void renderHud(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        boolean allowed = isDemoConnection(client);
        graphics.fill(7, 7, 295, 61, 0xC0151926);
        graphics.fill(7, 7, 10, 61, allowed ? 0xFF49E39A : 0xFFE3A049);
        graphics.text(client.font, "T48 CLIENT  |  " + (allowed ? "LOCAL / LAN DEMO" : "MODULES LOCKED"),
                15, 13, allowed ? 0xFF80F0BA : 0xFFFFCC80, true);
        graphics.text(client.font, "Speed " + state(speed) + " [V]    Reach " + state(reach) + " [R]",
                15, 27, 0xFFFFFFFF, true);
        graphics.text(client.font, "Blink 20m [B]    Menu [Right Shift]", 15, 42, 0xFFC0CBDE, true);
    }

    public boolean speedEnabled() { return speed; }
    public boolean reachEnabled() { return reach; }
    public String notice() { return notice; }
    public boolean isMenuKey(net.minecraft.client.input.KeyEvent event) { return menuKey.matches(event); }
    public static String state(boolean enabled) { return enabled ? "ON" : "OFF"; }
}
