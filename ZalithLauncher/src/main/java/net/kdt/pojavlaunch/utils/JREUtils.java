package net.kdt.pojavlaunch.utils;

import static com.movtery.zalithlauncher.utils.path.PathManager.DIR_NATIVE_LIB;
import static net.kdt.pojavlaunch.Architecture.ARCH_X86;
import static net.kdt.pojavlaunch.Architecture.is64BitsDevice;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.event.value.JvmExitEvent;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.feature.version.VersionInfo;
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager;
import com.movtery.zalithlauncher.plugins.renderer.RendererPlugin;
import com.movtery.zalithlauncher.plugins.renderer.RendererPluginManager;
import com.movtery.zalithlauncher.renderer.RendererInterface;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.ui.activity.ErrorActivity;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.path.LibPath;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.oracle.dalvik.VMLauncher;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.plugins.FFmpegPlugin;

import org.greenrobot.eventbus.EventBus;
import org.lwjgl.glfw.CallbackBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public final class JREUtils {
    private static final boolean FORCE_PURE_HYBRID_TEST_MODE = false;

    private static final int EGL_OPENGL_ES_BIT = 0x0001;
    private static final int EGL_OPENGL_ES2_BIT = 0x0004;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;

    public static String LD_LIBRARY_PATH;
    public static String jvmLibraryPath;

    private JREUtils() {
    }

    public static String findInLdLibPath(String libName) {
        if (Os.getenv("LD_LIBRARY_PATH") == null) {
            try {
                if (LD_LIBRARY_PATH != null) {
                    Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
                }
            } catch (ErrnoException e) {
                Logging.e("JREUtils", Tools.printToString(e));
            }
            return libName;
        }

        for (String libPath : Os.getenv("LD_LIBRARY_PATH").split(":")) {
            File f = new File(libPath, libName);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return libName;
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> libs = new ArrayList<>();
        File[] list = path.listFiles();
        if (list == null) {
            return libs;
        }

        for (File f : list) {
            if (f.isFile() && f.getName().endsWith(".so")) {
                libs.add(f);
            } else if (f.isDirectory()) {
                libs.addAll(locateLibs(f));
            }
        }
        return libs;
    }

    public static void initJavaRuntime(String jreHome) {
        loadOptionalBootstrapLibraries();
        loadRequiredJvmLibraries();
        loadRemainingRuntimeLibraries(jreHome);
    }

    private static void loadOptionalBootstrapLibraries() {
        dlopen(DIR_NATIVE_LIB + "/libSDL3.so");
        dlopen(DIR_NATIVE_LIB + "/libSDL2.so");
        dlopen(DIR_NATIVE_LIB + "/libspirv-cross.so");
        dlopen(DIR_NATIVE_LIB + "/libshaderc.so");
        dlopen(DIR_NATIVE_LIB + "/liblwjgl_vma.so");
        dlopen("libzstd-jni_dh-1.5.7-6.so");
    }

    private static void loadRequiredJvmLibraries() {
        dlopen(findInLdLibPath("libjli.so"));

        if (!dlopen("libjvm.so")) {
            Logging.w("DynamicLoader", "Failed to load libjvm.so by name, trying full path");
            dlopen(jvmLibraryPath + "/libjvm.so");
        }

        dlopen(findInLdLibPath("libverify.so"));
        dlopen(findInLdLibPath("libjava.so"));
        dlopen(findInLdLibPath("libnet.so"));
        dlopen(findInLdLibPath("libnio.so"));
        dlopen(findInLdLibPath("libawt.so"));
        dlopen(findInLdLibPath("libawt_headless.so"));
        dlopen(findInLdLibPath("libfreetype.so"));
        dlopen(findInLdLibPath("libfontmanager.so"));
    }

    private static void loadRemainingRuntimeLibraries(String jreHome) {
        for (File f : locateLibs(new File(jreHome, Tools.DIRNAME_HOME_JRE))) {
            dlopen(f.getAbsolutePath());
        }
    }

    public static void redirectAndPrintJRELog() {
        Logging.v("jrelog", "Log starts here");

        Thread thread = new Thread(() -> {
            ProcessBuilder logcatPb = new ProcessBuilder()
                    .command("logcat", "-v", "brief", "-s", "jrelog:I", "LIBGL:I", "NativeInput")
                    .redirectErrorStream(true);

            int failCount = 0;

            while (failCount <= 10) {
                Process process = null;
                InputStream inputStream = null;

                try {
                    Logging.i("jrelog-logcat", "Clearing logcat");
                    new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start();

                    Logging.i("jrelog-logcat", "Starting logcat");
                    process = logcatPb.start();
                    inputStream = process.getInputStream();

                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = inputStream.read(buf)) != -1) {
                        Logger.appendToLog(new String(buf, 0, len));
                    }

                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        break;
                    }

                    failCount++;
                    Logging.e("jrelog-logcat", "Logcat exited with code " + exitCode);
                    Logging.i("jrelog-logcat", "Restarting logcat (attempt " + failCount + "/10)");
                } catch (Throwable e) {
                    failCount++;
                    Logging.e("jrelog-logcat", "Exception on logging thread", e);
                    Logger.appendToLog("Exception on logging thread:\n" + Log.getStackTraceString(e));
                } finally {
                    try {
                        if (inputStream != null) {
                            inputStream.close();
                        }
                    } catch (Throwable ignored) {
                    }

                    if (process != null) {
                        process.destroy();
                    }
                }
            }

            if (failCount > 10) {
                Logger.appendToLog("ERROR: Unable to get more Logging.");
            }
        }, "JRELogcatReader");

        thread.start();
        Logging.i("jrelog-logcat", "Logcat thread started");
    }

    public static void relocateLibPath(Runtime runtime, String jreHome) {
        String jreArchitecture = runtime.arch;
        if (Architecture.archAsInt(jreArchitecture) == ARCH_X86) {
            jreArchitecture = "i386/i486/i586";
        }

        for (String arch : jreArchitecture.split("/")) {
            File f = new File(jreHome, "lib/" + arch);
            if (f.exists() && f.isDirectory()) {
                Tools.DIRNAME_HOME_JRE = "lib/" + arch;
            }
        }

        String libName = is64BitsDevice() ? "lib64" : "lib";
        StringBuilder ldLibraryPath = new StringBuilder();

        if (FFmpegPlugin.isAvailable) {
            ldLibraryPath.append(FFmpegPlugin.libraryPath).append(":");
        }

        RendererPlugin customRenderer = RendererPluginManager.getSelectedRendererPlugin();
        if (customRenderer != null) {
            ldLibraryPath.append(customRenderer.getPath()).append(":");
        }

        ldLibraryPath.append(jreHome)
                .append("/").append(Tools.DIRNAME_HOME_JRE)
                .append("/jli:")
                .append(jreHome).append("/").append(Tools.DIRNAME_HOME_JRE)
                .append(":")
                .append("/system/").append(libName).append(":")
                .append("/vendor/").append(libName).append(":")
                .append("/vendor/").append(libName).append("/hw:");

        File runtimeModDir = PathManager.DIR_RUNTIME_MOD;
        if (runtimeModDir != null) {
            ldLibraryPath.append(runtimeModDir.getAbsolutePath()).append(":");
        }

        ldLibraryPath.append(DIR_NATIVE_LIB);
        LD_LIBRARY_PATH = ldLibraryPath.toString();
    }

    private static void initLdLibraryPath(String jreHome) {
        File serverFile = new File(jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/server/libjvm.so");
        jvmLibraryPath = jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/" + (serverFile.exists() ? "server" : "client");

        Logging.d("DynamicLoader", "Base LD_LIBRARY_PATH: " + LD_LIBRARY_PATH);
        Logging.d("DynamicLoader", "Internal LD_LIBRARY_PATH: " + jvmLibraryPath + ":" + LD_LIBRARY_PATH);

        setLdLibraryPath(jvmLibraryPath + ":" + LD_LIBRARY_PATH);
    }

    private static void setJavaEnv(Map<String, String> envMap, String jreHome) {
        envMap.put("ALSOFT_LOGLEVEL", "0"); // silence OpenAL Soft log stalls
        envMap.put("POJAV_NATIVEDIR", DIR_NATIVE_LIB);
        envMap.put("DRIVER_PATH", DriverPluginManager.getDriver().getPath());
        envMap.put("JAVA_HOME", jreHome);
        envMap.put("HOME", PathManager.DIR_GAME_HOME);
        envMap.put("TMPDIR", PathManager.DIR_CACHE.getAbsolutePath());
        envMap.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        envMap.put("PATH", jreHome + "/bin:" + Os.getenv("PATH"));
        envMap.put("FORCE_VSYNC", String.valueOf(AllSettings.getForceVsync().getValue()));
        envMap.put("AWTSTUB_WIDTH", Integer.toString(
                CallbackBridge.windowWidth > 0 ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth
        ));
        envMap.put("AWTSTUB_HEIGHT", Integer.toString(
                CallbackBridge.windowHeight > 0 ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight
        ));
        envMap.put("MOD_ANDROID_RUNTIME",
                PathManager.DIR_RUNTIME_MOD != null ? PathManager.DIR_RUNTIME_MOD.getAbsolutePath() : "");

        if (AllSettings.getDumpShaders().getValue()) {
            envMap.put("LIBGL_VGPU_DUMP", "1");
        }
        if (AllSettings.getZinkPreferSystemDriver().getValue()) {
            envMap.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "1");
        }
        if (AllSettings.getVsyncInZink().getValue()) {
            envMap.put("POJAV_VSYNC_IN_ZINK", "1");
        }
        if (AllSettings.getBigCoreAffinity().getValue()) {
            envMap.put("POJAV_BIG_CORE_AFFINITY", "1");
        }
        if (FFmpegPlugin.isAvailable) {
            envMap.put("POJAV_FFMPEG_PATH", FFmpegPlugin.executablePath);
        }
    }

    private static void setRendererEnv(Map<String, String> envMap) {
        RendererInterface currentRenderer = Renderers.INSTANCE.getCurrentRenderer();
        String rendererId = currentRenderer.getRendererId();

        if (rendererId.startsWith("opengles2")) {
            envMap.put("LIBGL_ES", "2");
            envMap.put("LIBGL_MIPMAP", "3");
            envMap.put("LIBGL_NOERROR", "1");
            envMap.put("LIBGL_NOINTOVLHACK", "1");
            envMap.put("LIBGL_NORMALIZE", "1");
        }

        envMap.putAll(currentRenderer.getRendererEnv().getValue());

        String eglName = currentRenderer.getRendererEGL();
        if (eglName != null) {
            envMap.put("POJAVEXEC_EGL", eglName);
        }

        envMap.put("POJAV_RENDERER", rendererId);

        if (RendererPluginManager.getSelectedRendererPlugin() != null) {
            return;
        }

        if (!rendererId.startsWith("opengles")) {
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            envMap.put("MESA_GLSL_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
            envMap.put("force_glsl_extensions_warn", "true");
            envMap.put("allow_higher_compat_version", "true");
            envMap.put("allow_glsl_extension_directive_midshader", "true");
            envMap.put("LIB_MESA_NAME", loadGraphicsLibrary());
        }

        if (!envMap.containsKey("LIBGL_ES")) {
            int glesMajor = getDetectedVersion();
            Logging.i("glesDetect", "GLES version detected: " + glesMajor);

            if (glesMajor < 3) {
                envMap.put("LIBGL_ES", "2");
            } else if (rendererId.startsWith("opengles")) {
                envMap.put("LIBGL_ES", rendererId.replace("opengles", "").replace("_5", ""));
            } else {
                envMap.put("LIBGL_ES", "3");
            }
        }
    }

    private static void setCustomEnv(Map<String, String> envMap) throws Throwable {
        File customEnvFile = new File(PathManager.DIR_GAME_HOME, "custom_env.txt");
        if (!customEnvFile.exists() || !customEnvFile.isFile()) {
            return;
        }

        BufferedReader reader = new BufferedReader(new FileReader(customEnvFile));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf('=');
                if (index > 0) {
                    envMap.put(line.substring(0, index), line.substring(index + 1));
                }
            }
        } finally {
            reader.close();
        }
    }

    private static void configureJsphEnvIfNeeded(Map<String, String> envMap, Runtime runtime) {
        if (runtime.javaVersion <= 11) {
            return;
        }

        File dir = new File(DIR_NATIVE_LIB);
        if (!dir.isDirectory()) {
            return;
        }

        String jsphName = runtime.javaVersion == 17 ? "libjsph17" : "libjsph21";
        File[] files = dir.listFiles((dir1, name) -> name.startsWith(jsphName));
        if (files != null && files.length > 0) {
            envMap.put("JSP", DIR_NATIVE_LIB + "/" + jsphName + ".so");
        }
    }

    private static void setEnv(String jreHome, Runtime runtime, Version gameVersion) throws Throwable {
        Map<String, String> envMap = new ArrayMap<>();

        setJavaEnv(envMap, jreHome);
        setCustomEnv(envMap);

        if (gameVersion != null) {
            configureJsphEnvIfNeeded(envMap, runtime);

            VersionInfo versionInfo = gameVersion.getVersionInfo();
            if (versionInfo != null && versionInfo.getLoaderInfo() != null) {
                for (VersionInfo.LoaderInfo loaderInfo : versionInfo.getLoaderInfo()) {
                    if (loaderInfo.getLoaderEnvKey() != null) {
                        envMap.put(loaderInfo.getLoaderEnvKey(), "1");
                    }
                }
            }

            if (Renderers.INSTANCE.isCurrentRendererValid()) {
                setRendererEnv(envMap);
            }

            envMap.put("ZALITH_VERSION_CODE", String.valueOf(ZHTools.getVersionCode()));
        }

        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue());
            try {
                Os.setenv(env.getKey(), env.getValue(), true);
            } catch (NullPointerException exception) {
                Logging.e("JREUtils", exception.toString());
            }
        }
    }

    private static void initGraphicAndSoundEngine(boolean withRenderer) {
        dlopen(DIR_NATIVE_LIB + "/libopenal.so");

        if (!withRenderer) {
            return;
        }

        String rendererLib = loadGraphicsLibrary();
        RendererPlugin customRenderer = RendererPluginManager.getSelectedRendererPlugin();

        if (customRenderer != null) {
            customRenderer.getDlopen().forEach(lib -> dlopen(customRenderer.getPath() + "/" + lib));
        }

        if (rendererLib != null && !dlopen(rendererLib) && !dlopen(findInLdLibPath(rendererLib))) {
            Logging.e("RENDER_LIBRARY", "Failed to load renderer " + rendererLib);
        }
    }

    private static void purgeUnsupportedUserArgs(List<String> userArgs) {
        purgeArg(userArgs, "-Xms");
        purgeArg(userArgs, "-Xmx");
        purgeArg(userArgs, "-d32");
        purgeArg(userArgs, "-d64");
        purgeArg(userArgs, "-Xint");
        purgeArg(userArgs, "-XX:+UseTransparentHugePages");
        purgeArg(userArgs, "-XX:+UseLargePagesInMetaspace");
        purgeArg(userArgs, "-XX:+UseLargePages");
        purgeArg(userArgs, "-Dorg.lwjgl.opengl.libname");
        purgeArg(userArgs, "-Dorg.lwjgl.freetype.libname");
        purgeArg(userArgs, "-XX:ActiveProcessorCount");
        purgeArg(userArgs, "-Djava.library.path");
        purgeArg(userArgs, "-Djna.boot.library.path");
        purgeArg(userArgs, "-Djna.tmpdir");
        purgeArg(userArgs, "-Dorg.lwjgl.librarypath");
        purgeArg(userArgs, "-Dorg.lwjgl.system.SharedLibraryExtractPath");
        purgeArg(userArgs, "-Dio.netty.native.workdir");
    }

    /**
     * Embedded LWJGL SDL3 (MC 26.3+) bridge jar: org.lwjgl.sdl.* classes + VK Vulkan-driver stub.
     * Byte-identical to lwjgl-sdl-26.3-bridge.jar; unpacked to cache at every launch and appended
     * to the bootclasspath (see addLauncherManagedJvmArgs) so Minecraft 26.3+ boots even when the
     * active lwjgl component jar predates SDL3 support.
     */
    private static final String SDL_BRIDGE_JAR_B64 = "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";

    private static void addLauncherManagedJvmArgs(List<String> userArgs, Runtime runtime) {
        userArgs.add("-javaagent:" + LibPath.MIO_LIB_PATCHER.getAbsolutePath());
        int ramMb = AllSettings.getRamAllocation().getValue().getValue();
        int initRamMb = Math.min(512, ramMb);
        userArgs.add("-Xms" + initRamMb + "M");
        userArgs.add("-Xmx" + ramMb + "M");

        boolean hasCustomGc = false;
        for (String arg : userArgs) {
            if (arg.startsWith("-XX:+Use") && arg.endsWith("GC")) {
                hasCustomGc = true;
                break;
            }
        }
        if (!hasCustomGc) {
            userArgs.add("-XX:+UnlockExperimentalVMOptions");
            userArgs.add("-XX:+UseG1GC");
            userArgs.add("-XX:ParallelGCThreads=4"); // lock G1 workers to the 4 big cores
            userArgs.add("-XX:ConcGCThreads=1");
            userArgs.add("-XX:+ParallelRefProcEnabled");
            userArgs.add("-XX:MaxGCPauseMillis=30");
            userArgs.add("-XX:+DisableExplicitGC");
            userArgs.add("-XX:+UseStringDeduplication"); // G1-only: kept with the GC block
            userArgs.add("-XX:G1NewSizePercent=20");
            userArgs.add("-XX:G1ReservePercent=15");
            userArgs.add("-XX:G1HeapRegionSize=8M");
        }

        // --- In-game FPS boosters: chunk worker cap, LWJGL3 fast path, Netty,
        // --- ARM64 NEON/FMA, C2 JIT inlining (see ZalithLauncher-120Hz-LockFix-And-MaxFPS.patch)
        userArgs.add("-Dmax.bg.threads=3");
        userArgs.add("-Dorg.lwjgl.util.NoChecks=true");
        userArgs.add("-Dorg.lwjgl.util.NoFunctionChecks=true");
        userArgs.add("-Dorg.lwjgl.system.allocator=system");
        userArgs.add("-Dio.netty.leakDetection.level=disabled");
        userArgs.add("-Dio.netty.allocator.type=pooled");
        userArgs.add("-XX:+IgnoreUnrecognizedVMOptions");
        userArgs.add("-XX:+UnlockExperimentalVMOptions");
        userArgs.add("-XX:+UnlockDiagnosticVMOptions");
        userArgs.add("-XX:+AlwaysPreTouch");
        userArgs.add("-XX:+UseTransparentHugePages");
        userArgs.add("-XX:+UseCompressedOops");
        userArgs.add("-XX:+UseCompressedClassPointers");
        userArgs.add("-XX:+OptimizeStringConcat");
        userArgs.add("-XX:+UseFMA");
        userArgs.add("-XX:MaxInlineLevel=15");
        userArgs.add("-XX:InlineSmallCode=2500");
        userArgs.add("-XX:CompileThreshold=1500");

        // --- MC 26.3+ SDL3 runtime bridge: unpack the embedded lwjgl-sdl-26.3-bridge.jar
        // --- (org.lwjgl.sdl.* classes + VK Vulkan-driver stub) and append it to the bootclasspath,
        // --- so MC 26.3+ boots even when the active lwjgl component jar predates SDL3 support.
        try {
            java.io.File sdlBridgeJar = new java.io.File(PathManager.DIR_CACHE.getAbsolutePath(), "lwjgl-sdl-26.3-bridge.jar");
            byte[] sdlBridgeBytes = android.util.Base64.decode(SDL_BRIDGE_JAR_B64, android.util.Base64.DEFAULT);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(sdlBridgeJar)) {
                fos.write(sdlBridgeBytes);
            }
            userArgs.add("-Xbootclasspath/a:" + sdlBridgeJar.getAbsolutePath());
        } catch (Throwable ignored) {
        }

        // Point LWJGL's VK class at the system Vulkan loader on Android
        userArgs.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so");

        // --enable-native-access is only understood by Java 17+; older JVMs abort on unknown options
        if (runtime != null && runtime.javaVersion >= 17) {
            userArgs.add("--enable-native-access=ALL-UNNAMED");
        }

        if (Renderers.INSTANCE.isCurrentRendererValid()) {
            userArgs.add("-Dorg.lwjgl.opengl.libname=" + loadGraphicsLibrary());
        }

        if (!FORCE_PURE_HYBRID_TEST_MODE) {
            userArgs.add("-Dorg.lwjgl.freetype.libname=" + DIR_NATIVE_LIB + "/libfreetype.so");
        }

        userArgs.add("-XX:ActiveProcessorCount=" + java.lang.Runtime.getRuntime().availableProcessors());
    }

    private static void addPureHybridCacheOverrides(List<String> userArgs) {
        if (!FORCE_PURE_HYBRID_TEST_MODE) {
            return;
        }

        purgeArg(userArgs, "-Djna.tmpdir");
        purgeArg(userArgs, "-Dorg.lwjgl.system.SharedLibraryExtractPath");
        purgeArg(userArgs, "-Dio.netty.native.workdir");

        userArgs.add("-Djna.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath());
        userArgs.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + PathManager.DIR_CACHE.getAbsolutePath());
        userArgs.add("-Dio.netty.native.workdir=" + PathManager.DIR_CACHE.getAbsolutePath());
    }

    private static void logJvmArgs(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            Logger.appendToLog("JVMArg: " + arg);

            if (arg.startsWith("--accessToken")) {
                i++;
            }
        }
    }

    private static void launchJavaVM(
            AppCompatActivity activity,
            Runtime runtime,
            String runtimeHome,
            Version gameVersion,
            List<String> jvmArgs,
            String userArgsString
    ) {
        List<String> userArgs = getJavaArgs(runtimeHome, userArgsString);

        purgeUnsupportedUserArgs(userArgs);
        addLauncherManagedJvmArgs(userArgs, runtime);
        userArgs.addAll(jvmArgs);
        addPureHybridCacheOverrides(userArgs);

        try {
            android.view.Display d = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ? activity.getDisplay()
                    : activity.getWindowManager().getDefaultDisplay();
            if (d != null) {
                int maxHz = Math.round(d.getRefreshRate());
                for (android.view.Display.Mode m : d.getSupportedModes()) {
                    maxHz = Math.max(maxHz, Math.round(m.getRefreshRate()));
                }
                if (maxHz >= 60) {
                    userArgs.add("-Dglfwstub.refreshRate=" + maxHz);
                }
            }
        } catch (Throwable ignored) {
        }

        activity.runOnUiThread(() ->
                Toast.makeText(
                        activity,
                        activity.getString(R.string.autoram_info_msg, AllSettings.getRamAllocation().getValue().getValue()),
                        Toast.LENGTH_SHORT
                ).show()
        );

        logJvmArgs(userArgs);

        setupExitMethod(activity.getApplication());
        initializeGameExitHook();

        chdir(gameVersion == null
                ? ProfilePathHome.getGameHome()
                : gameVersion.getGameDir().getAbsolutePath());

        userArgs.add(0, "java");

        int exitCode = VMLauncher.launchJVM(userArgs.toArray(new String[0]));
        Logger.appendToLog("Java Exit code: " + exitCode);

        if (exitCode != 0) {
            ErrorActivity.showExitMessage(activity, exitCode, false);
        }

        EventBus.getDefault().post(new JvmExitEvent(exitCode));
    }

    public static void launchWithUtils(
            AppCompatActivity activity,
            Runtime runtime,
            Version gameVersion,
            List<String> jvmArgs,
            String userArgsString
    ) throws Throwable {
        String runtimeHome = MultiRTUtils.getRuntimeHome(runtime.name).getAbsolutePath();

        relocateLibPath(runtime, runtimeHome);
        initLdLibraryPath(runtimeHome);
        setEnv(runtimeHome, runtime, gameVersion);
        initJavaRuntime(runtimeHome);
        initGraphicAndSoundEngine(gameVersion != null);
        launchJavaVM(activity, runtime, runtimeHome, gameVersion, jvmArgs, userArgsString);
    }

    public static List<String> getJavaArgs(String runtimeHome, String userArgumentsString) {
        List<String> userArguments = parseJavaArguments(userArgumentsString);
        String resolvFile = new File(PathManager.DIR_DATA, "resolv.conf").getAbsolutePath();

        ArrayList<String> overridableArguments = new ArrayList<>(Arrays.asList(
                "-Djava.home=" + runtimeHome,
                "-Djava.io.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath(),
                "-Djna.boot.library.path=" + (FORCE_PURE_HYBRID_TEST_MODE ? PathManager.DIR_CACHE.getAbsolutePath() : DIR_NATIVE_LIB),
                "-Duser.home=" + ProfilePathManager.INSTANCE.getCurrentPath(),
                "-Duser.language=" + System.getProperty("user.language"),
                "-Dos.name=Linux",
                "-Dos.version=Android-" + Build.VERSION.RELEASE,
                "-Dpojav.path.minecraft=" + ProfilePathHome.getGameHome(),
                "-Dpojav.path.private.account=" + PathManager.DIR_ACCOUNT_NEW,
                "-Duser.timezone=" + TimeZone.getDefault().getID(),
                "-Dglfwstub.windowWidth=" + Tools.getDisplayFriendlyRes(
                        currentDisplayMetrics.widthPixels,
                        AllSettings.getResolutionRatio().getValue() / 100F
                ),
                "-Dglfwstub.windowHeight=" + Tools.getDisplayFriendlyRes(
                        currentDisplayMetrics.heightPixels,
                        AllSettings.getResolutionRatio().getValue() / 100F
                ),
                "-Dglfwstub.initEgl=false",
                "-Dext.net.resolvPath=" + resolvFile,
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Dnet.minecraft.clientmodname=" + InfoDistributor.LAUNCHER_NAME,
                "-Dfml.earlyprogresswindow=false",
                "-Dloader.disable_forked_guis=true",
                "-Djdk.lang.Process.launchMechanism=FORK",
                "-Dsodium.checks.issue2561=false"
        ));

        List<String> additionalArguments = new ArrayList<>();
        for (String arg : overridableArguments) {
            String strippedArg = arg.substring(0, arg.indexOf('='));
            boolean add = true;

            for (String uarg : userArguments) {
                if (uarg.startsWith(strippedArg)) {
                    add = false;
                    break;
                }
            }

            if (add) {
                additionalArguments.add(arg);
            } else {
                Logging.i("ArgProcessor", "Arg skipped: " + arg);
            }
        }

        userArguments.addAll(additionalArguments);
        return userArguments;
    }

    public static ArrayList<String> parseJavaArguments(String args) {
        ArrayList<String> parsedArguments = new ArrayList<>(0);
        args = args.trim().replace(" ", "");

        String[] separators = new String[]{"-XX:-", "-XX:+", "-XX:", "--", "-D", "-X", "-javaagent:", "-verbose"};
        for (String prefix : separators) {
            while (true) {
                int start = args.indexOf(prefix);
                if (start == -1) {
                    break;
                }

                int end = -1;
                for (String separator : separators) {
                    int tempEnd = args.indexOf(separator, start + prefix.length());
                    if (tempEnd == -1) {
                        continue;
                    }
                    if (end == -1) {
                        end = tempEnd;
                    } else {
                        end = Math.min(end, tempEnd);
                    }
                }

                if (end == -1) {
                    end = args.length();
                }

                String parsedSubString = args.substring(start, end);
                args = args.replace(parsedSubString, "");

                if (parsedSubString.indexOf('=') == parsedSubString.lastIndexOf('=')) {
                    int arraySize = parsedArguments.size();
                    if (arraySize > 0) {
                        String lastString = parsedArguments.get(arraySize - 1);
                        if (lastString.charAt(lastString.length() - 1) == ',' || parsedSubString.contains(",")) {
                            parsedArguments.set(arraySize - 1, lastString + parsedSubString);
                            continue;
                        }
                    }
                    parsedArguments.add(parsedSubString);
                } else {
                    Logging.w("JAVA ARGS PARSER", "Removed improper arguments: " + parsedSubString);
                }
            }
        }

        return parsedArguments;
    }

    public static String loadGraphicsLibrary() {
        if (!Renderers.INSTANCE.isCurrentRendererValid()) {
            return null;
        }

        RendererPlugin rendererPlugin = RendererPluginManager.getSelectedRendererPlugin();
        if (rendererPlugin != null) {
            return rendererPlugin.getPath() + "/" + rendererPlugin.getGlName();
        }

        return Renderers.INSTANCE.getCurrentRenderer().getRendererLibrary();
    }

    private static void purgeArg(List<String> argList, String argStart) {
        argList.removeIf(arg -> arg.startsWith(argStart));
    }

    @SuppressWarnings("SameParameterValue")
    private static boolean hasExtension(String extensions, String name) {
        int start = extensions.indexOf(name);
        while (start >= 0) {
            int end = start + name.length();
            if (end == extensions.length() || extensions.charAt(end) == ' ') {
                return true;
            }
            start = extensions.indexOf(name, end);
        }
        return false;
    }

    public static int getDetectedVersion() {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        int[] numConfigs = new int[1];

        if (!egl.eglInitialize(display, null)) {
            Logging.e("glesDetect", "Couldn't initialize EGL.");
            return -3;
        }

        try {
            boolean checkES3 = hasExtension(
                    egl.eglQueryString(display, EGL10.EGL_EXTENSIONS),
                    "EGL_KHR_create_context"
            );

            if (!egl.eglGetConfigs(display, null, 0, numConfigs)) {
                Logging.e("glesDetect", "Getting number of configs with EGL10#eglGetConfigs failed: " + egl.eglGetError());
                return -2;
            }

            EGLConfig[] configs = new EGLConfig[numConfigs[0]];
            if (!egl.eglGetConfigs(display, configs, numConfigs[0], numConfigs)) {
                Logging.e("glesDetect", "Getting configs with EGL10#eglGetConfigs failed: " + egl.eglGetError());
                return -1;
            }

            int highestEsVersion = 0;
            int[] value = new int[1];

            for (int i = 0; i < numConfigs[0]; i++) {
                if (!egl.eglGetConfigAttrib(display, configs[i], EGL10.EGL_RENDERABLE_TYPE, value)) {
                    Logging.w(
                            "glesDetect",
                            "Getting config attribute with EGL10#eglGetConfigAttrib failed (" +
                                    i + "/" + numConfigs[0] + "): " + egl.eglGetError()
                    );
                    continue;
                }

                if (checkES3 && ((value[0] & EGL_OPENGL_ES3_BIT_KHR) == EGL_OPENGL_ES3_BIT_KHR)) {
                    highestEsVersion = Math.max(highestEsVersion, 3);
                } else if ((value[0] & EGL_OPENGL_ES2_BIT) == EGL_OPENGL_ES2_BIT) {
                    highestEsVersion = Math.max(highestEsVersion, 2);
                } else if ((value[0] & EGL_OPENGL_ES_BIT) == EGL_OPENGL_ES_BIT) {
                    highestEsVersion = Math.max(highestEsVersion, 1);
                }
            }

            return highestEsVersion;
        } finally {
            // Do not call eglTerminate on EGL_DEFAULT_DISPLAY because it is shared process-wide.
        }
    }

    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeGameExitHook();
    public static native void setupExitMethod(Context context);
    public static native int[] renderAWTScreenFrame();

    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }
}