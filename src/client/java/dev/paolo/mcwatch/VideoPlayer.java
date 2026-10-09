package dev.paolo.mcwatch;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Wraps one libmpv instance. mpv does the hard parts (yt-dlp, decoding, audio, A/V sync);
 * a background thread renders each new video frame into RAM with mpv's software renderer,
 * and the game thread copies the latest frame into a texture via {@link #withFrame}.
 */
final class VideoPlayer {
	interface FrameSink {
		void accept(long address, int width, int height);
	}

	private static final class Frame {
		Memory mem;
		int w, h;
	}

	private final Mpv lib = Mpv.load();
	private final Consumer<String> messages;
	private Pointer mpv;
	private Pointer renderCtx;
	// Kept as a field so JNA's callback isn't garbage collected while mpv still holds it.
	private final Mpv.UpdateCallback onUpdate = data -> wakeRenderThread();

	private final Object renderSignal = new Object();
	private boolean renderPending;
	private volatile boolean running = true;
	private Thread renderThread, eventThread;

	private final Object frameLock = new Object();
	private Frame back = new Frame();
	private Frame front = new Frame();
	private boolean frameReady;

	private volatile int targetW, targetH;

	volatile String title = "";
	volatile double position, duration;
	volatile boolean paused, loaded, loading;

	VideoPlayer(Consumer<String> messages, int volume) {
		this.messages = messages;

		// Launched from the Minecraft launcher, PATH is minimal; yt-dlp needs deno for YouTube.
		Mpv.LibC.INSTANCE.setenv("PATH", "/opt/homebrew/bin:/usr/local/bin:" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"), 1);
		Mpv.LibC.INSTANCE.setlocale(Mpv.LibC.LC_NUMERIC, "C");

		mpv = lib.mpv_create();
		if (mpv == null) throw new IllegalStateException("mpv_create failed");
		opt("config", "no");
		opt("terminal", "no");
		opt("vo", "libmpv");
		opt("osc", "no");
		opt("input-default-bindings", "no");
		opt("input-vo-keyboard", "no");
		opt("keep-open", "yes");
		opt("hwdec", "auto-copy");
		opt("ytdl", "yes");
		opt("ytdl-format", "bv*[height<=720]+ba/b[height<=720]/b");
		for (String p : new String[]{"/opt/homebrew/bin/yt-dlp", "/usr/local/bin/yt-dlp"}) {
			if (Files.exists(Path.of(p))) {
				opt("script-opts", "ytdl_hook-ytdl_path=" + p);
				break;
			}
		}
		opt("audio-client-name", "mcwatch");
		opt("volume", Integer.toString(volume));
		check(lib.mpv_initialize(mpv), "mpv_initialize");

		Memory apiType = cString("sw");
		Memory params = new Memory(16 * 2);
		params.clear();
		params.setInt(0, Mpv.PARAM_API_TYPE);
		params.setPointer(8, apiType);
		PointerByReference ref = new PointerByReference();
		check(lib.mpv_render_context_create(ref, mpv, params), "mpv_render_context_create");
		renderCtx = ref.getValue();
		lib.mpv_render_context_set_update_callback(renderCtx, onUpdate, null);

		renderThread = thread("mcwatch-render", this::renderLoop);
		eventThread = thread("mcwatch-events", this::eventLoop);
	}

	void play(String url) {
		loaded = false;
		loading = true;
		title = url;
		command("loadfile", url, "replace");
		setProperty("pause", "no");
	}

	void togglePause() {
		command("cycle", "pause");
	}

	void seek(double seconds) {
		command("seek", Double.toString(seconds), "relative");
	}

	void setVolume(int volume) {
		setProperty("volume", Integer.toString(volume));
	}

	void stop() {
		command("stop");
		loaded = false;
		loading = false;
		title = "";
		synchronized (frameLock) {
			frameReady = false;
		}
	}

	boolean active() {
		return loaded || loading;
	}

	/** Size (in real pixels) mpv should render frames at; it letterboxes to keep the aspect ratio. */
	void setTargetSize(int w, int h) {
		if (w != targetW || h != targetH) {
			targetW = w;
			targetH = h;
			wakeRenderThread();
		}
	}

	/** Hands the newest frame (RGBA, tightly packed) to the sink, if there is a new one. */
	void withFrame(FrameSink sink) {
		synchronized (frameLock) {
			if (!frameReady) return;
			frameReady = false;
			sink.accept(Pointer.nativeValue(front.mem), front.w, front.h);
		}
	}

	void destroy() {
		running = false;
		// Both threads call into mpv, so they must be gone before mpv is freed.
		wakeRenderThread();
		if (mpv != null) lib.mpv_wakeup(mpv);
		try {
			renderThread.join(2000);
			eventThread.join(2000);
		} catch (InterruptedException ignored) {
		}
		if (renderCtx != null) {
			lib.mpv_render_context_free(renderCtx);
			renderCtx = null;
		}
		if (mpv != null) {
			lib.mpv_terminate_destroy(mpv);
			mpv = null;
		}
	}

	// --- threads ---

	private void wakeRenderThread() {
		synchronized (renderSignal) {
			renderPending = true;
			renderSignal.notifyAll();
		}
	}

	private void renderLoop() {
		Memory size = new Memory(8);
		Memory stride = new Memory(8);
		Memory format = cString("rgb0");
		Memory params = new Memory(16 * 5);
		int lastW = -1, lastH = -1;

		while (running) {
			synchronized (renderSignal) {
				while (!renderPending && running) {
					try {
						renderSignal.wait(250);
					} catch (InterruptedException e) {
						return;
					}
				}
				renderPending = false;
			}
			if (!running || renderCtx == null) return;

			long flags = lib.mpv_render_context_update(renderCtx);
			int w = targetW, h = targetH;
			boolean resized = w != lastW || h != lastH;
			if (((flags & Mpv.UPDATE_FRAME) == 0 && !resized) || w <= 0 || h <= 0 || !active()) continue;
			lastW = w;
			lastH = h;

			if (back.mem == null || back.w != w || back.h != h) {
				back.mem = new Memory((long) w * h * 4);
				back.w = w;
				back.h = h;
			}
			size.setInt(0, w);
			size.setInt(4, h);
			stride.setLong(0, (long) w * 4);
			params.clear();
			params.setInt(0, Mpv.PARAM_SW_SIZE);
			params.setPointer(8, size);
			params.setInt(16, Mpv.PARAM_SW_FORMAT);
			params.setPointer(24, format);
			params.setInt(32, Mpv.PARAM_SW_STRIDE);
			params.setPointer(40, stride);
			params.setInt(48, Mpv.PARAM_SW_POINTER);
			params.setPointer(56, back.mem);
			if (lib.mpv_render_context_render(renderCtx, params) < 0) continue;

			// "rgb0" leaves the 4th byte undefined; make every pixel fully opaque.
			IntBuffer px = back.mem.getByteBuffer(0, back.mem.size()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
			for (int i = 0, n = px.limit(); i < n; i++) px.put(i, px.get(i) | 0xFF000000);

			synchronized (frameLock) {
				Frame t = front;
				front = back;
				back = t;
				frameReady = true;
			}
		}
	}

	private void eventLoop() {
		while (running && mpv != null) {
			Pointer ev = lib.mpv_wait_event(mpv, 0.25);
			int id = ev.getInt(0);
			if (id == Mpv.EVENT_SHUTDOWN) return;
			if (id == Mpv.EVENT_FILE_LOADED) {
				loaded = true;
				loading = false;
				String t = getProperty("media-title");
				if (t != null) title = t;
			} else if (id == Mpv.EVENT_END_FILE) {
				Pointer data = ev.getPointer(16);
				if (data != null && data.getInt(0) == Mpv.END_FILE_REASON_ERROR) {
					loading = false;
					loaded = false;
					messages.accept("Couldn't play that link (" + lib.mpv_error_string(data.getInt(4)) + ")");
				}
			}
			position = parse(getProperty("time-pos"));
			duration = parse(getProperty("duration"));
			paused = "yes".equals(getProperty("pause"));
		}
	}

	// --- helpers ---

	private void command(String... args) {
		if (mpv != null) lib.mpv_command(mpv, args);
	}

	private void setProperty(String name, String value) {
		if (mpv != null) lib.mpv_set_property_string(mpv, name, value);
	}

	private String getProperty(String name) {
		if (mpv == null) return null;
		Pointer p = lib.mpv_get_property_string(mpv, name);
		if (p == null) return null;
		try {
			return p.getString(0, "UTF-8");
		} finally {
			lib.mpv_free(p);
		}
	}

	private void opt(String name, String value) {
		int err = lib.mpv_set_option_string(mpv, name, value);
		if (err < 0) McWatchClient.LOG.warn("mpv option {}={} failed: {}", name, value, lib.mpv_error_string(err));
	}

	private void check(int err, String what) {
		if (err < 0) throw new IllegalStateException(what + ": " + lib.mpv_error_string(err));
	}

	private static Memory cString(String s) {
		byte[] b = Native.toByteArray(s, "UTF-8");
		Memory m = new Memory(b.length);
		m.write(0, b, 0, b.length);
		return m;
	}

	private static double parse(String s) {
		try {
			return s == null ? 0 : Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static Thread thread(String name, Runnable r) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		t.start();
		return t;
	}
}
