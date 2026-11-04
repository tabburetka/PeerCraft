package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiShareToLan;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.GuiScreenEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge-event hook for the title screen and the "Open to LAN" screen — the same role the
 * {@code src/client-1122} {@code PeerCraftScreenEvents} plays (title / Open-to-LAN GUI hooks
 * are plain Forge events on both backports; only the server-side mixins stay {@code @Mixin}).
 * Lives in {@code net.peercraft.client.gui} so it can see the package-private widget shims.
 * Registered from {@code ClientProxy}.
 *
 * <p>1.7.10 deltas vs the 1.12.2 twin: {@code @SubscribeEvent} moved to {@code cpw.mods.fml}
 * ({@code net.minecraftforge.client.event.GuiScreenEvent} itself did not move); and 1.7.10's
 * {@code GuiScreenEvent} exposes public fields ({@code event.gui}, {@code event.button},
 * {@code event.buttonList}) rather than the {@code getGui()/getButton()/getButtonList()}
 * getters added in 1.8.9.
 *
 * <ul>
 *   <li>Title screen: the vanilla "Multiplayer" click is intercepted and redirected to
 *       {@link PeerCraftMultiplayerScreen} (unless {@code peercraft.mode=disabled}).</li>
 *   <li>Open-to-LAN screen: adds the "play over the internet" checkbox plus the max-players
 *       stepper and allow-unlicensed / friends-only / public-room checkboxes, gated on the
 *       internet toggle. The public-room world-name field from the modern UI is dropped —
 *       {@code OpenToLanMixin} falls back to the level name (see its {@code publicRoomWorldName}).</li>
 * </ul>
 */
public class PeerCraftScreenEvents {

    @SubscribeEvent
    public void onJoinRequestHud(net.minecraftforge.client.event.RenderGameOverlayEvent.Post event){
        if(event.type != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL)return;
        Minecraft mc=Minecraft.getMinecraft();
        net.peercraft.network.admission.AdmissionClient client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        if(mc.thePlayer==null || System.currentTimeMillis()>client.toastUntil || client.noticeKey.isEmpty())return;
        int right=event.resolution.getScaledWidth()-8,left=Math.max(8,right-250);
        net.minecraft.client.gui.Gui.drawRect(left,8,right,52,net.peercraft.client.theme.SteampunkPalette.PANEL);
        int i=0;for(Object line:mc.fontRenderer.listFormattedStringToWidth(PeerCraftLang.tr(client.noticeKey),right-left-16)){
            if(i>=2)break;mc.fontRenderer.drawString((String)line,left+8,17+i++*12,net.peercraft.client.theme.SteampunkPalette.ACCENT);
        }
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST)
    public void onPlayerList(net.minecraftforge.client.event.RenderGameOverlayEvent.Pre event) {
        SessionPlayerListOverlay.render(event);
    }
    @SubscribeEvent
    public void onTransportNoticeTick(cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent event) {
        if (event.phase == cpw.mods.fml.common.gameevent.TickEvent.Phase.END) TransportNoticeController.tick();
    }


    private final List<GuiButton> internetGated = new ArrayList<GuiButton>();

    @SubscribeEvent
    public void onGuiOpen(net.minecraftforge.client.event.GuiOpenEvent event) {
        // Disconnect constructs a fresh vanilla multiplayer screen instead of using the title button.
        // Exact class matching preserves PeerCraft and other mods' multiplayer subclasses.
        if (!PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())
                && event.gui != null && event.gui.getClass() == GuiMultiplayer.class) {
            event.gui = new PeerCraftMultiplayerScreen(new GuiMainMenu());
        }
        // Replace the native entry point; leave already themed and third-party subclasses intact.
        if (!PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())
                && event.gui != null && event.gui.getClass() == GuiShareToLan.class) {
            event.gui = new PeerCraftLanScreen(Minecraft.getMinecraft().currentScreen);
        }
    }

    @SubscribeEvent
    public void onActionPre(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        GuiButton button = event.button;

        if (event.gui instanceof GuiMainMenu) {
            if (button instanceof UpdateNoticeButton) {
                ((UpdateNoticeButton) button).press();
                event.setCanceled(true);
                return;
            }
            if (!PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())
                    && button.displayString.equals(I18n.format("menu.multiplayer"))) {
                Minecraft.getMinecraft().displayGuiScreen(new PeerCraftMultiplayerScreen(event.gui));
                event.setCanceled(true);
            }
            return;
        }

        if (event.gui instanceof PeerCraftLanScreen) return;
        if (event.gui instanceof GuiShareToLan) {
            if (button instanceof ToggleButton) {
                ((ToggleButton) button).fire();
                event.setCanceled(true);
            } else if (button instanceof IdButton) {
                ((IdButton) button).onPress.run();
                event.setCanceled(true);
            }
            return;
        }

        if (event.gui instanceof GuiIngameMenu && button.id == 7 && !PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) {
            Minecraft.getMinecraft().displayGuiScreen(new PeerCraftLanScreen(event.gui));
            event.setCanceled(true); return;
        }

        if (event.gui instanceof GuiIngameMenu && button instanceof IdButton) {
            ((IdButton) button).onPress.run();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onInitPost(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiMainMenu) {
            // Added last so it sees every other button when keeping clear of them; inert until
            // a newer release is known. Independent of mode: a disabled PeerCraft can be outdated.
            event.buttonList.add(new UpdateNoticeButton(event.gui, event.buttonList));
            // Idempotent; the title screen always precedes any world, so in-game notices are ready.
            if (!PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) FriendRequestNotifier.start();
            return;
        }
        if (event.gui instanceof PeerCraftLanScreen) return;
        if (event.gui instanceof GuiIngameMenu) {
            onPauseMenuInit(event);
            return;
        }
        if (!(event.gui instanceof GuiShareToLan)) {
            return;
        }
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) {
            return;
        }

        GuiScreen gui = event.gui;
        // 1.7.10's InitGuiEvent.buttonList is a raw List AND is the screen's real button list
        // (ActionPerformedEvent's, by contrast, is a copy) — adding here in .Post adds real,
        // clickable widgets.
        @SuppressWarnings("unchecked")
        List<GuiButton> buttons = event.buttonList;
        this.internetGated.clear();

        // Adaptive bottom-anchored layout — matches the modern ShareToLanScreenMixin. Vanilla
        // GuiShareToLan has a fixed top (title y50, info y82, game-mode/cheats row y100..120)
        // and its "Start LAN World"/"Cancel" row pinned at height-28. The old fixed y=130
        // start let the five stacked widgets ride onto that row — and drift away from it — as
        // the GUI scale changed this.height. Instead: glue the column to just above the button
        // row and scale the row pitch to the leftover vertical room.
        int left = gui.width / 2 - 155;
        int rowCount = 5;
        int bandTop = 126;
        int bandBottom = gui.height - 28 - 6;
        int rowPitch = Math.max(14, Math.min(24, (bandBottom - bandTop) / rowCount));
        int rowH = Math.max(14, Math.min(20, rowPitch - 2));
        int blockTop = Math.max(bandTop, bandBottom - (rowPitch * (rowCount - 1) + 20));
        int y = blockTop;

        ToggleButton internet = new ToggleButton(left, y,
                PeerCraftLang.tr("peercraft.mixin.share_to_lan.internet_play"),
                PeerCraftHostOptions.internetPlayRequested,
                value -> {
                    PeerCraftHostOptions.internetPlayRequested = value;
                    setGatedVisible(value);
                });
        buttons.add(internet);

        y += rowPitch;
        IdButton maxPlayers = CycleTextButton.create(left, y, 150, rowH,
                intRange(1, 8),
                clampMaxPlayers(),
                v -> PeerCraftLang.tr("peercraft.mixin.share_to_lan.max_players") + ": " + v,
                v -> PeerCraftHostOptions.maxPlayers = v);
        buttons.add(maxPlayers);
        this.internetGated.add(maxPlayers);

        y += rowPitch;
        ToggleButton allowUnlicensed = new ToggleButton(left, y,
                PeerCraftLang.tr("peercraft.mixin.share_to_lan.allow_unlicensed"),
                PeerCraftHostOptions.allowUnlicensedPlayers,
                value -> PeerCraftHostOptions.allowUnlicensedPlayers = value);
        buttons.add(allowUnlicensed);
        this.internetGated.add(allowUnlicensed);

        boolean loggedIn = AccountClient.INSTANCE.getCurrentSession() != null;

        y += rowPitch;
        ToggleButton[] friendsHolder = new ToggleButton[1];
        ToggleButton publicRoom = new ToggleButton(left, y,
                PeerCraftLang.tr("peercraft.mixin.share_to_lan.public_room"),
                PeerCraftHostOptions.publicRoom,
                value -> {
                    PeerCraftHostOptions.publicRoom = value;
                    if (friendsHolder[0] != null) {
                        friendsHolder[0].enabled = !value && loggedIn;
                    }
                });
        buttons.add(publicRoom);
        this.internetGated.add(publicRoom);

        y += rowPitch;
        ToggleButton friendsOnly = new ToggleButton(left, y,
                PeerCraftLang.tr("peercraft.mixin.share_to_lan.friends_only"),
                PeerCraftHostOptions.friendsOnly,
                value -> {
                    PeerCraftHostOptions.friendsOnly = value;
                    publicRoom.enabled = !value;
                });
        friendsOnly.enabled = loggedIn && !PeerCraftHostOptions.publicRoom;
        publicRoom.enabled = !PeerCraftHostOptions.friendsOnly;
        friendsHolder[0] = friendsOnly;
        buttons.add(friendsOnly);
        this.internetGated.add(friendsOnly);

        setGatedVisible(PeerCraftHostOptions.internetPlayRequested);
    }

    /** Keep vanilla controls and add one handoff entry below them. */
    @SuppressWarnings("unchecked")
    /** One small "PC" entry beside "Back to game" that opens the in-game PeerCraft menu; never moves vanilla buttons. */
    private void onPauseMenuInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) return;
        GuiScreen gui = event.gui;
        @SuppressWarnings("unchecked")
        List<GuiButton> buttons = event.buttonList;
        if (buttons.isEmpty()) return;
        List<int[]> occupied = new ArrayList<>();
        int[] anchor = null;
        for (GuiButton button : buttons) {
            int[] rect = {button.xPosition, button.yPosition, button.width, button.height};
            occupied.add(rect);
            // "Back to game" is the topmost full-width button of the vanilla menu.
            if (button.width >= 150 && (anchor == null || rect[1] < anchor[1])) anchor = rect;
        }
        net.peercraft.client.theme.SessionPauseLayout layout =
                net.peercraft.client.theme.SessionPauseLayout.icon(gui.width, gui.height, occupied, anchor, 20);
        String label = FriendRequestNotifier.pendingCount() > 0 ? "\u00a7ePC" : "PC";
        buttons.add(IdButton.builder(label,
                () -> Minecraft.getMinecraft().displayGuiScreen(new PeerCraftInGameMenuScreen(gui)))
                .bounds(layout.left, layout.top, 20, 20).build());
    }

    private void setGatedVisible(boolean visible) {
        for (GuiButton b : this.internetGated) {
            b.visible = visible;
        }
    }

    private static int clampMaxPlayers() {
        int v = PeerCraftHostOptions.maxPlayers;
        return v < 1 || v > 8 ? 8 : v;
    }

    private static List<Integer> intRange(int lo, int hi) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = lo; i <= hi; i++) {
            out.add(i);
        }
        return out;
    }
}
