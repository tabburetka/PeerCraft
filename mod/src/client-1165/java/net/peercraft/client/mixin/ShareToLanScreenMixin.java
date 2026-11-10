package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.GuiComponent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.peercraft.client.theme.SteampunkPalette;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.PeerCraftHostOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../ShareToLanScreenMixin.java}. Same widgets
 * (internet-play checkbox, max-players stepper, allow-unlicensed / friends-only / public-room
 * checkboxes, world-name field) added at {@code ShareToLanScreen.init()} TAIL, but rebuilt on
 * the 1.16.5 API:
 * <ul>
 *   <li>{@link Checkbox} has no {@code onValueChange} builder — {@link CallbackCheckbox}
 *       subclasses it and fires a {@link Consumer} from {@code onPress()}.</li>
 *   <li>{@code CycleButton} doesn't exist yet — the max-players stepper is a plain
 *       {@link Button} that cycles {@link #MAX_PLAYERS_OPTIONS} and rewrites its own label.</li>
 *   <li>{@code EditBox.setHint} → {@code setSuggestion(String)}; {@code addRenderableWidget}
 *       → {@code addButton}; {@code Component.translatable/literal} →
 *       {@code TranslatableComponent}/{@code TextComponent}.</li>
 * </ul>
 * The friends-only / public-room mutual exclusion is still enforced by disabling the other
 * checkbox (never force-unchecking), exactly as documented in the original.
 */
@Mixin(ShareToLanScreen.class)
public abstract class ShareToLanScreenMixin extends Screen {

    @Unique private final java.util.List<AbstractWidget> peercraft$lanActions = new java.util.ArrayList<>();
    @Unique private final java.util.List<AbstractWidget> peercraft$lanControls = new java.util.ArrayList<>();

    private static final List<Integer> MAX_PLAYERS_OPTIONS = IntStream.rangeClosed(1, 8).boxed().collect(Collectors.toList());

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addInternetCheckbox(CallbackInfo ci) {
        peercraft$lanActions.clear(); peercraft$lanControls.clear();
        // Vanilla adds Start/Cancel first, then mode/commands; retain the original input owners.
        for (AbstractWidget widget : this.buttons) {
            if (widget.y >= this.height - 30) peercraft$lanActions.add(widget);
            else peercraft$lanControls.add(widget);
        }
        // Adaptive bottom-anchored layout — same rationale as (and kept in step with)
        // src/main/.../ShareToLanScreenMixin.java. 1.16.5's ShareToLanScreen has a fixed top
        // (title y50, info y82, game-mode/cheats row y100..120, NO port field) and its
        // "Start LAN World"/"Cancel" row pinned at height-28. Pack the six mod widgets into
        // four rows / two columns, glue the block to just above that row, and scale the row
        // pitch + button height to the leftover vertical room so nothing rides up onto the
        // vanilla widgets when GUI Scale "Auto" shrinks this.height on a big monitor.
        int gutter = 8;
        int colWidth = Math.min(150, (this.width - 40 - gutter) / 2);
        int colLeft = this.width / 2 - colWidth - gutter / 2;
        int colRight = this.width / 2 + gutter / 2;

        int bandTop = 126;
        int bandBottom = this.height - 28 - 6;
        int rows = 4;
        int rowPitch = Math.max(16, Math.min(26, (bandBottom - bandTop) / rows));
        int widgetH = Math.max(12, Math.min(20, rowPitch - 4));
        int blockHeight = rowPitch * (rows - 1) + widgetH;
        int blockTop = Math.max(bandTop, bandBottom - blockHeight);
        int buttonWidth = colWidth;

        int internetY = blockTop;
        int allowUnlicensedY = blockTop + rowPitch;
        int friendsOnlyY = blockTop + rowPitch * 2;
        int publicRoomY = blockTop + rowPitch * 2;
        int maxPlayersY = blockTop + rowPitch * 3;
        int worldNameY = blockTop + rowPitch * 3;

        int initialMaxPlayers = MAX_PLAYERS_OPTIONS.contains(PeerCraftHostOptions.maxPlayers)
                ? PeerCraftHostOptions.maxPlayers
                : MAX_PLAYERS_OPTIONS.get(MAX_PLAYERS_OPTIONS.size() - 1);
        int[] mpIndex = {Math.max(0, MAX_PLAYERS_OPTIONS.indexOf(initialMaxPlayers))};
        Button maxPlayersButton = new Button(colLeft, maxPlayersY, buttonWidth, widgetH,
                maxPlayersLabel(MAX_PLAYERS_OPTIONS.get(mpIndex[0])), b -> {
            mpIndex[0] = (mpIndex[0] + 1) % MAX_PLAYERS_OPTIONS.size();
            int value = MAX_PLAYERS_OPTIONS.get(mpIndex[0]);
            PeerCraftHostOptions.maxPlayers = value;
            b.setMessage(maxPlayersLabel(value));
        });

        CallbackCheckbox allowUnlicensedCheckbox = new CallbackCheckbox(colLeft, allowUnlicensedY, 20, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.allow_unlicensed"),
                PeerCraftHostOptions.allowUnlicensedPlayers,
                value -> PeerCraftHostOptions.allowUnlicensedPlayers = value);

        EditBox worldNameBox = new EditBox(this.font, colRight, worldNameY, buttonWidth, widgetH,
                new TranslatableComponent("peercraft.mixin.share_to_lan.world_name"));
        worldNameBox.setMaxLength(PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH);
        worldNameBox.setSuggestion(new TranslatableComponent("peercraft.mixin.share_to_lan.world_name_hint").getString());
        worldNameBox.setValue(PeerCraftHostOptions.worldName);
        worldNameBox.setResponder(value -> {
            PeerCraftHostOptions.worldName = value;
            worldNameBox.setSuggestion(value.isEmpty()
                    ? new TranslatableComponent("peercraft.mixin.share_to_lan.world_name_hint").getString()
                    : "");
        });

        boolean loggedIn = net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession() != null;

        // friendsOnlyHolder breaks the circular reference between the two mutually-exclusive
        // checkbox callbacks — same trick as the original.
        CallbackCheckbox[] friendsOnlyHolder = new CallbackCheckbox[1];

        CallbackCheckbox publicRoomCheckbox = new CallbackCheckbox(colRight, publicRoomY, 20, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.public_room"),
                PeerCraftHostOptions.publicRoom,
                value -> {
                    PeerCraftHostOptions.publicRoom = value;
                    worldNameBox.visible = value && PeerCraftHostOptions.internetPlayRequested;
                    if (friendsOnlyHolder[0] != null) {
                        friendsOnlyHolder[0].active = !value && loggedIn;
                    }
                });

        CallbackCheckbox friendsOnlyCheckbox = new CallbackCheckbox(colLeft, friendsOnlyY, 20, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.friends_only"),
                PeerCraftHostOptions.friendsOnly,
                value -> {
                    PeerCraftHostOptions.friendsOnly = value;
                    publicRoomCheckbox.active = !value;
                });
        friendsOnlyCheckbox.active = loggedIn && !PeerCraftHostOptions.publicRoom;
        publicRoomCheckbox.active = !PeerCraftHostOptions.friendsOnly;
        friendsOnlyHolder[0] = friendsOnlyCheckbox;

        maxPlayersButton.visible = PeerCraftHostOptions.internetPlayRequested;
        allowUnlicensedCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        friendsOnlyCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        publicRoomCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        worldNameBox.visible = PeerCraftHostOptions.internetPlayRequested && PeerCraftHostOptions.publicRoom;

        CallbackCheckbox internetCheckbox = new CallbackCheckbox(colLeft, internetY, 20, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.internet_play"),
                PeerCraftHostOptions.internetPlayRequested,
                value -> {
                    PeerCraftHostOptions.internetPlayRequested = value;
                    maxPlayersButton.visible = value;
                    allowUnlicensedCheckbox.visible = value;
                    friendsOnlyCheckbox.visible = value;
                    publicRoomCheckbox.visible = value;
                    worldNameBox.visible = value && PeerCraftHostOptions.publicRoom;
                });

        this.addButton(internetCheckbox);
        this.addButton(maxPlayersButton);
        this.addButton(allowUnlicensedCheckbox);
        this.addButton(friendsOnlyCheckbox);
        this.addButton(publicRoomCheckbox);
        this.addButton(worldNameBox);
        peercraft$lanControls.add(internetCheckbox);
        peercraft$lanControls.add(maxPlayersButton);
        peercraft$lanControls.add(allowUnlicensedCheckbox);
        peercraft$lanControls.add(friendsOnlyCheckbox);
        peercraft$lanControls.add(publicRoomCheckbox);
        peercraft$lanControls.add(worldNameBox);
    }


    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void peercraft$renderLan(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        renderBackground(pose);
        java.util.List<AbstractWidget> visible = new java.util.ArrayList<>();
        for (AbstractWidget widget : peercraft$lanControls) if (widget.visible) visible.add(widget);
        int panelWidth = Math.max(1, Math.min(360, width - 24));
        int pitch = Math.max(12, Math.min(28, (height - 92) / Math.max(1, visible.size())));
        int widgetHeight = Math.max(9, pitch - 3);
        int panelHeight = Math.min(height - 16, 76 + visible.size() * pitch);
        int left = (width - panelWidth) / 2, top = (height - panelHeight) / 2;
        peercraft$frame(pose, left, top, panelWidth, panelHeight, SteampunkPalette.PANEL, SteampunkPalette.BORDER);
        GuiComponent.drawCenteredString(pose, font, "PeerCraft", width / 2, top + 9, SteampunkPalette.ACCENT);
        GuiComponent.drawCenteredString(pose, font, title, width / 2, top + 24, SteampunkPalette.TEXT);
        GuiComponent.fill(pose, left + 12, top + 38, left + panelWidth - 12, top + 39, 0xFF49331F);
        int y = top + 44;
        for (AbstractWidget widget : visible) {
            widget.x = left + 12; widget.y = y; widget.setWidth(panelWidth - 24); ((PeerCraftWidgetSizeAccessor) widget).peercraft$setHeight(widgetHeight);
            boolean hovered = widget.isMouseOver(mouseX, mouseY);
            int border = widget.isFocused() ? SteampunkPalette.ACCENT : hovered ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER;
            if (widget instanceof EditBox) {
                EditBox field = (EditBox) widget;
                String label = font.plainSubstrByWidth(widget.getMessage().getString(), panelWidth / 2 - 20);
                GuiComponent.drawString(pose, font, label, left + 12, y + (widgetHeight - 8) / 2, SteampunkPalette.MUTED);
                field.x = left + panelWidth / 2 + 4;
                field.setWidth(panelWidth / 2 - 21);
                field.setBordered(false); field.setTextColor(SteampunkPalette.TEXT); field.setTextColorUneditable(SteampunkPalette.MUTED);
                peercraft$frame(pose, field.x - 4, y, field.getWidth() + 8, widgetHeight, SteampunkPalette.CONTROL, border);
                field.render(pose, mouseX, mouseY, partialTick);
            } else {
                peercraft$frame(pose, widget.x, y, widget.getWidth(), widgetHeight,
                        !widget.active ? SteampunkPalette.DISABLED : hovered ? SteampunkPalette.CONTROL_HOVER : SteampunkPalette.CONTROL, border);
                String label = widget.getMessage().getString();
                if (widget instanceof Checkbox) label = (((Checkbox) widget).selected() ? "✓ " : "□ ") + label;
                GuiComponent.drawCenteredString(pose, font, font.plainSubstrByWidth(label, panelWidth - 36), width / 2,
                        y + (widgetHeight - 8) / 2, widget.active ? SteampunkPalette.TEXT : SteampunkPalette.MUTED);
            }
            y += pitch;
        }
        int actionWidth = (panelWidth - 30) / 2;
        for (int i = 0; i < peercraft$lanActions.size(); i++) {
            AbstractWidget widget = peercraft$lanActions.get(i);
            widget.x = left + 12 + i * (actionWidth + 6); widget.y = top + panelHeight - 12 - widgetHeight;
            widget.setWidth(actionWidth); ((PeerCraftWidgetSizeAccessor) widget).peercraft$setHeight(widgetHeight);
            boolean hovered = widget.isMouseOver(mouseX, mouseY);
            peercraft$frame(pose, widget.x, widget.y, actionWidth, widgetHeight,
                    i == 0 ? (hovered ? SteampunkPalette.PRIMARY_HOVER : SteampunkPalette.PRIMARY) : SteampunkPalette.CONTROL,
                    widget.isFocused() ? SteampunkPalette.ACCENT : hovered ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER);
            String label = font.plainSubstrByWidth(widget.getMessage().getString(), actionWidth - 8);
            if (i == 0) {
                // Dark text on the primary fill needs no shadow.
                font.draw(pose, label, widget.x + (actionWidth - font.width(label)) / 2,
                        widget.y + (widgetHeight - 8) / 2, SteampunkPalette.CONTROL);
            } else {
                GuiComponent.drawCenteredString(pose, font, label,
                        widget.x + actionWidth / 2, widget.y + (widgetHeight - 8) / 2, SteampunkPalette.TEXT);
            }
        }
        // Original widgets still own clicks, keyboard navigation, narration and the launch callbacks.
        ci.cancel();
    }
    @Unique private static void peercraft$frame(PoseStack pose, int x, int y, int w, int h, int fill, int border) {
        GuiComponent.fill(pose, x, y, x + w, y + h, border);
        GuiComponent.fill(pose, x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    private static Component maxPlayersLabel(int value) {
        return new TranslatableComponent("peercraft.mixin.share_to_lan.max_players")
                .append(new TextComponent(": " + value));
    }

    /** {@link Checkbox} with an after-toggle callback — 1.16.5 has no {@code onValueChange}. */
    private static final class CallbackCheckbox extends Checkbox {
        private final Consumer<Boolean> onChange;

        CallbackCheckbox(int x, int y, int width, int height, Component message, boolean selected, Consumer<Boolean> onChange) {
            super(x, y, width, height, message, selected);
            this.onChange = onChange;
        }

        @Override
        public void onPress() {
            super.onPress();
            onChange.accept(this.selected());
        }
    }
}
