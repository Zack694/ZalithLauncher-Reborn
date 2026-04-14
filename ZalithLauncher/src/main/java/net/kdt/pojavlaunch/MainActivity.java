package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import static net.kdt.pojavlaunch.Tools.runMethodbyReflection;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.movtery.anim.AnimPlayer;
import com.movtery.anim.animations.Animations;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.context.ContextExecutor;
import com.movtery.zalithlauncher.databinding.ActivityGameBinding;
import com.movtery.zalithlauncher.databinding.ViewControlMenuBinding;
import com.movtery.zalithlauncher.databinding.ViewGameMenuBinding;
import com.movtery.zalithlauncher.event.value.JvmExitEvent;
import com.movtery.zalithlauncher.feature.MCOptions;
import com.movtery.zalithlauncher.feature.ProfileLanguageSelector;
import com.movtery.zalithlauncher.feature.background.BackgroundManager;
import com.movtery.zalithlauncher.feature.background.BackgroundType;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.feature.version.VersionInfo;
import com.movtery.zalithlauncher.launch.LaunchGame;
import com.movtery.zalithlauncher.listener.SimpleTextWatcher;
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.setting.AllStaticSettings;
import com.movtery.zalithlauncher.task.TaskExecutors;
import com.movtery.zalithlauncher.ui.activity.BaseActivity;
import com.movtery.zalithlauncher.ui.dialog.KeyboardDialog;
import com.movtery.zalithlauncher.ui.subassembly.menu.ControlMenu;
import com.movtery.zalithlauncher.ui.subassembly.view.GameMenuViewWrapper;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.anim.AnimUtils;
import com.movtery.zalithlauncher.utils.file.FileTools;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.movtery.zalithlauncher.utils.stringutils.StringUtils;

import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.customcontrols.keyboard.LwjglCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.customcontrols.mouse.GyroControl;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.services.GameService;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.libsdl.app.SDL;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends BaseActivity implements
        ControlButtonMenuListener,
        EditorExitable,
        ServiceConnection,
        ViewTreeObserver.OnGlobalLayoutListener {

    public static final String INTENT_VERSION = "intent_version";

    public static volatile ClipboardManager GLOBAL_CLIPBOARD;
    public static volatile boolean isInputStackCall;
    public static TouchCharInput touchCharInput;

    protected static View.OnGenericMotionListener motionListener = (v, event) -> false;

    @SuppressLint("StaticFieldLeak")
    private static MainActivity sInstance;

    private final AtomicBoolean launchRequested = new AtomicBoolean(false);
    private final AnimPlayer inputPreviewAnim = new AnimPlayer();

    private ActivityGameBinding binding;
    private GameMenuViewWrapper gameMenuWrapper;
    private GyroControl gyroControl;
    private KeyboardDialog keyboardDialog;
    private Version minecraftVersion;

    private ViewGameMenuBinding gameMenuBinding;
    private ViewControlMenuBinding controlSettingsBinding;
    private GameMenuSettingsController gameMenuSettingsController;

    private boolean isInEditor;
    private boolean isKeyboardVisible;
    private boolean isGameServiceBound;
    private boolean isJvmExiting;

    private SimpleTextWatcher inputWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;

        initSdlIfNeeded();
        resolveVersion();
        configureVersionSettings();
        startAndBindGameService();

        initLayout();
        initWindow();
        initControlMenu();

        inputWatcher = s -> binding.inputPreview.setText(s.toString().trim());
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this);
    }

    private void resolveVersion() {
        minecraftVersion = getIntent().getParcelableExtra(INTENT_VERSION);
        if (minecraftVersion == null) {
            throw new IllegalStateException("The game version is not selected.");
        }
    }

    private void configureVersionSettings() {
        MCOptions.INSTANCE.setup(this, () -> minecraftVersion);

        if (AllSettings.getAutoSetGameLanguage().getValue()) {
            ProfileLanguageSelector.setGameLanguage(
                    minecraftVersion,
                    AllSettings.getGameLanguageOverridden().getValue()
            );
        }
    }

    private void startAndBindGameService() {
        Intent serviceIntent = new Intent(this, GameService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, this, 0);
    }

    private void initSdlIfNeeded() {
        MinecraftGLSurface.sdlEnabled = AllSettings.getGamepadSdlPassthru().getValue();
        if (MinecraftGLSurface.sdlEnabled) {
            Toast.makeText(this, "SDL Enabled", Toast.LENGTH_SHORT).show();
        }

        try {
            SDL.loadLibrary("SDL3", this);
            SDL.loadLibrary("SDL2", this);
            SDL.initialize();
            SDL.setupJNI();
            SDL.setContext(this);
            new SDLSurface(this);

            motionListener = (View.OnGenericMotionListener) runMethodbyReflection(
                    "org.libsdl.app.SDLActivity",
                    "getMotionListener"
            );

            if (AllSettings.getGamepadForcedSdlPassthru().getValue()) {
                Tools.SDL.initializeControllerSubsystems();
            }
        } catch (UnsatisfiedLinkError ignored) {
            // Native SDL libs were not loaded.
        } catch (ReflectiveOperationException e) {
            Tools.showErrorRemote("SDL did not load properly.", e);
        }
    }

    private void initWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(
                AllSettings.getAlternateSurface().getValue() ? null : new ColorDrawable(Color.BLACK)
        );
        window.setSustainedPerformanceMode(AllSettings.getSustainedPerformance().getValue());
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    protected void initLayout() {
        binding = ActivityGameBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        gameMenuWrapper = new GameMenuViewWrapper(this, v -> onClickedMenu(), true);
        touchCharInput = binding.mainTouchCharInput;
        keyboardDialog = new KeyboardDialog(this).setShowSpecialButtons(false);
        gyroControl = new GyroControl(this);

        BackgroundManager.setBackgroundImage(this, BackgroundType.IN_GAME, binding.backgroundView, null);

        binding.mainControlLayout.setMenuListener(this);
        binding.mainDrawerOptions.setScrimColor(Color.TRANSPARENT);
        binding.mainDrawerOptions.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        CallbackBridge.addGrabListener(binding.mainTouchpad);
        CallbackBridge.addGrabListener(binding.mainGameRenderView);

        try {
            initGameEnvironment();
            initRenderCallbacks();
            initGameTip();
        } catch (Throwable e) {
            Tools.showError(this, e, true);
        }
    }

    private void initGameEnvironment() throws Throwable {
        File latestLogFile = new File(PathManager.DIR_GAME_HOME, "latestlog.txt");
        if (!latestLogFile.exists() && !latestLogFile.createNewFile()) {
            throw new IOException("Failed to create a new log file");
        }

        Logger.begin(latestLogFile.getAbsolutePath());
        GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        binding.mainTouchCharInput.setCharacterSender(new LwjglCharSender());

        Logging.i("RdrDebug", "__P_renderer=" + minecraftVersion.getRenderer());
        Renderers.INSTANCE.setCurrentRenderer(this, minecraftVersion.getRenderer(), false);
        DriverPluginManager.setDriverByName(minecraftVersion.getDriver());

        setTitle("Minecraft " + minecraftVersion.getVersionName());

        JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(minecraftVersion);
        isInputStackCall = versionInfo.arguments != null;
        CallbackBridge.nativeSetUseInputStackQueue(isInputStackCall);

        Tools.getDisplayMetrics(this);
        windowWidth = Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, 1f);
        windowHeight = Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, 1f);
    }

    private void initRenderCallbacks() {
        JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(minecraftVersion);

        gameMenuBinding = ViewGameMenuBinding.inflate(getLayoutInflater());
        binding.mainNavigationView.removeAllViews();
        binding.mainNavigationView.addView(gameMenuBinding.getRoot());
        binding.mainDrawerOptions.closeDrawers();

        binding.mainGameRenderView.setSurfaceReadyListener(() -> {
            if (!launchRequested.compareAndSet(false, true)) {
                Logging.w("MainActivity", "Surface ready callback ignored because launch was already requested.");
                return;
            }

            try {
                if (AllSettings.getVirtualMouseStart().getValue()) {
                    binding.mainTouchpad.post(() -> binding.mainTouchpad.switchState());
                }

                LaunchGame.runGame(this, minecraftVersion, versionInfo);
                Logging.i("MainActivity", "LaunchGame.runGame returned");
                GameService.setActive(false);
            } catch (Throwable e) {
                launchRequested.set(false);
                LaunchGame.resetLaunchState();
                Tools.showErrorRemote(e);
            }
        });

        binding.mainGameRenderView.setOnRenderingStartedListener(() -> {
            BackgroundManager.clearBackgroundImage(binding.backgroundView);
            Logging.i(
                    "Rendering Game",
                    "Game rendering has started and the background image was cleared to avoid transparency issues on some devices."
            );
        });

        if (AllSettings.getEnableLogOutput().getValue()) {
            binding.mainLoggerView.setVisibilityWithAnim(true);
        }
    }

    private void initGameTip() {
        String mcInfo = "";
        VersionInfo versionInfo = minecraftVersion.getVersionInfo();
        if (versionInfo != null) {
            mcInfo = versionInfo.getInfoString();
        }

        String tipString = StringUtils.insertNewline(
                binding.gameTip.getText(),
                StringUtils.insertSpace(getString(R.string.game_tip_version), minecraftVersion.getVersionName())
        );

        if (!mcInfo.isEmpty()) {
            tipString = StringUtils.insertNewline(
                    tipString,
                    StringUtils.insertSpace(getString(R.string.game_tip_mc_info), mcInfo)
            );
        }

        binding.gameTip.setText(tipString);
        AnimUtils.setVisibilityAnim(binding.gameTip, 1000, true, 300, new AnimUtils.AnimationListener() {
            @Override
            public void onStart() {
            }

            @Override
            public void onEnd() {
                AnimUtils.setVisibilityAnim(binding.gameTip, 15000, false, 300, null);
            }
        });
    }

    private void initControlMenu() {
        ControlLayout controlLayout = binding.mainControlLayout;

        controlSettingsBinding = ViewControlMenuBinding.inflate(getLayoutInflater());
        new ControlMenu(this, this, controlSettingsBinding, controlLayout, false);
        controlSettingsBinding.saveAndExport.setVisibility(View.GONE);
        binding.mainControlLayout.setModifiable(false);

        gameMenuSettingsController = new GameMenuSettingsController(
                this,
                binding,
                gameMenuBinding,
                controlSettingsBinding,
                keyboardDialog,
                gameMenuWrapper,
                minecraftVersion,
                gyroControl,
                new GameMenuSettingsController.EditorState() {
                    @Override
                    public void setInEditor(boolean inEditor) {
                        isInEditor = inEditor;
                    }

                    @Override
                    public boolean isInEditor() {
                        return isInEditor;
                    }
                }
        );

        binding.mainDrawerOptions.addDrawerListener(gameMenuSettingsController);
    }

    private void loadControls() {
        try {
            binding.mainControlLayout.loadLayout(minecraftVersion.getControl());
        } catch (IOException e) {
            try {
                Logging.w("MainActivity", "Unable to load the control file, loading default controls instead.", e);
                binding.mainControlLayout.loadLayout((String) null);
            } catch (IOException ioException) {
                Tools.showError(this, ioException);
            }
        } catch (Throwable th) {
            Tools.showError(this, th);
        }

        gameMenuWrapper.setVisibility(!binding.mainControlLayout.hasMenuButton());
        binding.mainControlLayout.toggleControlVisible();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
        loadControls();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (AllStaticSettings.enableGyro) {
            gyroControl.enable();
        }

        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
    }

    @Override
    protected void onPause() {
        gyroControl.disable();

        if (!isJvmExiting && GameService.isActive() && CallbackBridge.isGrabbing()) {
            sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
        }

        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
        super.onPause();
    }
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onJvmExit(JvmExitEvent event) {
        Logging.i("MainActivity", "JvmExitEvent received, exitCode=" + event.getExitCode());

        isJvmExiting = true;
        launchRequested.set(false);
        LaunchGame.resetLaunchState();

        runOnUiThread(() -> {
            GameService.setActive(false);
            stopService(new Intent(this, GameService.class));

            if (AllSettings.getQuitLauncher().getValue()) {
                ZHTools.killProcess();
            } else {
                finish();
            }
        });
    }

    private void clearWindowAttribs() {
        try {
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
            CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        } catch (Throwable ignored) {
        }
    }

    private void cleanupRenderUiState() {
        if (binding == null) {
            return;
        }

        try {
            if (binding.mainGameRenderView != null) {
                binding.mainGameRenderView.setSurfaceReadyListener(null);
                binding.mainGameRenderView.setOnRenderingStartedListener(null);
            }

            CallbackBridge.removeGrabListener(binding.mainTouchpad);
            CallbackBridge.removeGrabListener(binding.mainGameRenderView);

            if (binding.mainDrawerOptions != null) {
                binding.mainDrawerOptions.closeDrawers();
            }
        } catch (Throwable t) {
            Logging.w("MainActivity", "Failed to clean UI/render state", t);
        }
    }

    private void cleanupGameService() {
        if (isGameServiceBound) {
            try {
                unbindService(this);
            } catch (IllegalArgumentException ignored) {
            }
            isGameServiceBound = false;
        }

        GameService.setActive(false);
        stopService(new Intent(this, GameService.class));
    }

    private String getCurrentProcessNameCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }

        try {
            int pid = android.os.Process.myPid();
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

            if (am != null) {
                for (ActivityManager.RunningAppProcessInfo proc : am.getRunningAppProcesses()) {
                    if (proc != null && proc.pid == pid) {
                        return proc.processName;
                    }
                }
            }
        } catch (Throwable t) {
            Logging.w("MainActivity", "Failed to resolve current process name", t);
        }

        return getPackageName();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, hasFocus ? 1 : 0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
    }

    @Override
    protected void onStop() {
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        launchRequested.set(false);
        LaunchGame.resetLaunchState();

        if (gameMenuSettingsController != null) {
            gameMenuSettingsController.closeSpinner();
        }

        cleanupGameService();
        cleanupRenderUiState();

        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(this);
        ContextExecutor.clearActivity();

        GLOBAL_CLIPBOARD = null;
        touchCharInput = null;
        sInstance = null;
        binding = null;

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        gyroControl.updateOrientation();
        Tools.updateWindowSize(this);
        binding.mainGameRenderView.refreshSize();
        runOnUiThread(() -> binding.mainControlLayout.refreshControlButtonPositions());
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        TaskExecutors.getUIHandler().postDelayed(() -> binding.mainGameRenderView.refreshSize(), 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            try {
                binding.mainControlLayout.loadLayout((String) null);
            } catch (IOException e) {
                Logging.e("LoadLayout", Tools.printToString(e));
            }
        }
    }

    @Override
    public boolean shouldIgnoreNotch() {
        return AllSettings.getIgnoreNotch().getValue();
    }

    @Override
    public void onGlobalLayout() {
        Rect rect = new Rect();
        View decorView = getWindow().getDecorView();
        decorView.getWindowVisibleDisplayFrame(rect);

        int screenHeight = decorView.getHeight();
        if (screenHeight * 2 / 3 > rect.bottom) {
            if (!isKeyboardVisible) {
                binding.mainTouchCharInput.addTextChangedListener(inputWatcher);
                setInputPreview(true);
            }
            isKeyboardVisible = true;
        } else if (isKeyboardVisible) {
            binding.mainTouchCharInput.removeTextChangedListener(inputWatcher);
            setInputPreview(false);
            isKeyboardVisible = false;
        }
    }

    private void setInputPreview(boolean show) {
        inputPreviewAnim.clearEntries();
        inputPreviewAnim.apply(new AnimPlayer.Entry(
                        binding.inputPreviewLayout,
                        show ? Animations.FadeIn : Animations.FadeOut
                ))
                .setOnStart(() -> binding.inputPreviewLayout.setVisibility(View.VISIBLE))
                .setOnEnd(() -> binding.inputPreviewLayout.setVisibility(show ? View.VISIBLE : View.GONE))
                .start();
    }

    @Override
    public void onClickedMenu() {
        DrawerLayout drawerLayout = binding.mainDrawerOptions;
        View navigationView = binding.mainNavigationView;

        if (drawerLayout.isDrawerOpen(navigationView)) {
            drawerLayout.closeDrawer(navigationView);
        } else {
            drawerLayout.openDrawer(navigationView);
        }

        navigationView.requestLayout();
    }

    @Override
    public void exitEditor() {
        try {
            binding.mainControlLayout.loadLayout((CustomControls) null);
            binding.mainControlLayout.setModifiable(false);
            System.gc();
            binding.mainControlLayout.loadLayout(minecraftVersion.getControl());
            gameMenuWrapper.setVisibility(!binding.mainControlLayout.hasMenuButton());
        } catch (IOException e) {
            Tools.showError(this, e);
        }

        binding.mainNavigationView.removeAllViews();
        binding.mainNavigationView.addView(gameMenuBinding.getRoot());
        isInEditor = false;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        isGameServiceBound = true;
        binding.mainGameRenderView.start(GameService.isActive(), binding.mainTouchpad);
        GameService.setActive(true);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        isGameServiceBound = false;
    }

    private boolean isCapturedPointerEvent(MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_MOUSE_RELATIVE) != 0
                || (source & InputDevice.SOURCE_MOUSE) != 0;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        if (isCapturedPointerEvent(event)) {
            return binding.mainGameRenderView.dispatchCapturedPointerEvent(event);
        }
        return super.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isInEditor) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    binding.mainControlLayout.askToExit(this);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        boolean handled = binding.mainGameRenderView.processKeyEvent(event);
        if (!handled
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && !binding.mainTouchCharInput.isEnabled()) {
            if (event.getAction() != KeyEvent.ACTION_UP) {
                return true;
            }
            sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
            return true;
        }

        return handled;
    }

    public static void toggleMouse(Context context) {
        MainActivity activity = sInstance;
        if (activity == null || activity.binding == null || CallbackBridge.isGrabbing()) {
            return;
        }

        Toast.makeText(
                context,
                activity.binding.mainTouchpad.switchState()
                        ? R.string.control_mouseon
                        : R.string.control_mouseoff,
                Toast.LENGTH_SHORT
        ).show();
    }

    public static void switchKeyboardState() {
        MainActivity activity = sInstance;
        if (activity != null && activity.binding != null) {
            activity.binding.mainTouchCharInput.switchKeyboardState();
        }
    }

    public static void openLink(String link) {
        MainActivity activity = sInstance;
        if (activity == null || activity.binding == null) {
            return;
        }

        Context context = activity.binding.mainTouchpad.getContext();
        ((Activity) context).runOnUiThread(() -> {
            try {
                openUriOrShareFile(context, link);
            } catch (Throwable th) {
                Tools.showError(context, th);
            }
        });
    }

    private static void openUriOrShareFile(Context context, String input) {
        if (input.startsWith("file:")) {
            int truncLength = input.startsWith("file://") ? 7 : 5;
            input = input.substring(truncLength);
            Logging.i("MainActivity", input);

            File inputFile = new File(input);
            FileTools.shareFile(context, inputFile);
            Logging.i("In-game Share File/Folder", "Start!");
            return;
        }

        ZHTools.openLink(context, input, "*/*");
    }

    public static void querySystemClipboard() {
        TaskExecutors.runInUIThread(() -> {
            if (GLOBAL_CLIPBOARD == null) {
                AWTInputBridge.nativeClipboardReceived(null, null);
                return;
            }

            ClipData clipData = GLOBAL_CLIPBOARD.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                AWTInputBridge.nativeClipboardReceived(null, null);
                return;
            }

            CharSequence text = clipData.getItemAt(0).getText();
            if (text == null) {
                AWTInputBridge.nativeClipboardReceived(null, null);
                return;
            }

            AWTInputBridge.nativeClipboardReceived(text.toString(), "plain");
        });
    }

    public static void putClipboardData(String data, String mimeType) {
        TaskExecutors.runInUIThread(() -> {
            if (GLOBAL_CLIPBOARD == null) {
                return;
            }

            ClipData clipData = null;
            switch (mimeType) {
                case "text/plain":
                    clipData = ClipData.newPlainText("AWT Paste", data);
                    break;
                case "text/html":
                    clipData = ClipData.newHtmlText("AWT Paste", data, data);
                    break;
                default:
                    break;
            }

            if (clipData != null) {
                GLOBAL_CLIPBOARD.setPrimaryClip(clipData);
            }
        });
    }

    public ActivityGameBinding getBinding() {
        return binding;
    }

    public ViewGameMenuBinding getGameMenuBinding() {
        return gameMenuBinding;
    }

    public ViewControlMenuBinding getControlSettingsBinding() {
        return controlSettingsBinding;
    }

    public GameMenuViewWrapper getGameMenuWrapper() {
        return gameMenuWrapper;
    }

    public KeyboardDialog getKeyboardDialog() {
        return keyboardDialog;
    }

    public GyroControl getGyroControl() {
        return gyroControl;
    }

    public Version getMinecraftVersion() {
        return minecraftVersion;
    }
}