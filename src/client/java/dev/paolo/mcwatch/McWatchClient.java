package dev.paolo.mcwatch;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class McWatchClient implements ClientModInitializer {
	public static final String MOD_ID = "mcwatch";
	static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
	private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(MOD_ID, "video");

	private static final String[] SIZES = {"small", "medium", "large", "huge"};
	private static final double[] SIZE_FRACTION = {0.25, 0.35, 0.5, 0.7};
	private static final String[] CORNERS = {"top-right", "top-left", "bottom-right", "bottom-left"};
	private static final double MIN_SIZE = 0.12, MAX_SIZE = 0.95;

	private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("mcwatch.properties");
	/** Width as a fraction of the screen. */
	private double size = 0.35;
	/** Position as a fraction of the free space: 0,0 = top-left corner, 1,1 = bottom-right. */
	private double posX = 1, posY = 0;
	private int volume = 70;
	private boolean hidden;
	private boolean moveMode;
	private boolean configDirty;

	private VideoPlayer player;
	private DynamicTexture texture;
	private int texW, texH;
	private long showInfoUntil;

	private KeyMapping pauseKey, backKey, forwardKey, toggleKey, moveKey, biggerKey, smallerKey, volUpKey, volDownKey;
	private String toast;

	@Override
	public void onInitializeClient() {
		loadConfig();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
		pauseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.pause", GLFW.GLFW_KEY_UP, category));
		backKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.back", GLFW.GLFW_KEY_LEFT, category));
		forwardKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.forward", GLFW.GLFW_KEY_RIGHT, category));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.toggle", GLFW.GLFW_KEY_DOWN, category));
		moveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.move", GLFW.GLFW_KEY_SEMICOLON, category));
		biggerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.bigger", GLFW.GLFW_KEY_EQUAL, category));
		smallerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.smaller", GLFW.GLFW_KEY_MINUS, category));
		volUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.volume_up", GLFW.GLFW_KEY_PERIOD, category));
		volDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcwatch.volume_down", GLFW.GLFW_KEY_COMMA, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> handleKeys());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("watch")
				.executes(c -> play(c, Minecraft.getInstance().keyboardHandler.getClipboard()))
				.then(literal("pause").executes(c -> run(c, () -> pauseToggled(player().togglePause()), null)))
				.then(literal("stop").executes(c -> run(c, () -> {
					if (player != null) player.stop();
				}, "Stopped")))
				.then(literal("hide").executes(c -> run(c, () -> hidden = !hidden, null)))
				.then(literal("seek").then(argument("seconds", IntegerArgumentType.integer())
						.executes(c -> run(c, () -> player().seek(IntegerArgumentType.getInteger(c, "seconds")), null))))
				.then(literal("volume").then(argument("percent", IntegerArgumentType.integer(0, 130))
						.executes(c -> {
							volume = IntegerArgumentType.getInteger(c, "percent");
							if (player != null) player.setVolume(volume);
							saveConfig();
							return say(c, "Volume " + volume + "%");
						})))
				.then(literal("size").then(argument("size", StringArgumentType.word())
						.suggests((c, b) -> suggest(b, SIZES))
						.executes(c -> {
							String s = StringArgumentType.getString(c, "size");
							if (indexOf(SIZES, s) < 0) return say(c, "Sizes: small, medium, large, huge");
							size = SIZE_FRACTION[indexOf(SIZES, s)];
							saveConfig();
							return say(c, "Size " + s);
						})))
				.then(literal("corner").then(argument("corner", StringArgumentType.word())
						.suggests((c, b) -> suggest(b, CORNERS))
						.executes(c -> {
							String s = StringArgumentType.getString(c, "corner");
							if (indexOf(CORNERS, s) < 0) return say(c, "Corners: top-right, top-left, bottom-right, bottom-left");
							posX = s.endsWith("left") ? 0 : 1;
							posY = s.startsWith("top") ? 0 : 1;
							saveConfig();
							return say(c, "Moved to " + s);
						})))
				.then(argument("link", StringArgumentType.greedyString())
						.executes(c -> play(c, StringArgumentType.getString(c, "link"))))));

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "video"), (graphics, delta) -> drawHud(graphics));
		// Leaving the world (or server) stops the video.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
			if (player != null) player.stop();
			moveMode = false;
			if (configDirty) saveConfig();
		}));
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			if (player != null) player.destroy();
		});
	}

	// --- commands & keys ---

	private int play(CommandContext<FabricClientCommandSource> c, String link) {
		link = link == null ? "" : link.trim();
		if (!link.startsWith("http://") && !link.startsWith("https://")) {
			return say(c, "Paste a link: /watch <link>  (or copy one and type just /watch)");
		}
		try {
			player().play(link);
		} catch (Throwable t) {
			LOG.error("mcwatch failed to start", t);
			return say(c, "Couldn't start the video player: " + t.getMessage());
		}
		hidden = false;
		showInfoUntil = System.currentTimeMillis() + 4000;
		return say(c, "Loading video... ↑ pause · ← → skip 5s (shift: 30s) · ↓ hide · - = size · , . volume · ; move");
	}

	private int run(CommandContext<FabricClientCommandSource> c, Runnable action, String feedback) {
		try {
			action.run();
		} catch (Throwable t) {
			return say(c, "Error: " + t.getMessage());
		}
		showInfoUntil = System.currentTimeMillis() + 3000;
		return feedback == null ? 1 : say(c, feedback);
	}

	private void handleKeys() {
		if (player == null || !player.active()) {
			moveMode = false;
			return;
		}
		boolean shift = InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
				|| InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);

		while (moveKey.consumeClick()) {
			moveMode = !moveMode;
			hidden = false;
			if (!moveMode) saveConfig();
			flash(moveMode ? "Move mode: arrows move · - = resize · ; done" : "Saved position");
		}

		if (moveMode) {
			// Held arrows glide the window; consume clicks so they don't also pause/seek afterwards.
			double step = shift ? 0.04 : 0.015;
			if (backKey.isDown()) posX -= step;
			if (forwardKey.isDown()) posX += step;
			if (pauseKey.isDown()) posY -= step;
			if (toggleKey.isDown()) posY += step;
			posX = Math.clamp(posX, 0, 1);
			posY = Math.clamp(posY, 0, 1);
			drain(backKey, forwardKey, pauseKey, toggleKey);
		} else {
			int seek = shift ? 30 : 5;
			while (pauseKey.consumeClick()) pauseToggled(player.togglePause());
			while (backKey.consumeClick()) {
				player.seek(-seek);
				flash("-" + seek + "s");
			}
			while (forwardKey.consumeClick()) {
				player.seek(seek);
				flash("+" + seek + "s");
			}
			while (toggleKey.consumeClick()) {
				hidden = !hidden;
			}
		}

		while (biggerKey.consumeClick()) resize(1.12);
		while (smallerKey.consumeClick()) resize(1 / 1.12);
		while (volUpKey.consumeClick()) changeVolume(5);
		while (volDownKey.consumeClick()) changeVolume(-5);

		if (configDirty && !moveMode) saveConfig();
	}

	private void pauseToggled(boolean nowPaused) {
		hidden = false;
		flash(nowPaused ? "⏸ Paused" : "▶ Playing");
	}

	private void resize(double factor) {
		size = Math.clamp(size * factor, MIN_SIZE, MAX_SIZE);
		hidden = false;
		configDirty = true;
		flash("Size " + Math.round(size * 100) + "%");
	}

	private void changeVolume(int delta) {
		volume = Math.clamp(volume + delta, 0, 130);
		player.setVolume(volume);
		configDirty = true;
		flash("Volume " + volume + "%");
	}

	private void flash(String message) {
		toast = message;
		showInfoUntil = System.currentTimeMillis() + 2500;
	}

	private static void drain(KeyMapping... keys) {
		for (KeyMapping k : keys) while (k.consumeClick()) ;
	}

	private VideoPlayer player() {
		if (player == null) {
			player = new VideoPlayer(msg -> Minecraft.getInstance().execute(() -> {
				var p = Minecraft.getInstance().player;
				if (p != null) p.sendSystemMessage(Component.literal("[mcwatch] " + msg));
			}), volume);
		}
		return player;
	}

	// --- HUD ---

	private void drawHud(GuiGraphicsExtractor g) {
		if (player == null || !player.active() || hidden) return;
		Minecraft mc = Minecraft.getInstance();
		var window = mc.getWindow();
		int screenW = window.getGuiScaledWidth();
		int screenH = window.getGuiScaledHeight();
		double scale = (double) window.getWidth() / screenW;

		int margin = 4;
		int w = Math.min((int) (screenW * size), screenW - 2 * margin);
		int h = Math.min(w * 9 / 16, screenH - 2 * margin);
		w = Math.min(w, h * 16 / 9);
		int x = margin + (int) Math.round((screenW - w - 2 * margin) * posX);
		int y = margin + (int) Math.round((screenH - h - 2 * margin) * posY);

		player.setTargetSize((int) Math.round(w * scale), (int) Math.round(h * scale));
		player.withFrame(this::upload);

		int border = moveMode ? 0xFFFFD000 : 0xFF000000;
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
		if (texture != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, x, y, 0, 0, w, h, texW, texH, texW, texH);
		}

		// thin progress bar along the bottom edge
		if (player.duration > 0) {
			int filled = (int) (w * Math.min(1, player.displayPosition() / player.duration));
			g.fill(x, y + h - 2, x + w, y + h, 0x80FFFFFF);
			g.fill(x, y + h - 2, x + filled, y + h, 0xFFFF3333);
		}

		boolean info = moveMode || player.loading || player.paused || System.currentTimeMillis() < showInfoUntil;
		if (info) {
			String status = player.loading ? "Loading…"
					: (player.paused ? "⏸ " : player.buffering ? "⏳ " : "▶ ") + time(player.displayPosition()) + " / " + time(player.duration);
			String title = mc.font.plainSubstrByWidth(player.title, w - 8 - mc.font.width(status));
			g.fill(x, y, x + w, y + 12, 0xA0000000);
			g.text(mc.font, title, x + 3, y + 2, 0xFFFFFFFF, false);
			g.text(mc.font, status, x + w - 3 - mc.font.width(status), y + 2, 0xFFFFFFFF, false);
			String banner = moveMode ? "Move: arrows (shift = faster) · - = size · ; done"
					: System.currentTimeMillis() < showInfoUntil ? toast : null;
			if (banner != null) {
				String b = mc.font.plainSubstrByWidth(banner, w - 6);
				int bw = mc.font.width(b);
				int by = y + h / 2 - 6;
				g.fill(x + (w - bw) / 2 - 4, by - 2, x + (w + bw) / 2 + 4, by + 11, 0xC0000000);
				g.text(mc.font, b, x + (w - bw) / 2, by, moveMode ? 0xFFFFD000 : 0xFFFFFFFF, false);
			}
		}
	}

	private void upload(long address, int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		if (texture == null || texW != w || texH != h) {
			if (texture != null) mc.getTextureManager().release(TEXTURE_ID);
			texture = new DynamicTexture(() -> "mcwatch video", new NativeImage(w, h, false));
			mc.getTextureManager().register(TEXTURE_ID, texture);
			texW = w;
			texH = h;
		}
		MemoryUtil.memCopy(address, texture.getPixels().getPointer(), (long) w * h * 4);
		texture.upload();
	}

	// --- config & helpers ---

	private void loadConfig() {
		if (!Files.exists(configFile)) return;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(configFile)) {
			p.load(r);
			size = Math.clamp(Double.parseDouble(p.getProperty("size", Double.toString(size))), MIN_SIZE, MAX_SIZE);
			posX = Math.clamp(Double.parseDouble(p.getProperty("x", Double.toString(posX))), 0, 1);
			posY = Math.clamp(Double.parseDouble(p.getProperty("y", Double.toString(posY))), 0, 1);
			volume = Integer.parseInt(p.getProperty("volume", Integer.toString(volume)));
		} catch (IOException | NumberFormatException e) {
			LOG.warn("Couldn't read {}", configFile, e);
		}
	}

	private void saveConfig() {
		Properties p = new Properties();
		configDirty = false;
		p.setProperty("size", Double.toString(size));
		p.setProperty("x", Double.toString(posX));
		p.setProperty("y", Double.toString(posY));
		p.setProperty("volume", Integer.toString(volume));
		try (Writer w = Files.newBufferedWriter(configFile)) {
			p.store(w, "mcwatch settings (also changeable in-game with /watch size|corner|volume)");
		} catch (IOException e) {
			LOG.warn("Couldn't save {}", configFile, e);
		}
	}

	private static int say(CommandContext<FabricClientCommandSource> c, String msg) {
		c.getSource().sendFeedback(Component.literal("[mcwatch] " + msg));
		return 1;
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
			com.mojang.brigadier.suggestion.SuggestionsBuilder b, String[] options) {
		for (String o : options) if (o.startsWith(b.getRemainingLowerCase())) b.suggest(o);
		return b.buildFuture();
	}

	private static int indexOf(String[] arr, String s) {
		for (int i = 0; i < arr.length; i++) if (arr[i].equals(s)) return i;
		return -1;
	}

	private static String time(double seconds) {
		int s = (int) seconds;
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}
}
