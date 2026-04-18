package net.kdt.pojavlaunch;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;
import static com.movtery.zalithlauncher.setting.AllStaticSettings.notchSize;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.context.ContextExecutor;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.utils.LauncherProfiles;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.ui.activity.BaseActivity;
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.runtime.SelectRuntimeUtils;
import com.movtery.zalithlauncher.utils.stringutils.StringUtils;

import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.memory.MemoryHoleFinder;
import net.kdt.pojavlaunch.memory.SelfMapsParser;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.libsdl.app.SDLControllerManager;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("IOStreamConstructor")
public final class Tools {
    public static final String NOTIFICATION_CHANNEL_DEFAULT = "channel_id";
    public static final float BYTE_TO_MB = 1024 * 1024;
    public static final Gson GLOBAL_GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String LAUNCHERPROFILES_RTPREFIX = "pojav://";
    private static final boolean isClientFirst = false;
    public static int DEVICE_ARCHITECTURE;
    public static String DIRNAME_HOME_JRE = "lib";
    public static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final String DEFAULT_LWJGL_COMPONENT = "lwjgl3";
    private static final String LWJGL_COMPONENT_OVERRIDE_PROPERTY = "pojav.lwjgl.component";
    private static final String LWJGL_COMPONENT_FALLBACK = "lwjglVulkan";
    private static final String LWJGL_COMPONENT_VULKAN = "lwjglVulkan";

    /**
     * Returns true for newer Minecraft version ids that should prefer the newer LWJGL bundle.
     *
     * Examples matched:
     * - 26w14a
     * - 26w14a-snapshot
     * - 26w14a-rc1
     * - 26.1
     * - 26.2
     * - 27.1
     */
    private static boolean isModernMinecraftVersionId(String versionToken) {
        if (!isValidString(versionToken)) return false;

        String v = versionToken.trim().toLowerCase(Locale.ROOT);

        java.util.regex.Matcher snapshotMatcher = java.util.regex.Pattern
                .compile("^(\\d{2})w\\d{2}[a-z](?:[-_].*)?$")
                .matcher(v);

        if (snapshotMatcher.matches()) {
            int yearPrefix = Integer.parseInt(snapshotMatcher.group(1));
            return yearPrefix >= 26;
        }

        java.util.regex.Matcher releaseMatcher = java.util.regex.Pattern
                .compile("^(\\d{2})\\.(\\d+)(?:\\.\\d+)?(?:[-_].*)?$")
                .matcher(v);

        if (releaseMatcher.matches()) {
            int yearPrefix = Integer.parseInt(releaseMatcher.group(1));
            return yearPrefix >= 26;
        }

        return false;
    }

    private static boolean shouldUseVulkanLWJGLComponent(String versionToken) {
        if (!isValidString(versionToken)) return false;

        String v = versionToken.trim().toLowerCase(Locale.ROOT);

        java.util.regex.Matcher snapshotMatcher = java.util.regex.Pattern
                .compile("^(\\d{2})\\.(\\d+)(?:\\.\\d+)?(?:[-_].*)?$")
                .matcher(v);
        if (snapshotMatcher.matches()) {
            int major = Integer.parseInt(snapshotMatcher.group(1));
            int minor = Integer.parseInt(snapshotMatcher.group(2));
            return major > 26 || (major == 26 && minor >= 2);
        }

        java.util.regex.Matcher yearWeekMatcher = java.util.regex.Pattern
                .compile("^(\\d{2})w(\\d{2})[a-z](?:[-_].*)?$")
                .matcher(v);
        if (yearWeekMatcher.matches()) {
            int yearPrefix = Integer.parseInt(yearWeekMatcher.group(1));
            return yearPrefix >= 26;
        }

        if (v.contains("26.2")) return true;
        if (v.contains("26.3")) return true;
        if (v.contains("27.")) return true;
        if (v.contains("28.")) return true;

        return false;
    }

    /**
     * Checks if the Pojav's storage root is accessible and read-writable.
     *
     * @return true if storage is fine, false if storage is not accessible
     */
    public static boolean checkStorageRoot() {
        File externalFilesDir = new File(PathManager.DIR_GAME_HOME);
        return Environment.getExternalStorageState(externalFilesDir).equals(Environment.MEDIA_MOUNTED);
    }

    public static void buildNotificationChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_DEFAULT,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.createNotificationChannel(channel);
    }

    public static void disableSplash(File dir) {
        File configDir = new File(dir, "config");
        if (FileUtils.ensureDirectorySilently(configDir)) {
            File forgeSplashFile = new File(dir, "config/splash.properties");
            String forgeSplashContent = "enabled=true";
            try {
                if (forgeSplashFile.exists()) {
                    forgeSplashContent = Tools.read(forgeSplashFile.getAbsolutePath());
                }
                if (forgeSplashContent.contains("enabled=true")) {
                    Tools.write(
                            forgeSplashFile.getAbsolutePath(),
                            forgeSplashContent.replace("enabled=true", "enabled=false")
                    );
                }
            } catch (IOException e) {
                Logging.w(InfoDistributor.LAUNCHER_NAME,
                        "Could not disable Forge 1.12.2 and below splash screen!", e);
            }
        } else {
            Logging.w(InfoDistributor.LAUNCHER_NAME, "Failed to create the configuration directory");
        }
    }

    public static String fromStringArray(String[] strArr) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < strArr.length; i++) {
            if (i > 0) builder.append(" ");
            builder.append(strArr[i]);
        }
        return builder.toString();
    }

    public static String artifactToPath(DependentLibrary library) {
        if (library.downloads != null
                && library.downloads.artifact != null
                && library.downloads.artifact.path != null) {
            return library.downloads.artifact.path;
        }

        String[] libInfos = library.name.split(":");

        if (libInfos.length < 3) {
            Logging.e("Tools_artifactToPath", "Invalid library name format: " + library.name);
            return null;
        }

        String groupId = libInfos[0].replace('.', '/');
        String artifactId = libInfos[1];
        String version = libInfos[2];
        String classifier = (libInfos.length > 3) ? "-" + libInfos[3] : "";

        return String.format(
                "%s/%s/%s/%s-%s%s.jar",
                groupId, artifactId, version, artifactId, version, classifier
        );
    }

    public static String getClientClasspath(Version version) {
        return new File(version.getVersionPath(), version.getVersionName() + ".jar").getAbsolutePath();
    }

    private static File getLWJGLComponentDir(String componentName) {
        File privateDir = new File(PathManager.DIR_FILE, componentName);
        if (privateDir.isDirectory()) return privateDir;

        File externalDir = new File(PathManager.DIR_GAME_HOME, componentName);
        if (externalDir.isDirectory()) return externalDir;

        return privateDir;
    }

    public static boolean hasLWJGLComponent(String componentName) {
        return getLWJGLComponentDir(componentName).isDirectory();
    }

    public static String getRequestedLWJGLComponent() {
        String forcedComponent = System.getProperty(LWJGL_COMPONENT_OVERRIDE_PROPERTY);
        return isValidString(forcedComponent) ? forcedComponent : null;
    }

    public static String resolveLWJGLComponent() {
        String forcedComponent = getRequestedLWJGLComponent();
        if (forcedComponent != null) {
            if (hasLWJGLComponent(forcedComponent)) return forcedComponent;
            Logging.w(
                    InfoDistributor.LAUNCHER_NAME,
                    "Requested LWJGL component does not exist: "
                            + getLWJGLComponentDir(forcedComponent).getAbsolutePath()
            );
        }

        if (hasLWJGLComponent(DEFAULT_LWJGL_COMPONENT)) return DEFAULT_LWJGL_COMPONENT;
        if (hasLWJGLComponent(LWJGL_COMPONENT_FALLBACK)) return LWJGL_COMPONENT_FALLBACK;

        return DEFAULT_LWJGL_COMPONENT;
    }

    public static String resolveLWJGLComponent(Version minecraftVersion) {
        return resolveLWJGLComponentForLaunch(minecraftVersion, null);
    }

    public static String resolveLWJGLComponentForLaunch(
            Version minecraftVersion,
            JMinecraftVersionList.Version versionInfo
    ) {
        String forcedComponent = getRequestedLWJGLComponent();
        if (forcedComponent != null) {
            if (hasLWJGLComponent(forcedComponent)) return forcedComponent;
            Logging.w(
                    InfoDistributor.LAUNCHER_NAME,
                    "Requested LWJGL component does not exist: "
                            + getLWJGLComponentDir(forcedComponent).getAbsolutePath()
            );
        }

        String versionToken = getVersionToken(minecraftVersion, versionInfo);

        if (shouldUseVulkanLWJGLComponent(versionToken)
                && hasLWJGLComponent(LWJGL_COMPONENT_VULKAN)) {
            Logging.i(
                    InfoDistributor.LAUNCHER_NAME,
                    "LWJGL auto-select: version=" + versionToken + ", component=" + LWJGL_COMPONENT_VULKAN
            );
            return LWJGL_COMPONENT_VULKAN;
        }

        if (shouldUseModernLWJGL(minecraftVersion, versionInfo)
                && hasLWJGLComponent(LWJGL_COMPONENT_FALLBACK)) {
            return LWJGL_COMPONENT_FALLBACK;
        }

        if (hasLWJGLComponent(DEFAULT_LWJGL_COMPONENT)) return DEFAULT_LWJGL_COMPONENT;
        if (hasLWJGLComponent(LWJGL_COMPONENT_FALLBACK)) return LWJGL_COMPONENT_FALLBACK;
        if (hasLWJGLComponent(LWJGL_COMPONENT_VULKAN)) return LWJGL_COMPONENT_VULKAN;

        return DEFAULT_LWJGL_COMPONENT;
    }

    public static boolean shouldUseModernLWJGL(
            Version minecraftVersion,
            JMinecraftVersionList.Version versionInfo
    ) {
        String versionToken = getVersionToken(minecraftVersion, versionInfo);
        boolean result = isModernMinecraftVersionId(versionToken);

        Logging.i(
                InfoDistributor.LAUNCHER_NAME,
                "LWJGL auto-select: version=" + versionToken + ", useModern=" + result
        );

        return result;
    }

    private static String getVersionToken(
            Version minecraftVersion,
            JMinecraftVersionList.Version versionInfo
    ) {
        if (versionInfo != null && isValidString(versionInfo.inheritsFrom)) {
            return versionInfo.inheritsFrom;
        }
        if (versionInfo != null && isValidString(versionInfo.id)) {
            return versionInfo.id;
        }
        if (minecraftVersion != null && isValidString(minecraftVersion.getVersionName())) {
            return minecraftVersion.getVersionName();
        }
        return null;
    }

    public static String getLWJGLClassPath(String componentName) {
        StringBuilder libStr = new StringBuilder();
        File lwjglFolder = getLWJGLComponentDir(componentName);
        File[] lwjglFiles = lwjglFolder.listFiles();

        Logging.i(InfoDistributor.LAUNCHER_NAME,
                "LWJGL classpath scan component=" + componentName + ", dir=" + lwjglFolder.getAbsolutePath());

        if (lwjglFiles != null) {
            Arrays.sort(lwjglFiles, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : lwjglFiles) {
                Logging.i(InfoDistributor.LAUNCHER_NAME,
                        "LWJGL scan entry: " + file.getAbsolutePath() + " exists=" + file.exists());

                if (file.getName().endsWith(".jar")) {
                    if (libStr.length() > 0) libStr.append(":");
                    libStr.append(file.getAbsolutePath());
                }
            }
        } else {
            Logging.w(InfoDistributor.LAUNCHER_NAME,
                    "LWJGL classpath scan: listFiles() returned null for " + lwjglFolder.getAbsolutePath());
        }

        Logging.i(InfoDistributor.LAUNCHER_NAME,
                "LWJGL classpath result=" + libStr);

        return libStr.toString();
    }

    public static String getLWJGLClassPathForLaunch() {
        return getLWJGLClassPath(resolveLWJGLComponent());
    }

    public static String getLWJGLClassPathForLaunch(Version minecraftVersion) {
        return getLWJGLClassPath(resolveLWJGLComponentForLaunch(minecraftVersion, null));
    }

    public static String getLWJGLClassPathForLaunch(
            Version minecraftVersion,
            JMinecraftVersionList.Version versionInfo
    ) {
        return getLWJGLClassPath(resolveLWJGLComponentForLaunch(minecraftVersion, versionInfo));
    }

    public static String getLWJGL3ClassPath() {
        return getLWJGLClassPath("lwjgl3");
    }

    public static String getLWJGL342ClassPath() {
        return getLWJGLClassPath("lwjgl3.4.2");
    }

    public static String getLWJGLVulkanClassPath() {
        return getLWJGLClassPath(LWJGL_COMPONENT_VULKAN);
    }

    public static String generateLaunchClassPath(JMinecraftVersionList.Version info, Version minecraftVersion) {
        StringBuilder finalClasspath = new StringBuilder();
        String[] classpath = generateLibClasspath(info);
        String clientClasspath = getClientClasspath(minecraftVersion);

        if (isClientFirst) {
            finalClasspath.append(clientClasspath);
        }

        for (String jarFile : classpath) {
            if (!FileUtils.exists(jarFile)) {
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Ignored non-exists file: " + jarFile);
                continue;
            }
            finalClasspath.append((isClientFirst ? ":" : ""))
                    .append(jarFile)
                    .append(!isClientFirst ? ":" : "");
        }

        if (!isClientFirst) {
            finalClasspath.append(clientClasspath);
        }

        return finalClasspath.toString();
    }

    public static DisplayMetrics getDisplayMetrics(BaseActivity activity) {
        DisplayMetrics displayMetrics = new DisplayMetrics();

        if (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode()) {
            displayMetrics = activity.getResources().getDisplayMetrics();
        } else {
            if (SDK_INT >= Build.VERSION_CODES.R) {
                activity.getDisplay().getRealMetrics(displayMetrics);
            } else {
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
            }

            if (!activity.shouldIgnoreNotch()) {
                if (activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT) {
                    displayMetrics.heightPixels -= notchSize;
                } else {
                    displayMetrics.widthPixels -= notchSize;
                }
            }
        }

        currentDisplayMetrics = displayMetrics;
        return displayMetrics;
    }

    public static void setFullscreen(Activity activity) {
        final View decorView = activity.getWindow().getDecorView();
        View.OnSystemUiVisibilityChangeListener visibilityChangeListener = visibility -> {
            boolean multiWindowMode = activity.isInMultiWindowMode();

            if (!multiWindowMode) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decorView.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    );
                }
            } else {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        };

        decorView.setOnSystemUiVisibilityChangeListener(visibilityChangeListener);
        visibilityChangeListener.onSystemUiVisibilityChange(decorView.getSystemUiVisibility());
    }

    public static DisplayMetrics currentDisplayMetrics;

    public static void updateWindowSize(BaseActivity activity) {
        currentDisplayMetrics = getDisplayMetrics(activity);
        CallbackBridge.physicalWidth = currentDisplayMetrics.widthPixels;
        CallbackBridge.physicalHeight = currentDisplayMetrics.heightPixels;
    }

    public static float dpToPx(float dp) {
        return dp * currentDisplayMetrics.density;
    }

    public static float pxToDp(float px) {
        return px / currentDisplayMetrics.density;
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, boolean overwrite)
            throws IOException {
        copyAssetFile(ctx, fileName, output, new File(fileName).getName(), overwrite);
    }

    public static void copyAssetFile(
            Context ctx,
            String fileName,
            String output,
            String outputName,
            boolean overwrite
    ) throws IOException {
        File parentFolder = new File(output);
        FileUtils.ensureDirectory(parentFolder);
        File destinationFile = new File(output, outputName);

        if (!destinationFile.exists() || overwrite) {
            try (InputStream inputStream = ctx.getAssets().open(fileName);
                 OutputStream outputStream = new FileOutputStream(destinationFile)) {
                IOUtils.copy(inputStream, outputStream);
            }
        }
    }

    public static String printToString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.close();
        return stringWriter.toString();
    }

    public static void showError(Context ctx, Throwable e) {
        showError(ctx, e, false);
    }

    public static void showError(final Context ctx, final Throwable e, final boolean exitIfOk) {
        showError(ctx, R.string.generic_error, null, e, exitIfOk, false);
    }

    public static void showError(final Context ctx, final int rolledMessage, final Throwable e) {
        showError(ctx, R.string.generic_error, ctx.getString(rolledMessage), e, false, false);
    }

    public static void showError(final Context ctx, final String rolledMessage, final Throwable e) {
        showError(ctx, R.string.generic_error, rolledMessage, e, false, false);
    }

    public static void showError(
            final Context ctx,
            final String rolledMessage,
            final Throwable e,
            boolean exitIfOk
    ) {
        showError(ctx, R.string.generic_error, rolledMessage, e, exitIfOk, false);
    }

    public static void showError(final Context ctx, final int titleId, final Throwable e, final boolean exitIfOk) {
        showError(ctx, titleId, null, e, exitIfOk, false);
    }

    private static void showError(
            final Context ctx,
            final int titleId,
            final String rolledMessage,
            final Throwable e,
            final boolean exitIfOk,
            final boolean showMore
    ) {
        if (e instanceof ContextExecutorTask) {
            ContextExecutor.executeTask((ContextExecutorTask) e);
            return;
        }

        Logging.e("ShowError", printToString(e));

        Runnable runnable = () -> {
            final String errMsg = showMore
                    ? printToString(e)
                    : rolledMessage != null ? rolledMessage : e.getMessage();

            AlertDialog.Builder builder = new AlertDialog.Builder(ctx, R.style.CustomAlertDialogTheme)
                    .setTitle(titleId)
                    .setMessage(errMsg)
                    .setPositiveButton(android.R.string.ok, (p1, p2) -> {
                        if (exitIfOk) {
                            if (ctx instanceof MainActivity) {
                                ZHTools.killProcess();
                            } else if (ctx instanceof Activity) {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setNegativeButton(
                            showMore ? R.string.error_show_less : R.string.error_show_more,
                            (p1, p2) -> showError(ctx, titleId, rolledMessage, e, exitIfOk, !showMore)
                    )
                    .setNeutralButton(android.R.string.copy, (p1, p2) -> {
                        StringUtils.copyText("error", printToString(e), ctx);
                        if (exitIfOk) {
                            if (ctx instanceof MainActivity) {
                                ZHTools.killProcess();
                            } else {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setCancelable(!exitIfOk);

            try {
                builder.show();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        };

        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }

    public static void showErrorRemote(Throwable e) {
        showErrorRemote(null, e);
    }

    public static void showErrorRemote(Context context, int rolledMessage, Throwable e) {
        showErrorRemote(context.getString(rolledMessage), e);
    }

    public static void showErrorRemote(String rolledMessage, Throwable e) {
        ContextExecutor.executeTask(new ShowErrorActivity.RemoteErrorTask(e, rolledMessage));
    }

    private static boolean checkRules(JMinecraftVersionList.Arguments.ArgValue.ArgRules[] rules) {
        if (rules == null) return true;

        for (JMinecraftVersionList.Arguments.ArgValue.ArgRules rule : rules) {
            if (rule.action.equals("allow") && rule.os != null && rule.os.name.equals("osx")) {
                return false;
            }
        }

        return true;
    }

    public static void preProcessLibraries(DependentLibrary[] libraries) {
        for (DependentLibrary libItem : libraries) {
            String[] version = libItem.name.split(":")[2].split("\\.");

            if (libItem.name.startsWith("net.java.dev.jna:jna:")) {
                if (Integer.parseInt(version[0]) >= 5 && Integer.parseInt(version[1]) >= 13) {
                    continue;
                }

                Logging.d(InfoDistributor.LAUNCHER_NAME,
                        "Library " + libItem.name + " has been changed to version 5.13.0");
                createLibraryInfo(libItem);
                libItem.name = "net.java.dev.jna:jna:5.13.0";
                libItem.downloads.artifact.path = "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
                libItem.downloads.artifact.sha1 = "1200e7ebeedbe0d10062093f32925a912020e747";
                libItem.downloads.artifact.url =
                        "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
            } else if (libItem.name.startsWith("com.github.oshi:oshi-core:")) {
                if (Integer.parseInt(version[0]) != 6 || Integer.parseInt(version[1]) != 2) {
                    continue;
                }

                Logging.d(InfoDistributor.LAUNCHER_NAME,
                        "Library " + libItem.name + " has been changed to version 6.3.0");
                createLibraryInfo(libItem);
                libItem.name = "com.github.oshi:oshi-core:6.3.0";
                libItem.downloads.artifact.path = "com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
                libItem.downloads.artifact.sha1 = "9e98cf55be371cafdb9c70c35d04ec2a8c2b42ac";
                libItem.downloads.artifact.url =
                        "https://repo1.maven.org/maven2/com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
            } else if (libItem.name.startsWith("org.ow2.asm:asm-all:")) {
                if (Integer.parseInt(version[0]) >= 5) continue;

                Logging.d(InfoDistributor.LAUNCHER_NAME,
                        "Library " + libItem.name + " has been changed to version 5.0.4");
                createLibraryInfo(libItem);
                libItem.name = "org.ow2.asm:asm-all:5.0.4";
                libItem.url = null;
                libItem.downloads.artifact.path = "org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar";
                libItem.downloads.artifact.sha1 = "e6244859997b3d4237a552669279780876228909";
                libItem.downloads.artifact.url =
                        "https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar";
            }
        }
    }

    private static void createLibraryInfo(DependentLibrary library) {
        if (library.downloads == null || library.downloads.artifact == null) {
            library.downloads = new DependentLibrary.LibraryDownloads(new MinecraftLibraryArtifact());
        }
    }

    public static String[] generateLibClasspath(JMinecraftVersionList.Version info) {
        List<String> libDir = new ArrayList<>();

        for (DependentLibrary libItem : info.libraries) {
            if (!checkRules(libItem.rules)) continue;

            String libName = libItem.name;
            if (libName == null) continue;

            if (libName.contains("org.lwjgl")
                    || libName.contains("jinput-platform")
                    || libName.contains("twitch-platform")) {
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Ignored unusable dependency: " + libName);
                continue;
            }

            String libArtifactPath = artifactToPath(libItem);
            if (libArtifactPath == null) continue;

            libDir.add(ProfilePathHome.getLibrariesHome() + "/" + libArtifactPath);
        }

        return libDir.toArray(new String[0]);
    }

    public static JMinecraftVersionList.Version getVersionInfo(Version version) {
        return getVersionInfo(version, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JMinecraftVersionList.Version getVersionInfo(Version version, boolean skipInheriting) {
        try {
            JMinecraftVersionList.Version customVer = Tools.GLOBAL_GSON.fromJson(
                    read(new File(version.getVersionPath(), version.getVersionName() + ".json")),
                    JMinecraftVersionList.Version.class
            );

            if (skipInheriting || customVer.inheritsFrom == null || customVer.inheritsFrom.equals(customVer.id)) {
                preProcessLibraries(customVer.libraries);
            } else {
                JMinecraftVersionList.Version inheritsVer;

                try {
                    inheritsVer = Tools.GLOBAL_GSON.fromJson(
                            read(version.getVersionsFolder() + "/" + customVer.inheritsFrom + "/"
                                    + customVer.inheritsFrom + ".json"),
                            JMinecraftVersionList.Version.class
                    );
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Can't find the source version for " + version.getVersionName()
                                    + " (req version=" + customVer.inheritsFrom + ")"
                    );
                }

                insertSafety(
                        inheritsVer,
                        customVer,
                        "assetIndex", "assets", "id",
                        "mainClass", "minecraftArguments",
                        "releaseTime", "time", "type"
                );

                List<DependentLibrary> inheritLibraryList = new ArrayList<>(Arrays.asList(inheritsVer.libraries));

                outer_loop:
                for (DependentLibrary library : customVer.libraries) {
                    String libName = library.name.substring(0, library.name.lastIndexOf(":"));

                    for (DependentLibrary inheritLibrary : inheritLibraryList) {
                        String inheritLibName = inheritLibrary.name.substring(0, inheritLibrary.name.lastIndexOf(":"));

                        if (libName.equals(inheritLibName)) {
                            Logging.d(
                                    InfoDistributor.LAUNCHER_NAME,
                                    "Library " + libName + ": Replaced version "
                                            + libName.substring(libName.lastIndexOf(":") + 1)
                                            + " with "
                                            + inheritLibName.substring(inheritLibName.lastIndexOf(":") + 1)
                            );

                            inheritLibraryList.remove(inheritLibrary);
                            continue outer_loop;
                        }
                    }
                }

                inheritLibraryList.addAll(Arrays.asList(customVer.libraries));
                inheritsVer.libraries = inheritLibraryList.toArray(new DependentLibrary[0]);
                preProcessLibraries(inheritsVer.libraries);

                if (inheritsVer.arguments != null && customVer.arguments != null) {
                    List totalArgList = new ArrayList(Arrays.asList(inheritsVer.arguments.game));

                    int nskip = 0;
                    for (int i = 0; i < customVer.arguments.game.length; i++) {
                        if (nskip > 0) {
                            nskip--;
                            continue;
                        }

                        Object perCustomArg = customVer.arguments.game[i];
                        if (perCustomArg instanceof String) {
                            String perCustomArgStr = (String) perCustomArg;
                            if (perCustomArgStr.startsWith("--") && totalArgList.contains(perCustomArgStr)) {
                                perCustomArg = customVer.arguments.game[i + 1];
                                if (perCustomArg instanceof String) {
                                    perCustomArgStr = (String) perCustomArg;
                                    if (!perCustomArgStr.startsWith("--")) {
                                        nskip++;
                                    }
                                }
                            } else {
                                totalArgList.add(perCustomArgStr);
                            }
                        } else if (!totalArgList.contains(perCustomArg)) {
                            totalArgList.add(perCustomArg);
                        }
                    }

                    inheritsVer.arguments.game = totalArgList.toArray(new Object[0]);
                }

                customVer = inheritsVer;
            }

            if (customVer.javaVersion != null && customVer.javaVersion.majorVersion == 0) {
                customVer.javaVersion.majorVersion = customVer.javaVersion.version;
            }

            return customVer;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void insertSafety(
            JMinecraftVersionList.Version targetVer,
            JMinecraftVersionList.Version fromVer,
            String... keyArr
    ) {
        for (String key : keyArr) {
            Object value = null;
            try {
                Field fieldA = findField(fromVer.getClass(), key);
                fieldA.setAccessible(true);
                value = fieldA.get(fromVer);

                if (value != null && (!(value instanceof String) || !((String) value).isEmpty())) {
                    Field fieldB = findField(targetVer.getClass(), key);
                    fieldB.setAccessible(true);
                    fieldB.set(targetVer, value);
                }
            } catch (Throwable th) {
                Logging.w(InfoDistributor.LAUNCHER_NAME, "Unable to insert " + key + "=" + value, th);
            }
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("No field " + name + " in class hierarchy of " + clazz.getName());
    }

    public static String read(InputStream is) throws IOException {
        String readResult = IOUtils.toString(is, StandardCharsets.UTF_8);
        is.close();
        return readResult;
    }

    public static String read(String path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static String read(File path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static void write(String path, String content) throws IOException {
        File file = new File(path);
        FileUtils.ensureParentDirectory(file);
        try (FileOutputStream outStream = new FileOutputStream(file)) {
            IOUtils.write(content, outStream);
        }
    }

    public interface DownloaderFeedback {
        void updateProgress(long curr, long max);
    }

    public static boolean compareSHA1(File f, String sourceSHA) {
        try {
            String sha1_dst;
            try (InputStream is = new FileInputStream(f)) {
                sha1_dst = new String(Hex.encodeHex(org.apache.commons.codec.digest.DigestUtils.sha1(is)));
            }

            if (sourceSHA != null) {
                return sha1_dst.equalsIgnoreCase(sourceSHA);
            } else {
                return true;
            }
        } catch (IOException e) {
            Logging.i("SHA1", "Fake-matching a hash due to a read error", e);
            return true;
        }
    }

    public static void ignoreNotch(boolean shouldIgnore, BaseActivity activity) {
        if (SDK_INT >= P) {
            if (shouldIgnore) {
                activity.getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            } else {
                activity.getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            }

            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            );
            Tools.updateWindowSize(activity);
        }
    }

    public static int getTotalDeviceMemory(Context ctx) {
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.totalMem / 1048576L);
    }

    public static int getFreeDeviceMemory(Context ctx) {
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.availMem / 1048576L);
    }

    private static int internalGetMaxContinuousAddressSpaceSize() throws Exception {
        MemoryHoleFinder memoryHoleFinder = new MemoryHoleFinder();
        new SelfMapsParser(memoryHoleFinder).run();
        long largestHole = memoryHoleFinder.getLargestHole();
        if (largestHole == -1) return -1;
        return (int) (largestHole / 1048576L);
    }

    public static int getMaxContinuousAddressSpaceSize() {
        try {
            return internalGetMaxContinuousAddressSpaceSize();
        } catch (Exception e) {
            Logging.w("Tools", "Failed to find the largest uninterrupted address space");
            return -1;
        }
    }

    public static int getDisplayFriendlyRes(int displaySideRes, float scaling) {
        int display = (int) (displaySideRes * scaling);
        if (display % 2 != 0) display--;
        return display;
    }

    public static String getFileName(Context ctx, Uri uri) {
        Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
        if (c == null) return uri.getLastPathSegment();

        c.moveToFirst();
        int columnIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (columnIndex == -1) return uri.getLastPathSegment();

        String fileName = c.getString(columnIndex);
        c.close();
        return fileName;
    }

    public static void backToMainMenu(FragmentActivity fragmentActivity) {
        fragmentActivity.getSupportFragmentManager().popBackStack(MainMenuFragment.TAG, 0);
    }

    public static void removeCurrentFragment(FragmentActivity fragmentActivity) {
        fragmentActivity.getSupportFragmentManager().popBackStack();
    }

    public static void installMod(Activity activity, boolean customJavaArgs) {
        if (MultiRTUtils.getExactJreName(8) == null) {
            Toast.makeText(activity, R.string.multirt_nojava8rt, Toast.LENGTH_LONG).show();
            return;
        }

        if (!customJavaArgs) {
            if (!(activity instanceof LauncherActivity)) {
                throw new IllegalStateException("Cannot start Mod Installer without LauncherActivity");
            }

            LauncherActivity launcherActivity = (LauncherActivity) activity;
            launcherActivity.modInstallerLauncher.launch(null);
            return;
        }

        new EditTextDialog.Builder(activity)
                .setTitle(R.string.dialog_select_jar)
                .setHintText("-jar/-cp /path/to/file.jar ...")
                .setAsRequired()
                .setConfirmListener((editBox, checked) -> {
                    Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
                    intent.putExtra("javaArgs", editBox.getText().toString());
                    SelectRuntimeUtils.selectRuntime(activity, null, jreName -> {
                        intent.putExtra(JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName);
                        activity.startActivity(intent);
                    });
                    return true;
                })
                .showDialog();
    }

    public static void launchModInstaller(Activity activity, @NonNull Uri uri) {
        Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
        intent.putExtra("modUri", uri);

        SelectRuntimeUtils.selectRuntime(activity, null, jreName -> {
            LauncherProfiles.generateLauncherProfiles();
            intent.putExtra(JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName);
            activity.startActivity(intent);
        });
    }

    public static void installRuntimeFromUri(Context context, Uri uri) {
        Task.runTask(() -> {
                    String name = getFileName(context, uri);
                    MultiRTUtils.installRuntimeNamed(
                            PathManager.DIR_NATIVE_LIB,
                            context.getContentResolver().openInputStream(uri),
                            name
                    );

                    MultiRTUtils.postPrepare(name);
                    return null;
                })
                .onThrowable(e -> Tools.showError(context, e))
                .execute();
    }

    public static String extractUntilCharacter(String input, String whatFor, char terminator) {
        int whatForStart = input.indexOf(whatFor);
        if (whatForStart == -1) return null;

        whatForStart += whatFor.length();
        int terminatorIndex = input.indexOf(terminator, whatForStart);
        if (terminatorIndex == -1) return null;

        return input.substring(whatForStart, terminatorIndex);
    }

    public static boolean isValidString(String string) {
        return string != null && !string.isEmpty();
    }

    public static boolean checkVulkanSupport(PackageManager packageManager) {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
                && packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
    }

    public static <T> T getWeakReference(WeakReference<T> weakReference) {
        if (weakReference == null) return null;
        return weakReference.get();
    }

    public static void runOnUiThread(Runnable runnable) {
        MAIN_HANDLER.post(runnable);
    }

    public static Object runMethodbyReflection(String className, String methodName)
            throws ReflectiveOperationException {
        Class<?> clazz = Class.forName(className);
        Method method = clazz.getDeclaredMethod(methodName);
        method.setAccessible(true);
        Object motionListener = method.invoke(null);
        assert motionListener != null;
        return motionListener;
    }

    public static void dialog(final Context context, final CharSequence title, final CharSequence message) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    static class SDL {
        public static native void initializeControllerSubsystems();
    }

    private static Logger.eventLogListener oldL4JMitigationLogListener;

    public static void startOldLegacy4JMitigation(Activity activity, File gamedir) {
        boolean hasLegacy4J = false;
        File modsDir = new File(gamedir, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));

        if (mods != null) {
            for (File file : mods) {
                String name = file.getName();
                if (name.contains("Legacy4J")) {
                    hasLegacy4J = true;
                    break;
                }
            }
        }

        if (hasLegacy4J) {
            String TAG = "OldLegacy4JMitigation";
            Log.i(TAG, "Legacy4J detected!");

            oldL4JMitigationLogListener = loggedLine -> {
                if (AllSettings.getGamepadSdlPassthru().getValue()
                        && loggedLine.contains("literal{SDL3 (isXander's libsdl4j)} isn't supported in this system. GLFW will be used instead.")) {
                    Log.i(TAG, "Old version of Legacy4J detected! Force enabling SDL");
                    Tools.SDL.initializeControllerSubsystems();
                    Tools.runOnUiThread(() ->
                            Tools.dialog(activity, "Warning!",
                                    "You are using Legacy4J enable SDL to use the built in controller feature!"));
                    Logger.removeLogListener(oldL4JMitigationLogListener);
                } else if (AllSettings.getGamepadSdlPassthru().getValue()
                        && loggedLine.contains("Added SDL Controller Mappings")) {
                    Log.i(TAG, "Fixed version of Legacy4J detected! Have fun!");
                    Logger.removeLogListener(oldL4JMitigationLogListener);
                }
            };

            Logger.addLogListener(oldL4JMitigationLogListener);
        }
    }

    private static Logger.eventLogListener controllableMitigationLogListener;

    public static void startControllableMitigation(Activity activity, File gamedir) {
        String TAG = "ControllableMitigation";
        File controllableDir = new File(gamedir, "controllable_natives/SDL");
        boolean hasControllable = false;

        File modsDir = new File(gamedir, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));

        if (mods != null) {
            for (File file : mods) {
                String name = file.getName().toLowerCase();
                if (name.contains("controllable")) {
                    hasControllable = true;
                    break;
                }
            }
        }

        if (!hasControllable) return;

        Log.i(TAG, "Controllable detected, starting mitigation early");
        Logger.appendToLog("Controllable detected, starting mitigation early");

        try {
            if (controllableDir.exists()) {
                org.apache.commons.io.FileUtils.deleteDirectory(controllableDir);
                Log.i(TAG, "Deleted existing controllable SDL dir: " + controllableDir.getAbsolutePath());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed initial controllable SDL cleanup", t);
        }

        Tools.runOnUiThread(() -> Tools.dialog(
                activity,
                "Warning!",
                "Controllable may crash on some setups. If it does, try again or use Controlify."
        ));

        Thread mitigationThread = new Thread(() -> {
            Log.i(TAG, "Mitigation watcher thread started");

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (controllableDir.isDirectory()) {
                        File[] versionDirs = controllableDir.listFiles();
                        if (versionDirs != null) {
                            for (File versionDir : versionDirs) {
                                File[] libs = versionDir.listFiles();
                                if (libs != null) {
                                    for (File lib : libs) {
                                        if (lib.isFile() && lib.getName().contains("SDL")) {
                                            Log.i(TAG, "Deleting extracted Controllable SDL: " + lib.getAbsolutePath());
                                            boolean ok = lib.delete();
                                            Log.i(TAG, "Delete result=" + ok);
                                            if (ok) {
                                                Log.i(TAG, "Mitigation succeeded, ending thread");
                                                return;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Mitigation loop error", t);
                }
            }

            Log.i(TAG, "Mitigation watcher thread ended");
        }, "ControllableMitigationThread");

        mitigationThread.start();

        controllableMitigationLogListener = loggedLine -> {
            if (loggedLine.contains("Sound engine started") && mitigationThread.isAlive()) {
                Log.i(TAG, "Stopping mitigation watcher after sound engine start");
                Logger.removeLogListener(controllableMitigationLogListener);
                mitigationThread.interrupt();
            }
        };

        Logger.addLogListener(controllableMitigationLogListener);
    }
}
