package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.network.rendezvous.AccountProtocol;

import java.util.List;
import java.util.Locale;

/**
 * Friends list — loaded async on open (see the null-{@code friends} "loading" constructor).
 * Rows are a plain stacked list (no scrolling yet) capped at {@link #MAX_ROWS_SHOWN}; a hobby
 * project's friend list realistically fits, and this avoids taking on ObjectSelectionList's
 * more involved API before it's actually needed.
 *
 * "Подключиться" is wired up to reuse the join screen's flow once presence lands (Фаза 4) — for
 * now every friend's status is always OFFLINE (see AccountService.listFriends), so the button
 * stays disabled.
 */
public class PeerCraftFriendsScreen extends Screen {

    private static final int MAX_ROWS_SHOWN = 8;
    private static final int ROW_HEIGHT = 22;

    private final Screen lastScreen;
    private final List<AccountClient.FriendInfo> friends;
    private Component statusMessage = Component.empty();
    private EditBox addByCodeBox;
    private Button addByCodeButton;

    public PeerCraftFriendsScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftFriendsScreen(Screen lastScreen, List<AccountClient.FriendInfo> friends) {
        super(Component.literal("PeerCraft — друзья"));
        this.lastScreen = lastScreen;
        this.friends = friends;
    }

    @Override
    protected void init() {
        if (this.friends == null) {
            this.statusMessage = Component.literal("Загружаем список друзей...");
            AccountClient.INSTANCE.listFriends(new AccountClient.FriendListCallback() {
                @Override
                public void onResult(List<AccountClient.FriendInfo> result) {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            minecraft.setScreen(new PeerCraftFriendsScreen(lastScreen, result));
                        }
                    });
                }

                @Override
                public void onTimeout() {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            statusMessage = Component.literal("Сервер аккаунтов не ответил");
                        }
                    });
                }
            });

            this.addRenderableWidget(Button.builder(Component.literal("Назад"), b -> this.minecraft.setScreen(this.lastScreen))
                    .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
            return;
        }

        int centerX = this.width / 2;
        int top = 40;

        int shown = Math.min(this.friends.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.FriendInfo friend = this.friends.get(i);
            int rowY = top + i * ROW_HEIGHT;
            Button connectButton = Button.builder(Component.literal("Подключиться"), b -> onConnectToFriend(friend))
                    .bounds(centerX + 30, rowY, 100, 20).build();
            connectButton.active = friend.status() == AccountProtocol.STATUS_HOSTING;
            this.addRenderableWidget(connectButton);

            this.addRenderableWidget(Button.builder(Component.literal("Удалить"), b -> onRemoveFriend(friend))
                    .bounds(centerX + 135, rowY, 65, 20).build());
        }
        if (this.friends.size() > MAX_ROWS_SHOWN) {
            this.statusMessage = Component.literal("Показаны первые " + MAX_ROWS_SHOWN + " из " + this.friends.size());
        } else if (this.friends.isEmpty()) {
            this.statusMessage = Component.literal("Пока нет друзей — найдите игроков или добавьте по коду ниже");
        }

        int bottomY = top + shown * ROW_HEIGHT + 10;
        this.addByCodeBox = new EditBox(this.font, centerX - 200, bottomY, 120, 20, Component.literal("Friend code"));
        this.addByCodeBox.setMaxLength(6);
        this.addByCodeBox.setHint(Component.literal("Код друга"));
        this.addRenderableWidget(this.addByCodeBox);
        this.addByCodeButton = this.addRenderableWidget(Button.builder(Component.literal("Добавить по коду"), b -> onAddByCode())
                .bounds(centerX - 70, bottomY, 150, 20).build());

        bottomY += 26;
        this.addRenderableWidget(Button.builder(Component.literal("Заявки в друзья"), b -> this.minecraft.setScreen(new PeerCraftFriendRequestsScreen(this.lastScreen)))
                .bounds(centerX - 200, bottomY, 190, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Найти игроков"), b -> this.minecraft.setScreen(new PeerCraftSearchScreen(this.lastScreen)))
                .bounds(centerX + 10, bottomY, 190, 20).build());

        bottomY += 26;
        this.addRenderableWidget(Button.builder(Component.literal("Назад"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, bottomY, 200, 20).build());
    }

    private void onAddByCode() {
        String code = this.addByCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        if (code.length() != 6) {
            this.statusMessage = Component.literal("Код дружбы — это 6 символов");
            return;
        }
        this.addByCodeButton.active = false;
        this.statusMessage = Component.literal("Ищем игрока...");
        AccountClient.INSTANCE.lookupFriendCode(code, new AccountClient.FriendCodeLookupCallback() {
            @Override
            public void onResult(boolean found, java.util.UUID accountId, boolean licensed, String displayName) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    if (!found) {
                        statusMessage = Component.literal("Игрок с таким кодом не найден");
                        addByCodeButton.active = true;
                        return;
                    }
                    sendRequestTo(accountId, displayName);
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = Component.literal("Сервер аккаунтов не ответил");
                    addByCodeButton.active = true;
                });
            }
        });
    }

    private void sendRequestTo(java.util.UUID targetAccountId, String displayName) {
        AccountClient.INSTANCE.sendFriendRequest(targetAccountId, new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = Component.literal("Заявка отправлена игроку " + displayName);
                    addByCodeButton.active = true;
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = Component.literal(reason);
                    addByCodeButton.active = true;
                });
            }
        });
    }

    private void onRemoveFriend(AccountClient.FriendInfo friend) {
        AccountClient.INSTANCE.removeFriend(friend.accountId(), new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        minecraft.setScreen(new PeerCraftFriendsScreen(lastScreen));
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        statusMessage = Component.literal(reason);
                    }
                });
            }
        });
    }

    /** The whole point of this feature — no room code ever typed, just the code the server already told us the friend is hosting under. */
    private void onConnectToFriend(AccountClient.FriendInfo friend) {
        this.statusMessage = Component.literal("Подключаемся к " + friend.displayName() + "...");
        P2PBridge.INSTANCE.startClientViaRendezvous(friend.roomCode(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override
                    public void onStatus(String message) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                statusMessage = Component.literal(message);
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        runOnClientThread(() -> {
                            if (!stillOnThisScreen()) {
                                return;
                            }
                            int port = P2PBridge.INSTANCE.getProxyPort();
                            ServerAddress address = new ServerAddress("127.0.0.1", port);
                            ServerData serverData = new ServerData("PeerCraft", "127.0.0.1:" + port, ServerData.Type.OTHER);
                            ConnectScreen.startConnecting(lastScreen, minecraft, address, serverData, false, null);
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                statusMessage = Component.literal(reason);
                            }
                        });
                    }
                });
    }

    // ---- periodic refresh while the screen is open (~every 5s) ----

    private static final int POLL_INTERVAL_TICKS = 100;
    private int ticksSincePoll = 0;

    @Override
    public void tick() {
        super.tick();
        if (this.friends == null) {
            return; // initial load still in flight
        }
        if (++this.ticksSincePoll < POLL_INTERVAL_TICKS) {
            return;
        }
        this.ticksSincePoll = 0;
        AccountClient.INSTANCE.listFriends(new AccountClient.FriendListCallback() {
            @Override
            public void onResult(List<AccountClient.FriendInfo> result) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        minecraft.setScreen(new PeerCraftFriendsScreen(lastScreen, result));
                    }
                });
            }

            @Override
            public void onTimeout() {
                // Silent — keep showing the last known list rather than clearing it.
            }
        });
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    private boolean stillOnThisScreen() {
        return Minecraft.getInstance().screen == this;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 15, 0xFFFFFF);

        if (this.friends != null) {
            int top = 40;
            int shown = Math.min(this.friends.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.FriendInfo friend = this.friends.get(i);
                String badge = friend.licensed() ? " ✓" : " ☠";
                String status = switch (friend.status()) {
                    case AccountProtocol.STATUS_HOSTING -> "хостит (" + friend.roomCode() + ")";
                    case AccountProtocol.STATUS_ONLINE -> "онлайн";
                    default -> "офлайн";
                };
                graphics.drawString(this.font, friend.displayName() + badge + " — " + status,
                        centerX - 200, top + i * ROW_HEIGHT + 6, 0xFFFFFF, false);
            }
        }

        graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height - 45, 0xFFFF55);
    }
}
