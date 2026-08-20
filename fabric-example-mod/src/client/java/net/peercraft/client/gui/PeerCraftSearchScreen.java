package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.network.account.AccountClient;

import java.util.List;

/** Player search/browser — results sorted licensed-first by the server (AccountService.search). */
public class PeerCraftSearchScreen extends Screen {

    private static final int MAX_ROWS_SHOWN = 8;
    private static final int ROW_HEIGHT = 22;

    private final Screen lastScreen;
    private List<AccountClient.SearchResult> results = List.of();

    private EditBox queryBox;
    private Button searchButton;
    private Component statusMessage = Component.empty();

    public PeerCraftSearchScreen(Screen lastScreen) {
        super(Component.literal("PeerCraft — найти игроков"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        this.queryBox = new EditBox(this.font, centerX - 100, 35, 200, 20, Component.literal("Query"));
        this.queryBox.setMaxLength(16);
        this.queryBox.setHint(Component.literal("Ник или часть ника"));
        this.addRenderableWidget(this.queryBox);
        this.setInitialFocus(this.queryBox);

        this.searchButton = this.addRenderableWidget(Button.builder(Component.literal("Искать"), b -> onSearch())
                .bounds(centerX + 105, 35, 90, 20).build());

        int top = 65;
        int shown = Math.min(this.results.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.SearchResult result = this.results.get(i);
            this.addRenderableWidget(Button.builder(Component.literal("Добавить"), b -> onAddFriend(result))
                    .bounds(centerX + 30, top + i * ROW_HEIGHT, 170, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Назад"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, this.height - 30, 200, 20).build());
    }

    private void onSearch() {
        String query = this.queryBox.getValue().trim();
        this.searchButton.active = false;
        this.statusMessage = Component.literal("Ищем...");
        AccountClient.INSTANCE.searchAccounts(query, new AccountClient.SearchCallback() {
            @Override
            public void onResult(List<AccountClient.SearchResult> found) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    results = found;
                    statusMessage = found.isEmpty() ? Component.literal("Никого не найдено") : Component.empty();
                    minecraft.setScreen(PeerCraftSearchScreen.this);
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = Component.literal("Сервер аккаунтов не ответил");
                    searchButton.active = true;
                });
            }
        });
    }

    private void onAddFriend(AccountClient.SearchResult result) {
        AccountClient.INSTANCE.sendFriendRequest(result.accountId(), new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        statusMessage = Component.literal("Заявка отправлена игроку " + result.displayName());
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

        int top = 65;
        int shown = Math.min(this.results.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.SearchResult result = this.results.get(i);
            String badge = result.licensed() ? " ✓" : " ☠";
            graphics.drawString(this.font, result.displayName() + badge, centerX - 200, top + i * ROW_HEIGHT + 6, 0xFFFFFF, false);
        }

        graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height - 45, 0xFFFF55);
    }
}
