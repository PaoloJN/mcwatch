package dev.paolo.mcwatch;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal JNA bindings for libmpv (client + software render API). */
interface Mpv extends Library {
	String[] LIB_PATHS = {"/opt/homebrew/lib/libmpv.dylib", "/usr/local/lib/libmpv.dylib"};

	static Mpv load() {
		for (String p : LIB_PATHS) {
			if (Files.exists(Path.of(p))) return Native.load(p, Mpv.class);
		}
		throw new IllegalStateException("libmpv not found — install it with: brew install mpv");
	}

	// mpv_render_param types
	int PARAM_INVALID = 0;
	int PARAM_API_TYPE = 1;
	int PARAM_SW_SIZE = 17;
	int PARAM_SW_FORMAT = 18;
	int PARAM_SW_STRIDE = 19;
	int PARAM_SW_POINTER = 20;
	long UPDATE_FRAME = 1;

	int EVENT_NONE = 0;
	int EVENT_SHUTDOWN = 1;
	int EVENT_END_FILE = 7;
	int EVENT_FILE_LOADED = 8;
	int END_FILE_REASON_ERROR = 4;

	Pointer mpv_create();
	int mpv_initialize(Pointer ctx);
	void mpv_terminate_destroy(Pointer ctx);
	int mpv_set_option_string(Pointer ctx, String name, String data);
	int mpv_set_property_string(Pointer ctx, String name, String data);
	Pointer mpv_get_property_string(Pointer ctx, String name);
	void mpv_free(Pointer data);
	/** args is converted by JNA to a NULL-terminated char**. */
	int mpv_command(Pointer ctx, String[] args);
	Pointer mpv_wait_event(Pointer ctx, double timeout);
	void mpv_wakeup(Pointer ctx);
	String mpv_error_string(int error);

	int mpv_render_context_create(PointerByReference res, Pointer mpv, Pointer params);
	void mpv_render_context_set_update_callback(Pointer ctx, UpdateCallback cb, Pointer data);
	long mpv_render_context_update(Pointer ctx);
	int mpv_render_context_render(Pointer ctx, Pointer params);
	void mpv_render_context_free(Pointer ctx);

	interface UpdateCallback extends Callback {
		void invoke(Pointer data);
	}

	/** libmpv refuses to start unless LC_NUMERIC is "C"; also lets yt-dlp find deno/ffmpeg. */
	interface LibC extends Library {
		LibC INSTANCE = Native.load("c", LibC.class);
		int LC_NUMERIC = 4; // macOS value
		Pointer setlocale(int category, String locale);
		int setenv(String name, String value, int overwrite);
	}
}
