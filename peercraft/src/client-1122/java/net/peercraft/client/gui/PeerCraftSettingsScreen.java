package net.peercraft.client.gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;
import java.util.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
/** Complete settings editor for legacy Forge, using the shared panel and original settings store. */
public class PeerCraftSettingsScreen extends PeerCraftDialogScreen {
    private static final int KIND_BOOL=0, KIND_INT=1, KIND_STRING=2, KIND_MODE=3, KIND_MODSYNC=4;
    private static final List<String> MODE_VALUES=Arrays.asList("auto","client","host","disabled");
    private static final List<String> MODSYNC_VALUES=Arrays.asList("off","required","all");
    private final GuiScreen lastScreen;
    private PeerCraftSettings settings;
    private final List<Row> rows=new ArrayList<>();
    private ToggleButton developerToggle;
    private int rowsTop, listBottom, rowPitch, controlWidth, labelX, scroll;
    private boolean draggingScrollbar;
    private String statusMessage="";
    private int statusColor=PeerCraftUi.TEXT_MUTED;
    private static final class Row {
        final String key, labelKey;
        final int kind, min, max;
        final boolean developer, restart;
        String warnOverride;
        Object widget;
        List<String> cycleValues;
        int cycleIndex, screenY=-1;
        boolean enabled;
        Row(String key,int kind,boolean developer,boolean restart,int min,int max,String labelKey) {
            this.key=key;this.kind=kind;this.developer=developer;this.restart=restart;this.min=min;this.max=max;this.labelKey=labelKey;
        }
    }
    public PeerCraftSettingsScreen(GuiScreen lastScreen) {
        super(PeerCraftLang.tr("peercraft.gui.settings.title"),600,440);this.lastScreen=lastScreen;
    }
    private static String builtinDefault(String key) {
        switch (key) {
            case "mode": return "auto";
            case "proxyPort": return "25566";
            case "clientUdpPort": return "50002";
            case "hostUdpPort": return "50001";
            case "peerHost": return "127.0.0.1";
            case "peerPort": return "";
            case "internetPlay": return "false";
            case "rendezvousHost": return "91.146.31.165";
            case "rendezvousPort": return "51000";
            case "maxPlayers": return "4";
            case "modSync.host": return "all";
            case "modSync.client": return "all";
            case "modSync.autoAccept": return "false";
            case "modSync.reofferDeclined": return "false";
            case "modSync.maxTotalMb": return "512";
            case "modSync.maxModMb": return "256";
            case "handoff": return "true";
            case "handoff.declineSuccessor": return "false";
            case "handoff.confirmBeforeOffer": return "true";
            case "handoff.chatNotify": return "true";
            default: return "";
        }
    }

    private static String envName(String key) {
        StringBuilder b = new StringBuilder("PEERCRAFT_");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                b.append('_');
            } else if (Character.isUpperCase(c)) {
                b.append('_').append(c);
            } else {
                b.append(Character.toUpperCase(c));
            }
        }
        return b.toString();
    }

    private static boolean forcedByFlag(String key) {
        String prop = System.getProperty("peercraft." + key);
        if (prop != null && !prop.trim().isEmpty()) {
            return true;
        }
        String env = System.getenv(envName(key));
        return env != null && !env.trim().isEmpty();
    }

    private String seed(String key) {
        String saved = this.settings.get(key);
        if (saved != null && !saved.trim().isEmpty()) {
            return saved.trim();
        }
        String baseline = PeerCraftConfig.baselineValue(key);
        return !baseline.isEmpty() ? baseline : builtinDefault(key);
    }
    @Override public void initGui() {
        for(Row row:rows) if(row.widget!=null && settings!=null) settings.set(row.key,readWidget(row));
        super.initGui(); Keyboard.enableRepeatEvents(true);this.buttonList.clear();
        if(settings==null) settings=PeerCraftSettingsStore.load();
        labelX=dialog.contentX()+4;
        controlWidth=Math.max(70,Math.min(150,dialog.contentWidth()*2/5));
        rowsTop=dialog.contentTop()+36;rowPitch=dialog.compact?24:30;
        listBottom=backY()-28; rows.clear();
        addRow("modSync.client", KIND_MODSYNC, false, false, 0, 0, "modsync_client");
        addRow("modSync.host", KIND_MODSYNC, false, false, 0, 0, "modsync_host");
        addRow("modSync.reofferDeclined", KIND_BOOL, false, false, 0, 0, "reoffer_declined");
        addRow("modSync.maxTotalMb", KIND_INT, false, false, 1, 4096, "max_total_mb");
        addRow("modSync.maxModMb", KIND_INT, false, false, 1, 2048, "max_mod_mb");
        addRow("internetPlay", KIND_BOOL, false, false, 0, 0, "internet_play");
        addRow("maxPlayers", KIND_INT, false, false, 1, 8, "max_players");
        addRow("handoff", KIND_BOOL, false, false, 0, 0, "handoff");
        addRow("handoff.declineSuccessor", KIND_BOOL, false, false, 0, 0, "handoff_decline_successor");
        addRow("handoff.confirmBeforeOffer", KIND_BOOL, false, false, 0, 0, "handoff_confirm_before_offer");
        addRow("handoff.chatNotify", KIND_BOOL, false, false, 0, 0, "handoff_chat_notify");

        addRow("mode", KIND_MODE, true, true, 0, 0, "mode");
        addRow("modSync.autoAccept", KIND_BOOL, true, false, 0, 0, "autoaccept").warnOverride = "autoaccept_warning";
        addRow("rendezvousHost", KIND_STRING, true, true, 0, 0, "rendezvous_host");
        addRow("rendezvousPort", KIND_INT, true, true, 0, 65535, "rendezvous_port");
        addRow("proxyPort", KIND_INT, true, true, 0, 65535, "proxy_port");
        addRow("clientUdpPort", KIND_INT, true, true, 0, 65535, "client_udp_port");
        addRow("hostUdpPort", KIND_INT, true, true, 0, 65535, "host_udp_port");
        addRow("peerHost", KIND_STRING, true, true, 0, 0, "peer_host");
        addRow("peerPort", KIND_INT, true, true, 0, 65535, "peer_port");

        for(Row row:rows) { row.enabled=!forcedByFlag(row.key);row.widget=buildWidget(row); }
        developerToggle=new ToggleButton(labelX,dialog.contentTop(),"",settings.showDeveloperSection,value->{
            settings.showDeveloperSection=value;scroll=0;relayout();
        });
        developerToggle.width=20;developerToggle.height=20;this.buttonList.add(developerToggle);
        int actionWidth=Math.max(1,(dialog.contentWidth()-12)/3);
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.settings.save"),this::onSave).primary()
                .bounds(dialog.contentX(),backY(),actionWidth,dialog.buttonHeight()).build());
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.settings.reset"),this::onReset)
                .bounds(dialog.contentX()+actionWidth+6,backY(),actionWidth,dialog.buttonHeight()).build());
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.settings.cancel"),()->PeerCraftUi.setScreen(mc,lastScreen))
                .bounds(dialog.contentX()+(actionWidth+6)*2,backY(),actionWidth,dialog.buttonHeight()).build());
        relayout();
    }
    private Row addRow(String key,int kind,boolean developer,boolean restart,int min,int max,String labelKey) {
        Row row=new Row(key,kind,developer,restart,min,max,labelKey);rows.add(row);return row;
    }
    private int controlX() { return dialog.contentX()+dialog.contentWidth()-controlWidth-12; }
    private Object buildWidget(Row row) {
        String current=seed(row.key);
        if(row.kind==KIND_INT || row.kind==KIND_STRING) {
            SteampunkField box=new SteampunkField(rows.size(), this.fontRenderer,controlX(),rowsTop,controlWidth,dialog.buttonHeight());
            box.setMaxStringLength(row.kind==KIND_INT?6:128);box.setText(current);box.setEnabled(row.enabled);return box;
        }
        GuiButton button;
        if(row.kind==KIND_BOOL) {
            ToggleButton cb=new ToggleButton(controlX(),rowsTop,"","true".equalsIgnoreCase(current),value->{});
            cb.width=16;cb.height=16;button=cb;
        } else {
            row.cycleValues=row.kind==KIND_MODE?MODE_VALUES:MODSYNC_VALUES;
            row.cycleIndex=Math.max(0,row.cycleValues.indexOf(row.cycleValues.contains(current)?current:row.cycleValues.get(row.cycleValues.size()-1)));
            IdButton[] self=new IdButton[1];
            self[0]=IdButton.builder(cycleLabel(row),()->{
                row.cycleIndex=(row.cycleIndex+1)%row.cycleValues.size();self[0].displayString=cycleLabel(row);
            }).bounds(controlX(),rowsTop,controlWidth,dialog.buttonHeight()).build();button=self[0];
        }
        button.enabled=row.enabled;this.buttonList.add(button);return button;
    }
    private String cycleLabel(Row row) { return PeerCraftLang.tr("peercraft.gui.settings."+(row.kind==KIND_MODE?"mode.":"modsync_mode.")+row.cycleValues.get(row.cycleIndex)); }
    private List<Row> visibleRows() {
        List<Row> out=new ArrayList<>();for(Row row:rows)if(!row.developer || settings.showDeveloperSection)out.add(row);return out;
    }
    private int rowsShown() { return Math.max(0,(listBottom-rowsTop)/rowPitch); }
    private void relayout() {
        List<Row> visible=visibleRows();int shown=rowsShown();scroll=Math.max(0,Math.min(scroll,Math.max(0,visible.size()-shown)));
        for(Row row:rows) {
            row.screenY=-1;
            if(row.widget instanceof GuiTextField) ((GuiTextField)row.widget).setVisible(false);
            else ((GuiButton)row.widget).visible=false;
        }
        for(int i=0;i<shown && scroll+i<visible.size();i++) {
            Row row=visible.get(scroll+i);row.screenY=rowsTop+i*rowPitch;
            if(row.widget instanceof GuiTextField) {
                GuiTextField field=(GuiTextField)row.widget;field.x=controlX()+5;field.y=row.screenY;field.setVisible(true);
            } else { GuiButton button=(GuiButton)row.widget;button.x=controlX();button.y=row.screenY;button.visible=true; }
        }
        for(Row row:rows)if(row.screenY<0 && row.widget instanceof GuiTextField)((GuiTextField)row.widget).setFocused(false);
    }
    private String readWidget(Row row) {
        if(row.widget instanceof GuiTextField)return ((GuiTextField)row.widget).getText();
        if(row.widget instanceof ToggleButton)return Boolean.toString(((ToggleButton)row.widget).isChecked());
        return row.cycleValues.get(row.cycleIndex);
    }
    private void onReset() {
        PeerCraftSettingsStore.clear();PeerCraftConfig.applyOverrides(new LinkedHashMap<String,String>());
        PeerCraftUi.setScreen(mc,new PeerCraftSettingsScreen(lastScreen));
    }
    private void focusRow(Row row) {
        if(row.developer && !settings.showDeveloperSection) { settings.showDeveloperSection=true;developerToggle.setIsChecked(true); }
        List<Row> visible=visibleRows();int index=visible.indexOf(row);
        if(index<scroll)scroll=index;else if(index>=scroll+rowsShown())scroll=index-Math.max(1,rowsShown())+1;
        relayout();for(Row r:rows)if(r.widget instanceof GuiTextField)((GuiTextField)r.widget).setFocused(r==row);
    }
    private void failValidation(Row row) {
        focusRow(row);statusMessage=PeerCraftLang.tr("peercraft.gui.settings.invalid_number",PeerCraftLang.tr("peercraft.gui.settings."+row.labelKey));statusColor=PeerCraftUi.TEXT_ERROR;
    }
    private void onSave() {
        for(Row row:rows)if(row.kind==KIND_INT) {
            String value=readWidget(row).trim();if(value.isEmpty())continue;
            try { int n=Integer.parseInt(value);if(n<row.min || n>row.max){failValidation(row);return;} }
            catch(NumberFormatException invalid){failValidation(row);return;}
        }
        for(Row row:rows) {
            String value=readWidget(row).trim();
            settings.set(row.key,value.isEmpty() || value.equals(builtinDefault(row.key)) || value.equals(PeerCraftConfig.baselineValue(row.key)) || forcedByFlag(row.key)?null:value);
        }
        settings.showDeveloperSection=developerToggle.isChecked();PeerCraftSettingsStore.save(settings);
        PeerCraftConfig.applyOverrides(settings.toOverrideMap());HandoffClientController.INSTANCE.resendPreference();PeerCraftUi.setScreen(mc,lastScreen);
    }
    @Override protected void actionPerformed(GuiButton button) throws java.io.IOException {
        if(button instanceof ToggleButton)((ToggleButton)button).fire();else if(button instanceof IdButton)((IdButton)button).onPress.run();
    }
    @Override public void onGuiClosed(){Keyboard.enableRepeatEvents(false);}
    @Override public void updateScreen(){for(Row row:rows)if(row.widget instanceof GuiTextField)((GuiTextField)row.widget).updateCursorCounter();}
    @Override protected void keyTyped(char typedChar,int keyCode) throws java.io.IOException {
        if(keyCode==Keyboard.KEY_ESCAPE){PeerCraftUi.setScreen(mc,lastScreen);return;}
        if(keyCode==Keyboard.KEY_TAB) {
            List<Row> edits=new ArrayList<>();int current=-1;
            for(Row row:visibleRows())if(row.enabled && row.widget instanceof GuiTextField){if(((GuiTextField)row.widget).isFocused())current=edits.size();edits.add(row);}
            if(!edits.isEmpty()){int delta=GuiScreen.isShiftKeyDown()?-1:1;focusRow(edits.get(current<0?(delta<0?edits.size()-1:0):(current+delta+edits.size())%edits.size()));}return;
        }
        for(Row row:rows)if(row.screenY>=0 && row.enabled && row.widget instanceof GuiTextField && ((GuiTextField)row.widget).textboxKeyTyped(typedChar,keyCode))return;
        if(keyCode==Keyboard.KEY_NEXT || keyCode==Keyboard.KEY_PRIOR){scroll+=(keyCode==Keyboard.KEY_NEXT?1:-1)*Math.max(1,rowsShown());relayout();return;}
        super.keyTyped(typedChar,keyCode);
    }
    @Override public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();int delta=Mouse.getEventDWheel();int x=Mouse.getEventX()*width/mc.displayWidth,y=height-Mouse.getEventY()*height/mc.displayHeight-1;
        if(delta!=0 && x>=dialog.contentX() && x<dialog.left+dialog.width && y>=rowsTop && y<listBottom){scroll-=(int)Math.signum(delta)*3;relayout();}
    }
    private int scrollbarX(){return dialog.left+dialog.width-9;}
    private void scrollAt(int y){
        int total=visibleRows().size(),shown=rowsShown(),track=listBottom-rowsTop;
        if(shown<=0 || total<=shown)return;
        int thumb=Math.max(8,track*shown/total);
        scroll=(int)Math.round(Math.max(0,Math.min(1,(y-rowsTop-thumb/2.0)/Math.max(1,track-thumb)))*(total-shown));relayout();
    }
    @Override protected void mouseClicked(int x,int y,int button) throws java.io.IOException {
        if(button==0 && x>=scrollbarX()-3 && x<scrollbarX()+7 && y>=rowsTop && y<listBottom){draggingScrollbar=true;scrollAt(y);return;}
        super.mouseClicked(x,y,button);
        if(mc.currentScreen!=this)return;
        for(Row row:rows)if(row.widget instanceof GuiTextField){GuiTextField field=(GuiTextField)row.widget;if(row.screenY>=0 && row.enabled)field.mouseClicked(x,y,button);else field.setFocused(false);}
    }
    @Override protected void mouseClickMove(int x,int y,int button,long elapsed){if(button==0 && draggingScrollbar){scrollAt(y);return;}super.mouseClickMove(x,y,button,elapsed);}
    @Override protected void mouseReleased(int x,int y,int button){if(button==0)draggingScrollbar=false;super.mouseReleased(x,y,button);}
    private String rowLabel(Row row){
        String label=PeerCraftLang.tr("peercraft.gui.settings."+row.labelKey);
        if(row.restart)label+=" "+PeerCraftLang.tr("peercraft.gui.settings.restart_hint");return label;
    }
    @Override public void drawScreen(int mouseX,int mouseY,float partialTicks){
        drawDefaultBackground();
        for(Row row:rows)if(row.screenY>=0 && row.widget instanceof GuiTextField)((GuiTextField)row.widget).drawTextBox();
        super.drawScreen(mouseX,mouseY,partialTicks);
        this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(PeerCraftLang.tr("peercraft.gui.settings.developer_toggle"),Math.max(1,dialog.contentWidth()-28)),labelX+24,dialog.contentTop()+5,PeerCraftUi.TEXT_MUTED);
        if(settings.showDeveloperSection)this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(PeerCraftLang.tr("peercraft.gui.settings.developer_warning"),dialog.contentWidth()),labelX,dialog.contentTop()+22,PeerCraftUi.TEXT_ERROR);
        List<String> tooltip=null;
        for(Row row:rows)if(row.screenY>=0){
            int color=!row.enabled?PeerCraftUi.TEXT_MUTED:row.warnOverride!=null?PeerCraftUi.TEXT_ERROR:PeerCraftUi.TEXT_TITLE;
            String label=rowLabel(row);this.fontRenderer.drawStringWithShadow(this.fontRenderer.trimStringToWidth(label,Math.max(1,controlX()-labelX-10)),labelX,row.screenY+5,color);
            if(mouseX>=labelX && mouseX<controlX() && mouseY>=row.screenY && mouseY<row.screenY+rowPitch){
                if(row.warnOverride!=null)label+="\n"+PeerCraftLang.tr("peercraft.gui.settings."+row.warnOverride);
                tooltip=PeerCraftUi.wrap(this.fontRenderer,label,dialog.contentWidth());
            }
        }
        int total=visibleRows().size(),shown=rowsShown();
        if(total>shown && shown>0){int track=listBottom-rowsTop,thumb=Math.max(8,track*shown/total),y=rowsTop+(track-thumb)*scroll/(total-shown);
            drawRect(scrollbarX(),rowsTop,scrollbarX()+3,listBottom,net.peercraft.client.theme.SteampunkPalette.BORDER);
            drawRect(scrollbarX(),y,scrollbarX()+3,y+thumb,PeerCraftUi.TEXT_ACCENT);}
        dialog.status(this.fontRenderer,statusMessage,backY()-20,16,statusColor);
        if(tooltip!=null)drawHoveringText(tooltip,mouseX,mouseY);
    }
}
