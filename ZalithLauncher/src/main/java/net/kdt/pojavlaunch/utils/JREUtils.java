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

        if (!rendererId.startsWith("opengles") && !isDirectMesaKgslRenderer(rendererId, envMap)) {
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

        if (rendererLib != null && isRenderSpecProxyLibrary(rendererLib)) {
            Logging.i("RENDER_LIBRARY", "Skipping Java-side dlopen for RenderSpec proxy " + rendererLib);
        } else if (rendererLib != null && !dlopen(rendererLib) && !dlopen(findInLdLibPath(rendererLib))) {
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

    private static void addLauncherManagedJvmArgs(List<String> userArgs) {
        userArgs.add("-javaagent:" + LibPath.MIO_LIB_PATCHER.getAbsolutePath());
        userArgs.add("-Xms" + AllSettings.getRamAllocation().getValue().getValue() + "M");
        userArgs.add("-Xmx" + AllSettings.getRamAllocation().getValue().getValue() + "M");

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
            String runtimeHome,
            Version gameVersion,
            List<String> jvmArgs,
            String userArgsString
    ) {
        List<String> userArgs = getJavaArgs(runtimeHome, userArgsString);

        purgeUnsupportedUserArgs(userArgs);
        addLauncherManagedJvmArgs(userArgs);
        userArgs.addAll(jvmArgs);
        addPureHybridCacheOverrides(userArgs);

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
        launchJavaVM(activity, runtimeHome, gameVersion, jvmArgs, userArgsString);
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

    private static boolean isDirectMesaKgslRenderer(String rendererId, Map<String, String> envMap) {
        return "freedreno_kgsl".equals(rendererId)
                || "freedreno_kgsl".equals(envMap.get("ZALITH_MESA_MODE"))
                || "freedreno_kgsl".equals(envMap.get("DROIDBRIDGE_MESA_MODE"))
                || "kgsl".equals(envMap.get("ZALITH_MESA_DRIVER"))
                || "kgsl".equals(envMap.get("DROIDBRIDGE_MESA_DRIVER"));
    }

    private static boolean isRenderSpecProxyLibrary(String rendererLib) {
        if (rendererLib == null) {
            return false;
        }

        return rendererLib.endsWith("libGLZalith.so")
                || rendererLib.endsWith("libGLZalithMesa.so")
                || rendererLib.endsWith("libGLDroidBridge.so")
                || rendererLib.endsWith("libGLDroidBridgeMesa.so")
                || rendererLib.endsWith("libGLMojo.so");
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
            egl.eglTerminate(display);
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
