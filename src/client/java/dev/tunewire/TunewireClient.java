package dev.tunewire;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.tunewire.config.TokenStore;
import dev.tunewire.config.TwConfig;
import dev.tunewire.gui.HudEditorScreen;
import dev.tunewire.gui.PlayerScreen;
import dev.tunewire.hud.TunewireHud;
import dev.tunewire.hud.WorldLyrics;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import dev.tunewire.spotify.SpotifyAuth;
import dev.tunewire.spotify.SpotifyPlayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TunewireClient implements ClientModInitializer {
    public static final String MOD_ID = "tunewire";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
    private static KeyMapping openKey, playKey, nextKey, prevKey, volUpKey, volDownKey, hudKey;
    private static Screen pendingScreen;

    @Override
    public void onInitializeClient() {
        TwConfig.load();
        TokenStore.load();

        openKey = key("open", GLFW.GLFW_KEY_UNKNOWN);
        playKey = key("play_pause", GLFW.GLFW_KEY_UNKNOWN);
        nextKey = key("next", GLFW.GLFW_KEY_UNKNOWN);
        prevKey = key("previous", GLFW.GLFW_KEY_UNKNOWN);
        volUpKey = key("volume_up", GLFW.GLFW_KEY_UNKNOWN);
        volDownKey = key("volume_down", GLFW.GLFW_KEY_UNKNOWN);
        hudKey = key("toggle_hud", GLFW.GLFW_KEY_UNKNOWN);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommandManager.literal("tunewire")
                        .executes(c -> {
                            openScreen(new PlayerScreen());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("login").executes(c -> {
                            SpotifyAuth.startLogin();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("logout").executes(c -> {
                            SpotifyAuth.logout();
                            chat("Disconnected from Spotify.", false);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("perf").executes(c -> {
                            Perf.overlay = !Perf.overlay;
                            chat(Perf.overlay ? "Performance overlay on (top right). Run /tunewire perf again to hide it."
                                    : "Performance overlay off.", false);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("hud").executes(c -> {
                            openScreen(new HudEditorScreen(null));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("clientid")
                                .then(ClientCommandManager.argument("id", StringArgumentType.word()).executes(c -> {
                                    TwConfig.get().clientId = StringArgumentType.getString(c, "id").strip();
                                    TwConfig.save();
                                    chat("Client ID saved. Run /tunewire login to connect.", false);
                                    return 1;
                                })))));

        ClientTickEvents.END_CLIENT_TICK.register(TunewireClient::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> SpotifyAuth.cancelLogin());
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "hud"), TunewireHud::render);
        WorldRenderEvents.AFTER_ENTITIES.register(WorldLyrics::render);

        LOG.info("Tunewire loaded");
        System.out.println("[Tunewire] loaded: /tunewire, hotkeys under Options > Controls > Tunewire"); // Lunar only logs stdout
    }

    private static KeyMapping key(String name, int def) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping("key.tunewire." + name, InputConstants.Type.KEYSYM, def, CATEGORY));
    }

    private static void tick(Minecraft mc) {
        long t0 = Perf.start();
        try {
            tickInner(mc);
        } finally {
            Perf.end(Perf.Section.TICK, t0);
        }
    }

    private static void tickInner(Minecraft mc) {
        if (pendingScreen != null && mc.screen == null) {
            // opened a tick later so the chat screen that ran the command has closed
            Screen s = pendingScreen;
            pendingScreen = null;
            mc.setScreen(s);
        } else if (pendingScreen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
            pendingScreen = null;
        }

        while (openKey.consumeClick()) openScreen(new PlayerScreen());
        SpotifyPlayer sp = SpotifyPlayer.INSTANCE;
        while (playKey.consumeClick()) sp.togglePlay();
        while (nextKey.consumeClick()) sp.next();
        while (prevKey.consumeClick()) sp.previous();
        while (volUpKey.consumeClick()) sp.changeVolume(TwConfig.get().volumeStep);
        while (volDownKey.consumeClick()) sp.changeVolume(-TwConfig.get().volumeStep);
        while (hudKey.consumeClick()) {
            TwConfig c = TwConfig.get();
            boolean on = !(c.nowPlayingHud || c.lyricsHud);
            c.nowPlayingHud = on;
            c.lyricsHud = on;
            TwConfig.save();
            actionBar(on ? "Tunewire HUD shown" : "Tunewire HUD hidden");
        }

        sp.tick(mc);
    }

    public static void openScreen(Screen s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) mc.setScreen(s);
        else pendingScreen = s;
    }

    /** Chat line with a [Tunewire] prefix. Safe from any thread. */
    public static void chat(String msg, boolean error) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) {
                LOG.info(msg);
                return;
            }
            mc.gui.getChat().addMessage(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("Tunewire").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(msg).withStyle(error ? ChatFormatting.RED : ChatFormatting.GRAY)));
        });
    }

    /** Short message above the hotbar. Safe from any thread. */
    public static void actionBar(String msg) {
        if (msg == null || msg.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.setOverlayMessage(Component.literal("♫ " + msg), false);
        });
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }
}
