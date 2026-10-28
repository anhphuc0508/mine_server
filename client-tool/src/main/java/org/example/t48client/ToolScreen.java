package org.example.t48client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** An in-game click menu; buttons control client modules, never server commands. */
public final class ToolScreen extends Screen {
    private final T48Client tool;
    private int left;
    private int top;
    private int panelWidth;
    private Button speed;
    private Button reach;
    private Button blink;

    public ToolScreen(T48Client tool) {
        super(Component.literal("T48 Demo Client"));
        this.tool = tool;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(440, width - 24);
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 232) / 2);
        speed = addRenderableWidget(Button.builder(Component.empty(), button -> {
            tool.toggleSpeed(minecraft);
            updateButtons();
        }).bounds(left + 14, top + 50, panelWidth - 28, 20).build());
        blink = addRenderableWidget(Button.builder(Component.literal("BLINK +20m  [B]"), button -> {
            onClose();
            tool.blink(minecraft);
        }).bounds(left + 14, top + 92, panelWidth - 28, 20).build());
        reach = addRenderableWidget(Button.builder(Component.empty(), button -> {
            tool.toggleReach(minecraft);
            updateButtons();
        }).bounds(left + 14, top + 134, panelWidth - 28, 20).build());
        addRenderableWidget(Button.builder(Component.literal("All OFF"), button -> {
            tool.disableAll(minecraft);
            updateButtons();
        }).bounds(left + 14, top + 202, (panelWidth - 34) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Return to game"), button -> onClose())
                .bounds(left + 20 + (panelWidth - 34) / 2, top + 202,
                        (panelWidth - 34) / 2, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        speed.setMessage(Component.literal("SPEED: " + T48Client.state(tool.speedEnabled()) + "  [V]"));
        reach.setMessage(Component.literal("REACH: " + T48Client.state(tool.reachEnabled()) + "  [R]"));
        boolean allowed = tool.isDemoConnection(minecraft);
        speed.active = allowed;
        reach.active = allowed;
        blink.active = allowed;
    }

    @Override
    public void tick() {
        updateButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, left + panelWidth, top + 232, 0xEE101521);
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFF49E39A);
        graphics.centeredText(font, title, width / 2, top + 13, 0xFFFFFFFF);
        graphics.centeredText(font, tool.isDemoConnection(minecraft)
                        ? "Local / LAN server | Real client packets"
                        : "Connect to your local or LAN demo server",
                width / 2, top + 32, 0xFF8BE0B5);
        graphics.text(font, "WASD: ~15 blocks/s. Watch for rubberbanding.",
                left + 16, top + 75, 0xFFB8C6DA, true);
        graphics.text(font, "Jump 20m locally and send a position packet.",
                left + 16, top + 117, 0xFFB8C6DA, true);
        graphics.text(font, "Aim at a player <=4m, then LEFT CLICK.",
                left + 16, top + 159, 0xFFB8C6DA, true);
        graphics.textWithWordWrap(font, Component.literal(tool.notice()),
                left + 16, top + 177, panelWidth - 32, 0xFFD8E2F0);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tool.isMenuKey(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
