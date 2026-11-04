package net.peercraft.client.gui;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.theme.SteampunkPalette;
import java.util.ArrayList;
import java.util.List;

/** Isolated visual fixture. No registration, address exchange, or production protection. */
public final class JoinRequestsPreview implements ClientModInitializer {
    private boolean creating, ready;
    private int reviewTicks;
    private static boolean protection = true;
    private static long toastUntil;
    private static final List<Request> requests = new ArrayList<>();
    private static String result = "";
    private record Request(String name, long expires) { }
    private static void addRequest() {
        requests.clear();
        requests.add(new Request("Alex", System.nanoTime() + 120_000_000_000L));
        requests.add(new Request("Wanderer", System.nanoTime() + 120_000_000_000L));
        toastUntil = System.nanoTime() + 5_000_000_000L;
    }
    @Override public void onInitializeClient() {
        net.peercraft.config.TelemetryConsent.choose(false);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("peercraft", "join_preview"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.gui.screen() != null || System.nanoTime() > toastUntil) return;
            int right = mc.getWindow().getGuiScaledWidth() - 8;
            int left = Math.max(8, right - 250);
            SteampunkSettingsTheme.frame(g,left,48,right-left,48,SteampunkPalette.PANEL,SteampunkPalette.BORDER);
            g.text(mc.font,"Новые запросы на подключение · " + pending(),left+10,57,SteampunkPalette.ACCENT);
            g.text(mc.font,"Рассмотреть можно в меню Esc",left+10,73,SteampunkPalette.TEXT);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!creating && mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null) {
                creating = true;
                var settings = new net.minecraft.world.level.LevelSettings("PeerCraft — заявки (предпросмотр)", net.minecraft.world.level.GameType.CREATIVE,
                        new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.PEACEFUL, false, false),
                        true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("join-preview-" + System.currentTimeMillis(), settings,
                        new net.minecraft.world.level.levelgen.WorldOptions(123, false, false),
                        registries -> registries.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
            }
            if (!ready && creating && mc.level != null && mc.player != null && mc.getConnection().hasClientLoaded()) {
                ready = true; addRequest(); mc.gui.setScreen(new Panel(null, 0));
                System.out.println("JOIN_REQUESTS_PREVIEW_READY");
            }
            if (ready) {
                reviewTicks++;
                if (reviewTicks == 20) capture(mc, "requests");
                if (reviewTicks == 25) mc.gui.setScreen(new Panel(null, 2));
                if (reviewTicks == 40) capture(mc, "settings");
                if (reviewTicks == 45) mc.gui.setScreen(new Panel(null, 1));
                if (reviewTicks == 60) capture(mc, "guest");
                if (reviewTicks == 65) { addRequest(); mc.gui.setScreen(null); }
                if (reviewTicks == 85) {
                    if (mc.gui.screen() != null) throw new IllegalStateException("Notification opened a screen");
                    capture(mc, "toast");
                    System.out.println("JOIN_NOTIFICATION_NO_SCREEN_PASSED");
                }
                if (reviewTicks == 175) {
                    if (System.nanoTime() < toastUntil) throw new IllegalStateException("Toast did not expire");
                    capture(mc, "toast-expired");
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                }
                if (reviewTicks == 195) capture(mc, "pause");
                if (reviewTicks == 200) { addRequest(); mc.gui.setScreen(new Panel(null, 0)); }
            }
            if (ready && mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen pause) {
                boolean exists = pause.children().stream().anyMatch(c -> c instanceof Button b && b.getMessage().getString().startsWith("Запросы на подключение"));
                if (!exists) {
                    // Fixture uses the public Screen accessor through a tiny subclass-independent invoker.
                    try {
                        var method = Screen.class.getDeclaredMethod("addRenderableWidget", net.minecraft.client.gui.components.events.GuiEventListener.class);
                        method.setAccessible(true);
                        int bottom = pause.children().stream()
                                .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
                                .mapToInt(c -> ((net.minecraft.client.gui.components.AbstractWidget)c).getY()
                                        + ((net.minecraft.client.gui.components.AbstractWidget)c).getHeight()).max().orElse(100);
                        Button button = SteampunkSettingsTheme.action((pause.width - 204)/2, Math.min(bottom+4,pause.height-24), 204, 20,
                                Component.literal("Запросы на подключение · " + pending()), b -> mc.gui.setScreen(new Panel(pause, 0)), false);
                        method.invoke(pause, button);
                    } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                }
            }
        });
    }
    private void capture(Minecraft mc, String name) {
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, "join-"+name+".png", mc.gameRenderer.mainRenderTarget(), 1,
                message -> System.out.println("JOIN_PREVIEW_CAPTURED " + name));
    }
    private static int pending() { requests.removeIf(r -> r.expires < System.nanoTime()); return requests.size(); }

    public static final class Panel extends Screen {
        private final Screen parent;
        private int page;
        private String guest = "Заявка отправлена. Ожидает ответа хоста.";
        private long guestDeadline = System.nanoTime() + 120_000_000_000L;
        Panel(Screen parent, int page) { super(Component.literal("Запросы на подключение")); this.parent = parent; this.page = page; }
        private int w() { return Math.min(440, width - 20); }
        private int h() { return Math.min(350, height - 16); }
        private int x() { return (width - w()) / 2; }
        private int y() { return (height - h()) / 2; }
        private void button(int dx, int dy, int bw, String label, Runnable action, boolean primary) {
            addRenderableWidget(SteampunkSettingsTheme.action(x()+dx, y()+dy, bw, 20, Component.literal(label), b -> action.run(), primary));
        }
        @Override protected void init() {
            int tab = (w()-32)/3;
            button(12, 44, tab, "Заявки хосту", () -> {page=0; rebuildWidgets();}, page==0);
            button(16+tab, 44, tab, "У гостя", () -> {page=1; rebuildWidgets();}, page==1);
            button(20+tab*2, 44, tab, "Настройки", () -> {page=2; rebuildWidgets();}, page==2);
            if (page==0) {
                pending();
                for (int i=0; i<requests.size(); i++) {
                    Request r = requests.get(i); int dy = 135 + i*70; int bw=(w()-48)/3;
                    button(18, dy, bw, "Разрешить", () -> decide(r,"Вход разрешён на 2 минуты."), true);
                    button(24+bw, dy, bw, "Отклонить", () -> decide(r,"Заявка отклонена."), false);
                    button(30+bw*2, dy, bw, "Заблокировать", () -> decide(r,"Игрок заблокирован."), false);
                }
                button(12, h()-60, w()-24, "Предпросмотр: новая заявка и уведомление", () -> {addRequest(); result=""; minecraft.gui.setScreen(null);}, false);
            } else if (page==1) {
                button(18, 155, w()-36, "К списку комнат", () -> {guest="Заявка ожидает ответа. Вы можете выбирать другие комнаты."; rebuildWidgets();}, false);
                button(18, 181, w()-36, "Отменить заявку", () -> {guest="Заявка отменена."; guestDeadline=0;}, false);
                button(18, 227, (w()-42)/2, "Пример: одобрено", () -> {guest="Хост разрешил вход. Войти можно в течение 2 минут."; guestDeadline=System.nanoTime()+120_000_000_000L; rebuildWidgets();}, false);
                button(24+(w()-42)/2, 227, (w()-42)/2, "Пример: нет ответа", () -> {guest="Хост не ответил. Возможно, он сейчас занят. Попробуйте позже."; guestDeadline=0;}, false);
                if (guest.startsWith("Хост разрешил")) button(18, 260, w()-36, "Войти", () -> {guest="Предпросмотр: сетевое подключение не выполняется."; guestDeadline=0; rebuildWidgets();}, true);
            } else {
                button(18, 100, w()-36, "Одобрять незнакомцев: " + (protection?"ВКЛ":"ВЫКЛ"), () -> {protection=!protection; rebuildWidgets();}, protection);
            }
            button(12, h()-30, w()-24, "Готово", this::onClose, true);
        }
        private void decide(Request r, String message) {requests.remove(r); result=r.name+": "+message; rebuildWidgets();}
        @Override public void tick() {
            int before=requests.size(); pending(); if (before!=requests.size()) {result="Срок заявки истёк."; rebuildWidgets();}
            if (page==1 && guestDeadline>0 && System.nanoTime()>guestDeadline) {guest="Хост не ответил. Возможно, он сейчас занят. Попробуйте позже."; guestDeadline=0; rebuildWidgets();}
        }
        @Override public boolean isPauseScreen() {return false;}
        @Override public void onClose() {minecraft.gui.setScreen(parent != null ? parent : new net.minecraft.client.gui.screens.PauseScreen(true));}
        @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float delta) {
            super.extractBackground(g,mx,my,delta);
            SteampunkSettingsTheme.frame(g,x(),y(),w(),h(),SteampunkPalette.PANEL,SteampunkPalette.BORDER);
        }
        private void text(GuiGraphicsExtractor g,String text,int dy,int color) {
            int line=0;
            for (String s : PeerCraftUi.wrap(font,text,w()-36)) g.text(font,s,x()+18,y()+dy+(line++)*12,color);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
            super.extractRenderState(g,mx,my,delta);
            text(g,"PeerCraft / Запросы на подключение",12,SteampunkPalette.ACCENT);
            text(g,"Предпросмотр интерфейса · тестовые заявки",28,SteampunkPalette.MUTED);
            if(page==0) {
                text(g,"Разрешая вход незнакомцу, вы раскрываете ему свой настоящий публичный IP-адрес.",76,SteampunkPalette.TEXT);
                for(int i=0;i<requests.size();i++) {
                    Request r=requests.get(i); long sec=Math.max(0,(r.expires-System.nanoTime())/1_000_000_000L);
                    text(g,r.name+" · хочет присоединиться · "+sec+" с",116+i*70,SteampunkPalette.ACCENT);
                }
                if(requests.isEmpty()) text(g,"Нет ожидающих заявок",120,SteampunkPalette.MUTED);
                text(g,result,257,SteampunkPalette.MUTED);
            } else if(page==1) {
                text(g,guest,80,SteampunkPalette.TEXT);
                if(guestDeadline>0) text(g,"Осталось "+Math.max(0,(guestDeadline-System.nanoTime())/1_000_000_000L)+" с. Ждать на этом экране не обязательно.",118,SteampunkPalette.MUTED);
            } else {
                text(g,"Публичные комнаты",77,SteampunkPalette.ACCENT);
                text(g,protection?"Незнакомые игроки смогут подключиться только с вашего разрешения. Друзьям заявка не нужна.":"Незнакомые игроки смогут подключаться без одобрения и узнавать ваш настоящий публичный IP-адрес.",136,SteampunkPalette.TEXT);
                text(g,"Во время игры — только короткое уведомление в углу. Рассмотреть заявки можно в меню Esc.",195,SteampunkPalette.MUTED);
                text(g,"Настройка здесь тестовая. Серверная защита ещё не включена.",249,SteampunkPalette.MUTED);
            }
        }
    }
}
