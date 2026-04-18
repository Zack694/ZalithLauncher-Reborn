package net.kdt.pojavlaunch;

import static com.movtery.zalithlauncher.launch.LaunchGame.preLaunch;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.palette.graphics.Palette;

import com.kdt.mcgui.ProgressLayout;
import com.movtery.anim.AnimPlayer;
import com.movtery.anim.animations.Animations;
import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.context.ContextExecutor;
import com.movtery.zalithlauncher.databinding.ActivityLauncherBinding;
import com.movtery.zalithlauncher.event.single.LaunchGameEvent;
import com.movtery.zalithlauncher.event.single.MainBackgroundChangeEvent;
import com.movtery.zalithlauncher.event.single.PageOpacityChangeEvent;
import com.movtery.zalithlauncher.event.single.SwapToLoginEvent;
import com.movtery.zalithlauncher.event.sticky.MinecraftVersionValueEvent;
import com.movtery.zalithlauncher.event.value.AddFragmentEvent;
import com.movtery.zalithlauncher.event.value.DownloadProgressKeyEvent;
import com.movtery.zalithlauncher.event.value.InstallGameEvent;
import com.movtery.zalithlauncher.event.value.InstallLocalModpackEvent;
import com.movtery.zalithlauncher.event.value.LocalLoginEvent;
import com.movtery.zalithlauncher.event.value.MicrosoftLoginEvent;
import com.movtery.zalithlauncher.event.value.OtherLoginEvent;
import com.movtery.zalithlauncher.feature.accounts.AccountType;
import com.movtery.zalithlauncher.feature.accounts.AccountsManager;
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils;
import com.movtery.zalithlauncher.feature.background.BackgroundManager;
import com.movtery.zalithlauncher.feature.background.BackgroundType;
import com.movtery.zalithlauncher.feature.download.item.ModLoaderWrapper;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.mod.modpack.install.InstallExtra;
import com.movtery.zalithlauncher.feature.mod.modpack.install.InstallLocalModPack;
import com.movtery.zalithlauncher.feature.mod.modpack.install.ModPackInfo;
import com.movtery.zalithlauncher.feature.mod.modpack.install.ModPackUtils;
import com.movtery.zalithlauncher.feature.notice.CheckNewNotice;
import com.movtery.zalithlauncher.feature.notice.NoticeInfo;
import com.movtery.zalithlauncher.feature.update.UpdateUtils;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.feature.version.VersionsManager;
import com.movtery.zalithlauncher.feature.version.install.GameInstaller;
import com.movtery.zalithlauncher.feature.version.install.InstallTask;
import com.movtery.zalithlauncher.plugins.PluginLoader;
import com.movtery.zalithlauncher.plugins.renderer.RendererPlugin;
import com.movtery.zalithlauncher.plugins.renderer.RendererPluginManager;
import com.movtery.zalithlauncher.renderer.RendererInterface;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.task.TaskExecutors;
import com.movtery.zalithlauncher.ui.activity.BaseActivity;
import com.movtery.zalithlauncher.ui.activity.ErrorActivity;
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog;
import com.movtery.zalithlauncher.ui.dialog.TipDialog;
import com.movtery.zalithlauncher.ui.fragment.AccountFragment;
import com.movtery.zalithlauncher.ui.fragment.BaseFragment;
import com.movtery.zalithlauncher.ui.fragment.DownloadFragment;
import com.movtery.zalithlauncher.ui.fragment.DownloadModFragment;
import com.movtery.zalithlauncher.ui.fragment.SettingsFragment;
import com.movtery.zalithlauncher.ui.subassembly.settingsbutton.ButtonType;
import com.movtery.zalithlauncher.ui.subassembly.settingsbutton.SettingsButtonWrapper;
import com.movtery.zalithlauncher.ui.subassembly.view.DraggableViewWrapper;
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.anim.ViewAnimUtils;
import com.movtery.zalithlauncher.utils.file.FileTools;
import com.movtery.zalithlauncher.utils.image.ImageUtils;
import com.movtery.zalithlauncher.utils.stringutils.ShiftDirection;
import com.movtery.zalithlauncher.utils.stringutils.StringUtils;

import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.services.ProgressServiceKeeper;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.utils.NotificationUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LauncherActivity extends BaseActivity {

    private static final String TAG = "LauncherActivity";
    private static final String FALLBACK_COMPATIBLE_RENDERER_NAME = "a compatible renderer";
    private static final String MOBILE_GLUES_URL =
            "https://github.com/MobileGL-Dev/MobileGlues-release/releases";
    private static final String VULKAN_ZINK_RENDERER_ID = "vulkan_zink";
    private static final String VULKAN_ZINK_RENDERER_NAME = "Vulkan Zink";
    private static final String VULKAN_ZINK_RENDERER_UUID = "0fa435e2-46df-45c9-906c-b29606aaef00";
    private static final Pattern LEGACY_RELEASE_VERSION_PATTERN =
            Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern MODERN_RELEASE_VERSION_PATTERN =
            Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?.*$");

    private final AnimPlayer noticeAnimPlayer = new AnimPlayer();

    private ActivityLauncherBinding binding;
    private SettingsButtonWrapper settingsButtonWrapper;
    private ProgressServiceKeeper progressServiceKeeper;
    private NotificationManager notificationManager;
    private Future<?> checkNoticeTask;

    private ActivityResultLauncher<String> requestNotificationPermissionLauncher;
    private WeakReference<Runnable> requestNotificationPermissionRunnable;

    public final ActivityResultLauncher<Object> modInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), uris -> {
                if (uris != null) {
                    Tools.launchModInstaller(this, uris.get(0));
                }
            });

    private final FragmentManager.FragmentLifecycleCallbacks fragmentCallbackListener =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                    if (fragment instanceof MainMenuFragment) {
                        settingsButtonWrapper.setButtonType(ButtonType.SETTINGS);
                    } else {
                        settingsButtonWrapper.setButtonType(ButtonType.HOME);
                    }
                }
            };

    private final TaskCountListener doubleLaunchPreventionListener = taskCount -> {
        if (taskCount > 0) {
            TaskExecutors.runInUIThread(() ->
                    notificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START));
        }
    };

    @Subscribe
    public void event(PageOpacityChangeEvent event) {
        setPageOpacity(event.getProgress());
    }

    @Subscribe
    public void event(MainBackgroundChangeEvent event) {
        refreshBackground();
        setPageOpacity(AllSettings.getPageOpacity().getValue());
    }

    @Subscribe
    public void event(SwapToLoginEvent event) {
        Fragment currentFragment = getCurrentFragment();

        if (currentFragment == null || getVisibleFragment(AccountFragment.TAG) != null) {
            return;
        }

        ZHTools.swapFragmentWithAnim(currentFragment, AccountFragment.class, AccountFragment.TAG, null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(LaunchGameEvent event) {
        if (binding.progressLayout.hasProcesses()) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }

        Version version = VersionsManager.INSTANCE.getCurrentVersion();
        if (version == null) {
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return;
        }

        if (AccountsManager.INSTANCE.getAllAccounts().isEmpty()) {
            Toast.makeText(this, R.string.account_no_saved_accounts, Toast.LENGTH_LONG).show();
            EventBus.getDefault().post(new SwapToLoginEvent());
            return;
        }

        String rendererValue = getEffectiveRendererValue(version);
        RendererPlugin rendererPlugin =
                RendererPluginManager.getConfigurablePluginOrNull(rendererValue);

        if (rendererPlugin != null) {
            StoragePermissionsUtils.ensurePermissions(
                    this,
                    R.string.generic_warning,
                    getString(
                            R.string.permissions_storage_for_renderer_config,
                            rendererPlugin.getDisplayName(),
                            InfoDistributor.APP_NAME
                    ),
                    new StoragePermissionsUtils.PermissionResult() {
                        @Override
                        public void onGranted() {
                            launchGame(version);
                        }

                        @Override
                        public void onCancelled() {
                            launchGame(version);
                        }
                    }
            );
            return;
        }

        launchGame(version);
    }

    @Subscribe
    public void event(MicrosoftLoginEvent event) {
        new MicrosoftBackgroundLogin(false, event.getUri().getQueryParameter("code")).performLogin(
                this,
                null,
                AccountsManager.INSTANCE.getDoneListener(),
                AccountsManager.INSTANCE.getErrorListener()
        );
    }

    @Subscribe
    public void event(OtherLoginEvent event) {
        Task.runTask(() -> {
                    event.getAccount().save();
                    Logging.i("Account", "Saved account: " + event.getAccount().username);
                    return null;
                })
                .onThrowable(e -> Logging.e("Account", "Failed to save account: " + e))
                .finallyTask(() -> AccountsManager.INSTANCE.getDoneListener().onLoginDone(event.getAccount()))
                .execute();
    }

    @Subscribe
    public void event(LocalLoginEvent event) {
        String userName = event.getUserName();
        MinecraftAccount localAccount = new MinecraftAccount();
        localAccount.username = userName;
        localAccount.accountType = AccountType.LOCAL.getType();

        try {
            localAccount.save();
            Logging.i("Account", "Saved account: " + localAccount.username);
        } catch (IOException e) {
            Logging.e("Account", "Failed to save account: " + e);
        }

        AccountsManager.INSTANCE.getDoneListener().onLoginDone(localAccount);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(InstallLocalModpackEvent event) {
        InstallExtra installExtra = event.getInstallExtra();
        if (!installExtra.startInstall) {
            return;
        }

        if (binding.progressLayout.hasProcesses()) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }

        File modpackFile = new File(installExtra.modpackPath);
        ModPackInfo info = ModPackUtils.determineModpack(modpackFile);

        if (info.getType() == ModPackUtils.ModPackEnum.UNKNOWN) {
            InstallLocalModPack.showUnSupportDialog(this);
        }

        String modPackName = info.getName() != null
                ? info.getName()
                : FileTools.getFileNameWithoutExtension(modpackFile);

        new EditTextDialog.Builder(this)
                .setTitle(R.string.version_install_new)
                .setEditText(modPackName)
                .setAsRequired()
                .setConfirmListener((editText, checked) -> {
                    String customName = editText.getText().toString();

                    if (FileTools.isFilenameInvalid(editText)) {
                        return false;
                    }

                    if (VersionsManager.INSTANCE.isVersionExists(customName, true)) {
                        editText.setError(getString(R.string.version_install_exists));
                        return false;
                    }

                    Task.runTask(() -> {
                                ModLoaderWrapper modLoaderWrapper =
                                        InstallLocalModPack.installModPack(
                                                this,
                                                info.getType(),
                                                modpackFile,
                                                customName
                                        );

                                if (modLoaderWrapper != null) {
                                    InstallTask downloadTask = modLoaderWrapper.getDownloadTask();

                                    if (downloadTask != null) {
                                        runOnUiThread(() -> Toast.makeText(
                                                this,
                                                getString(R.string.modpack_prepare_mod_loader_installation),
                                                Toast.LENGTH_SHORT
                                        ).show());

                                        Logging.i(
                                                "Install Version",
                                                "Installing ModLoader: " + modLoaderWrapper.getModLoaderVersion()
                                        );

                                        File file = downloadTask.run(customName);
                                        if (file != null) {
                                            return new kotlin.Pair<>(modLoaderWrapper, file);
                                        }
                                    }
                                }

                                return null;
                            })
                            .beforeStart(
                                    TaskExecutors.getAndroidUI(),
                                    () -> ProgressLayout.setProgress(
                                            ProgressLayout.INSTALL_RESOURCE,
                                            0,
                                            R.string.generic_waiting
                                    )
                            )
                            .ended(filePair -> {
                                if (filePair != null) {
                                    try {
                                        ModPackUtils.startModLoaderInstall(
                                                filePair.getFirst(),
                                                LauncherActivity.this,
                                                filePair.getSecond(),
                                                customName
                                        );
                                    } catch (Throwable e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            })
                            .onThrowable(
                                    TaskExecutors.getAndroidUI(),
                                    e -> Tools.showErrorRemote(
                                            this,
                                            R.string.modpack_install_download_failed,
                                            e
                                    )
                            )
                            .finallyTask(
                                    TaskExecutors.getAndroidUI(),
                                    () -> ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)
                            )
                            .execute();

                    return true;
                })
                .showDialog();
    }

    @Subscribe
    public void event(InstallGameEvent event) {
        new GameInstaller(this, event).installGame();
    }

    @Subscribe
    public void event(DownloadProgressKeyEvent event) {
        if (event.getObserve()) {
            binding.progressLayout.observe(event.getProgressKey());
        } else {
            binding.progressLayout.unObserve(event.getProgressKey());
        }
    }

    @Subscribe
    public synchronized void event(AddFragmentEvent event) {
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment == null) {
            return;
        }

        try {
            AddFragmentEvent.FragmentActivityCallBack activityCallBack =
                    event.getFragmentActivityCallback();

            if (activityCallBack != null) {
                activityCallBack.callBack(currentFragment.requireActivity());
            }

            ZHTools.addFragment(
                    currentFragment,
                    event.getFragmentClass(),
                    event.getFragmentTag(),
                    event.getBundle()
            );
        } catch (Exception e) {
            Logging.e(TAG, "Failed to open a new fragment.", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupFragments();
        setupViews();
        setupNotificationPermissionLauncher();
        checkNotificationPermission();

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        registerProgressListeners();
        loadVersionList();
        checkNotice();
        checkDownloadedPackagesAndUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PluginLoader.refreshAllPlugins(this);
        Renderers.reloadRenderers(this, AllSettings.getRenderer().getValue(), true);
        setPageOpacity(AllSettings.getPageOpacity().getValue());
        VersionsManager.INSTANCE.refresh("LauncherActivity:onResume", false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                fragmentCallbackListener,
                true
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        binding.progressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(binding.progressLayout);
        ProgressKeeper.removeTaskCountListener(progressServiceKeeper);

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(fragmentCallbackListener);
        ContextExecutor.clearActivity();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
    }

    private void setupNotificationPermissionLauncher() {
        requestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if (!isAllowed) {
                        handleNoNotificationPermission();
                        return;
                    }

                    Runnable runnable = Tools.getWeakReference(requestNotificationPermissionRunnable);
                    if (runnable != null) {
                        runnable.run();
                    }
                }
        );
    }

    private void registerProgressListeners() {
        ProgressKeeper.addTaskCountListener(doubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener(
                progressServiceKeeper = new ProgressServiceKeeper(this)
        );
        ProgressKeeper.addTaskCountListener(binding.progressLayout);
    }

    private void loadVersionList() {
        new AsyncVersionList().getVersionList(
                versions -> EventBus.getDefault().postSticky(
                        new MinecraftVersionValueEvent(versions)
                ),
                false
        );
    }

    private void checkDownloadedPackagesAndUpdates() {
        Task.runTask(() -> {
            UpdateUtils.checkDownloadedPackage(this, false, true);
            return null;
        }).execute();
    }

    private void setupFragments() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Fragment currentFragment = getCurrentFragment();

                if (currentFragment instanceof BaseFragment
                        && !((BaseFragment) currentFragment).onBackPressed()) {
                    return;
                }

                if (getSupportFragmentManager().getBackStackEntryCount() <= 1) {
                    finish();
                } else {
                    getSupportFragmentManager().popBackStackImmediate();
                }
            }
        });

        FragmentManager fragmentManager = getSupportFragmentManager();

        if (fragmentManager.getBackStackEntryCount() < 1) {
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addToBackStack(MainMenuFragment.TAG)
                    .add(
                            R.id.container_fragment,
                            MainMenuFragment.class,
                            null,
                            MainMenuFragment.TAG
                    )
                    .commit();
        }
    }

    private void setupViews() {
        refreshBackground();
        setPageOpacity(AllSettings.getPageOpacity().getValue());

        settingsButtonWrapper = new SettingsButtonWrapper(binding.settingButton);
        settingsButtonWrapper.setOnTypeChangeListener(
                type -> ViewAnimUtils.setViewAnim(binding.settingButton, Animations.Pulse)
        );

        binding.downloadButton.setOnClickListener(v -> openDownloadFragment());
        binding.settingButton.setOnClickListener(v -> handleSettingsButtonClick());

        binding.appTitleText.setText(InfoDistributor.APP_NAME);
        binding.appTitleText.setOnClickListener(v -> shiftAppTitle());

        observeProgressKeys();
        setupNoticeUI();
        setupNoticeDrag();

        binding.hair.setVisibility(ZHTools.checkDate(4, 1) ? View.VISIBLE : View.GONE);
    }

    private void openDownloadFragment() {
        Fragment fragment = getCurrentFragment();
        if (fragment == null) {
            return;
        }

        if (!(fragment instanceof DownloadFragment || fragment instanceof DownloadModFragment)) {
            ViewAnimUtils.setViewAnim(binding.downloadButton, Animations.Pulse);
            ZHTools.swapFragmentWithAnim(fragment, DownloadFragment.class, DownloadFragment.TAG, null);
        }
    }

    private void handleSettingsButtonClick() {
        ViewAnimUtils.setViewAnim(binding.settingButton, Animations.Pulse);

        Fragment fragment = getCurrentFragment();
        if (fragment instanceof MainMenuFragment) {
            ZHTools.swapFragmentWithAnim(fragment, SettingsFragment.class, SettingsFragment.TAG, null);
        } else {
            Tools.backToMainMenu(this);
        }
    }

    private void shiftAppTitle() {
        String shiftedString = StringUtils.shiftString(
                binding.appTitleText.getText().toString(),
                ShiftDirection.RIGHT,
                1
        );

        if (new Random().nextInt(100) < 20 && shiftedString.equals(InfoDistributor.APP_NAME)) {
            ErrorActivity.showEasterEgg(this);
            return;
        }

        binding.appTitleText.setText(shiftedString);
    }

    private void observeProgressKeys() {
        binding.progressLayout.observe(ProgressLayout.DOWNLOAD_MINECRAFT);
        binding.progressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        binding.progressLayout.observe(ProgressLayout.INSTALL_RESOURCE);
        binding.progressLayout.observe(ProgressLayout.LOGIN_ACCOUNT);
        binding.progressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
        binding.progressLayout.observe(ProgressLayout.CHECKING_MODS);
    }

    private void setupNoticeUI() {
        binding.noticeGotButton.setOnClickListener(v -> {
            setNotice(false);
            AllSettings.getNoticeDefault().put(false).save();
        });
    }

    private void setupNoticeDrag() {
        new DraggableViewWrapper(binding.noticeLayout, new DraggableViewWrapper.AttributesFetcher() {
            @NonNull
            @Override
            public DraggableViewWrapper.ScreenPixels getScreenPixels() {
                return new DraggableViewWrapper.ScreenPixels(
                        0,
                        0,
                        currentDisplayMetrics.widthPixels - binding.noticeLayout.getWidth(),
                        currentDisplayMetrics.heightPixels - binding.noticeLayout.getHeight()
                );
            }

            @NonNull
            @Override
            public int[] get() {
                return new int[]{
                        (int) binding.noticeLayout.getX(),
                        (int) binding.noticeLayout.getY()
                };
            }

            @Override
            public void set(int x, int y) {
                binding.noticeLayout.setX(x);
                binding.noticeLayout.setY(y);
            }
        }).init();
    }

    private void launchGame(Version version) {
        LocalAccountUtils.checkUsageAllowed(new LocalAccountUtils.CheckResultListener() {
            @Override
            public void onUsageAllowed() {
                continueLaunchIfModernRendererReady(version);
            }

            @Override
            public void onUsageDenied() {
                if (!AllSettings.getLocalAccountReminders().getValue()) {
                    continueLaunchIfModernRendererReady(version);
                    return;
                }

                LocalAccountUtils.openDialog(
                        LauncherActivity.this,
                        checked -> {
                            LocalAccountUtils.saveReminders(checked);
                            continueLaunchIfModernRendererReady(version);
                        },
                        getString(R.string.account_no_microsoft_account)
                                + getString(R.string.account_purchase_minecraft_account_tip),
                        R.string.account_continue_to_launch_the_game
                );
            }
        });
    }

    private void continueLaunchIfModernRendererReady(Version version) {
        PluginLoader.refreshAllPlugins(this);
        Renderers.reloadRenderers(this, getEffectiveRendererValue(version), false);

        if (!checkModernRendererRequirement(version)) {
            return;
        }

        preLaunch(LauncherActivity.this, version);
    }

    private boolean checkModernRendererRequirement(Version version) {
        boolean is117OrNewer = isVersionAtLeast17(version);

        Logger.appendToLog(
                "Modern renderer gate [LauncherActivity]: versionRenderer="
                        + version.getRenderer()
                        + " settingsRenderer=" + AllSettings.getRenderer().getValue()
                        + " effectiveRenderer=" + getEffectiveRendererValue(version)
                        + " is117OrNewer=" + is117OrNewer
        );

        if (!is117OrNewer) {
            return true;
        }

        if (isAllowedRendererSelectedFor117Plus(version)) {
            Logger.appendToLog(
                    "Modern renderer gate [LauncherActivity]: allowed 1.17+ renderer selected"
            );
            return true;
        }

        if (hasAllowedRendererAvailableFor117Plus()) {
            showModernRendererSelectionDialog();
        } else {
            showModernRendererInstallDialog();
        }
        return false;
    }
    private boolean isVersionAtLeast17(Version version) {
        for (String candidate : buildVersionCandidates(version)) {
            Boolean parsed = parseIsVersionAtLeast17(candidate);
            if (parsed != null) {
                Logger.appendToLog(
                        "Modern renderer gate [LauncherActivity]: parsed version candidate '"
                                + candidate + "' => is117OrNewer=" + parsed
                );
                return parsed;
            }
        }

        Logger.appendToLog(
                "Modern renderer gate [LauncherActivity]: unable to confidently parse version, treating as below 1.17"
        );
        return false;
    }

    private String[] buildVersionCandidates(Version version) {
        java.util.List<String> candidates = new java.util.ArrayList<>();

        if (version != null) {
            try {
                JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(version);
                if (versionInfo != null && versionInfo.id != null && !versionInfo.id.trim().isEmpty()) {
                    candidates.add(versionInfo.id.trim());
                }
            } catch (Throwable ignored) {
            }

            String versionName = version.getVersionName();
            if (versionName != null && !versionName.trim().isEmpty()) {
                candidates.add(versionName.trim());
            }
        }

        return candidates.toArray(new String[0]);
    }



    private Boolean parseIsVersionAtLeast17(String rawValue) {
        String normalized = safeTrim(rawValue);
        if (normalized.isEmpty()) {
            return null;
        }

        Matcher legacyExactMatcher = LEGACY_RELEASE_VERSION_PATTERN.matcher(normalized);
        if (legacyExactMatcher.matches()) {
            return evaluateLegacyIsVersionAtLeast17(legacyExactMatcher.group(1));
        }

        Matcher modernExactMatcher = MODERN_RELEASE_VERSION_PATTERN.matcher(normalized);
        if (modernExactMatcher.matches()) {
            return evaluateModernIsVersionAtLeast17(modernExactMatcher.group(1));
        }

        Matcher legacyEmbeddedMatcher =
                Pattern.compile("(^|[^0-9])1\\.(\\d+)(?:\\.(\\d+))?([^0-9]|$)").matcher(normalized);
        if (legacyEmbeddedMatcher.find()) {
            return evaluateLegacyIsVersionAtLeast17(legacyEmbeddedMatcher.group(2));
        }

        Matcher modernEmbeddedMatcher =
                Pattern.compile("(^|[^0-9])(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?([^0-9]|$)").matcher(normalized);
        if (modernEmbeddedMatcher.find()) {
            return evaluateModernIsVersionAtLeast17(modernEmbeddedMatcher.group(2));
        }

        return null;
    }

    private boolean evaluateLegacyIsVersionAtLeast17(String minorGroup) {
        try {
            return Integer.parseInt(minorGroup) >= 17;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean evaluateModernIsVersionAtLeast17(String majorGroup) {
        try {
            return Integer.parseInt(majorGroup) >= 17;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String getEffectiveRendererValue(Version version) {
        if (version != null) {
            String versionRenderer = version.getRenderer();
            if (versionRenderer != null && !versionRenderer.trim().isEmpty()) {
                return versionRenderer.trim();
            }
        }

        String settingsRenderer = AllSettings.getRenderer().getValue();
        if (settingsRenderer != null && !settingsRenderer.trim().isEmpty()) {
            return settingsRenderer.trim();
        }

        return "";
    }

    private boolean isAllowedRendererSelectedFor117Plus(Version version) {
        String rendererValue = getEffectiveRendererValue(version);
        if (rendererValue.isEmpty()) {
            return false;
        }

        if (matchesAllowed117PlusRendererValue(rendererValue)) {
            return true;
        }

        try {
            if (Renderers.INSTANCE.isCurrentRendererValid()) {
                RendererInterface currentRenderer = Renderers.INSTANCE.getCurrentRenderer();
                if (matchesBuiltInRenderer(currentRenderer, rendererValue)) {
                    return isAllowedBuiltInRendererFor117Plus(currentRenderer);
                }
            }
        } catch (Throwable t) {
            Logger.appendToLog(
                    "Modern renderer gate [LauncherActivity]: failed to inspect current built-in renderer: " + t
            );
        }

        for (RendererPlugin rendererPlugin : RendererPluginManager.getRendererList()) {
            if (rendererPlugin == null) {
                continue;
            }

            if (!matchesRendererPlugin(rendererPlugin, rendererValue)) {
                continue;
            }

            return isAllowedPluginRendererFor117Plus(rendererPlugin);
        }

        return false;
    }

    private boolean hasAllowedRendererAvailableFor117Plus() {
        if (hasCompatibleVulkanZinkBuiltIn()) {
            return true;
        }

        for (RendererPlugin rendererPlugin : RendererPluginManager.getRendererList()) {
            if (isAllowedPluginRendererFor117Plus(rendererPlugin)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesAllowed117PlusRendererValue(String rendererValue) {
        String lower = safeLower(rendererValue);

        return lower.contains("mobile glues")
                || lower.contains("mobileglues")
                || lower.contains("mobile_glues")
                || lower.contains("ltw")
                || lower.contains("krypton")
                || VULKAN_ZINK_RENDERER_UUID.equalsIgnoreCase(rendererValue)
                || lower.contains("vulkan_zink")
                || lower.contains("vulkan-zink")
                || lower.contains("vulkan zink");
    }

    private boolean matchesBuiltInRenderer(RendererInterface renderer, String rendererValue) {
        if (renderer == null || rendererValue == null || rendererValue.isEmpty()) {
            return false;
        }

        return rendererValue.equals(renderer.getUniqueIdentifier())
                || rendererValue.equals(renderer.getRendererId())
                || rendererValue.equals(renderer.getRendererName());
    }

    private boolean isAllowedBuiltInRendererFor117Plus(RendererInterface renderer) {
        if (renderer == null) {
            return false;
        }

        String rendererId = safeLower(renderer.getRendererId());
        String rendererName = safeLower(renderer.getRendererName());
        String uniqueIdentifier = safeLower(renderer.getUniqueIdentifier());

        return rendererName.contains("mobile glues")
                || uniqueIdentifier.contains("mobileglues")
                || uniqueIdentifier.contains("mobile_glues")
                || rendererId.contains("ltw")
                || rendererName.contains("ltw")
                || uniqueIdentifier.contains("ltw")
                || rendererId.contains("krypton")
                || rendererName.contains("krypton")
                || uniqueIdentifier.contains("krypton")
                || rendererId.contains("vulkan_zink")
                || rendererId.contains("zink")
                || rendererName.contains("vulkan zink")
                || VULKAN_ZINK_RENDERER_UUID.equalsIgnoreCase(renderer.getUniqueIdentifier());
    }

    private boolean isAllowedPluginRendererFor117Plus(RendererPlugin rendererPlugin) {
        if (rendererPlugin == null) {
            return false;
        }

        String displayName = safeLower(rendererPlugin.getDisplayName());
        String uniqueIdentifier = safeLower(rendererPlugin.getUniqueIdentifier());
        String pluginId = safeLower(getRendererPluginId(rendererPlugin));

        return displayName.contains("mobile glues")
                || uniqueIdentifier.contains("mobileglues")
                || uniqueIdentifier.contains("mobile_glues")
                || pluginId.contains("mobileglues")
                || pluginId.contains("mobile_glues")
                || displayName.contains("ltw")
                || uniqueIdentifier.contains("ltw")
                || pluginId.contains("ltw")
                || displayName.contains("krypton")
                || uniqueIdentifier.contains("krypton")
                || pluginId.contains("krypton")
                || displayName.contains("vulkan zink")
                || uniqueIdentifier.contains("vulkan_zink")
                || uniqueIdentifier.contains("vulkan-zink")
                || VULKAN_ZINK_RENDERER_UUID.equalsIgnoreCase(rendererPlugin.getUniqueIdentifier())
                || pluginId.contains("vulkan_zink")
                || pluginId.contains("vulkan-zink")
                || pluginId.contains("zink");
    }

    private boolean hasCompatibleVulkanZinkBuiltIn() {
        return Tools.checkVulkanSupport(getPackageManager())
                && !(Architecture.is32BitsDevice() && Architecture.isx86Device());
    }

    private boolean matchesRendererPlugin(RendererPlugin rendererPlugin, String rendererValue) {
        if (rendererPlugin == null || rendererValue == null || rendererValue.isEmpty()) {
            return false;
        }

        return rendererValue.equals(rendererPlugin.getUniqueIdentifier())
                || rendererValue.equals(rendererPlugin.getDisplayName())
                || rendererValue.equals(getRendererPluginId(rendererPlugin));
    }

    private String getRendererPluginId(RendererPlugin rendererPlugin) {
        try {
            return rendererPlugin.getId();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void showModernRendererInstallDialog() {
        String message =
                "Minecraft 1.17 and newer needs one of these renderers before launch:\n\n"
                        + "• Mobile Glues\n"
                        + "• LTW\n"
                        + "• Krypton\n"
                        + "• Vulkan Zink\n\n"
                        + "No compatible renderer was detected.\n\n"
                        + "Recommended download:\n"
                        + MOBILE_GLUES_URL;

        new TipDialog.Builder(this)
                .setTitle(R.string.generic_warning)
                .setMessage(message)
                .setCancelable(true)
                .setShowCancel(true)
                .setCenterMessage(false)
                .setSelectable(true)
                .setConfirmClickListener(checked -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(MOBILE_GLUES_URL)));
                    } catch (Exception e) {
                        Logging.e(TAG, "Failed to open renderer download page.", e);
                    }
                })
                .showDialog();
    }

    private void showModernRendererSelectionDialog() {
        String message =
                "Minecraft 1.17 and newer requires one of these renderers:\n\n"
                        + "• Mobile Glues\n"
                        + "• LTW\n"
                        + "• Krypton\n"
                        + "• Vulkan Zink\n\n"
                        + "A compatible renderer appears to be installed, but it is not selected for this version.\n\n"
                        + "Open Settings > Video Settings > Renderer, then select a compatible renderer before launching.";

        new TipDialog.Builder(this)
                .setTitle(R.string.generic_warning)
                .setMessage(message)
                .setCancelable(true)
                .setShowCancel(false)
                .setCenterMessage(false)
                .setSelectable(true)
                .showDialog();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeLower(String value) {
        return safeTrim(value).toLowerCase();
    }

    private void checkNotice() {
        checkNoticeTask = TaskExecutors.getDefault().submit(() ->
                CheckNewNotice.checkNewNotice(noticeInfo -> {
                    if (checkNoticeTask.isCancelled() || noticeInfo == null) {
                        return;
                    }

                    if (AllSettings.getNoticeDefault().getValue()
                            || noticeInfo.numbering != AllSettings.getNoticeNumbering().getValue()) {
                        TaskExecutors.runInUIThread(() -> setNotice(true));
                        AllSettings.getNoticeDefault()
                                .put(true)
                                .put(AllSettings.getNoticeNumbering(), noticeInfo.numbering)
                                .save();
                    }
                })
        );
    }

    private void setNotice(boolean show) {
        if (show) {
            NoticeInfo noticeInfo = CheckNewNotice.getNoticeInfo();
            if (noticeInfo == null) {
                return;
            }

            binding.noticeGotButton.setClickable(true);
            binding.noticeTitleView.setText(noticeInfo.title);
            binding.noticeMessageView.setText(noticeInfo.content);
            binding.noticeDateView.setText(noticeInfo.date);

            noticeAnimPlayer.clearEntries();
            noticeAnimPlayer.apply(
                            new AnimPlayer.Entry(binding.noticeLayout, Animations.BounceEnlarge))
                    .setOnStart(() -> binding.noticeLayout.setVisibility(View.VISIBLE))
                    .start();
            return;
        }

        binding.noticeGotButton.setClickable(false);

        noticeAnimPlayer.clearEntries();
        noticeAnimPlayer.apply(
                        new AnimPlayer.Entry(binding.noticeLayout, Animations.BounceShrink))
                .setOnStart(() -> binding.noticeLayout.setVisibility(View.VISIBLE))
                .setOnEnd(() -> binding.noticeLayout.setVisibility(View.GONE))
                .start();
    }

    private void refreshBackground() {
        BackgroundManager.setBackgroundImage(
                this,
                BackgroundType.MAIN_MENU,
                binding.backgroundView,
                this::refreshTopBarColor
        );
    }

    private void refreshTopBarColor(boolean loadFromBackground) {
        int backgroundMenuTop = ContextCompat.getColor(this, R.color.background_menu_top);

        if (loadFromBackground) {
            Bitmap bitmap = ImageUtils.getBitmapFromImageView(binding.backgroundView);
            if (bitmap != null) {
                Palette palette = Palette.from(bitmap).generate();
                boolean isDarkMode = ZHTools.isDarkMode(this);

                binding.topLayout.setBackgroundColor(
                        isDarkMode
                                ? palette.getDarkVibrantColor(backgroundMenuTop)
                                : palette.getLightVibrantColor(backgroundMenuTop)
                );

                int mutedColor = isDarkMode
                        ? palette.getLightMutedColor(0xFFFFFFFF)
                        : palette.getDarkMutedColor(0xFFFFFFFF);

                ColorStateList colorStateList = ColorStateList.valueOf(mutedColor);
                binding.appTitleText.setTextColor(mutedColor);
                binding.downloadButton.setImageTintList(colorStateList);
                binding.settingButton.setImageTintList(colorStateList);
                return;
            }
        }

        binding.topLayout.setBackgroundColor(backgroundMenuTop);
        binding.appTitleText.setTextColor(
                ContextCompat.getColor(this, R.color.menu_bar_text)
        );

        ColorStateList colorStateList = ColorStateList.valueOf(0xFFFFFFFF);
        binding.downloadButton.setImageTintList(colorStateList);
        binding.settingButton.setImageTintList(colorStateList);
    }

    private Fragment getCurrentFragment() {
        return getVisibleFragment(binding.containerFragment.getId());
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag) {
        return getVisibleFragmentIfAvailable(getSupportFragmentManager().findFragmentByTag(tag));
    }

    private Fragment getVisibleFragment(int id) {
        return getVisibleFragmentIfAvailable(getSupportFragmentManager().findFragmentById(id));
    }

    private Fragment getVisibleFragmentIfAvailable(Fragment fragment) {
        return fragment != null && fragment.isVisible() ? fragment : null;
    }

    private void checkNotificationPermission() {
        if (AllSettings.getSkipNotificationPermissionCheck().getValue()
                || ZHTools.checkForNotificationPermission()) {
            return;
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS
        )) {
            showNotificationPermissionReasoning();
            return;
        }

        askForNotificationPermission(null);
    }

    private void showNotificationPermissionReasoning() {
        new TipDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(getString(
                        R.string.notification_permission_dialog_text,
                        InfoDistributor.APP_NAME,
                        InfoDistributor.APP_NAME
                ))
                .setConfirmClickListener(checked -> askForNotificationPermission(null))
                .setCancelClickListener(this::handleNoNotificationPermission)
                .showDialog();
    }

    private void handleNoNotificationPermission() {
        AllSettings.getSkipNotificationPermissionCheck().put(true).save();
        Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show();
    }

    public void askForNotificationPermission(Runnable onSuccessRunnable) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }

        if (onSuccessRunnable != null) {
            requestNotificationPermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }

        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void setPageOpacity(int pageOpacity) {
        BigDecimal opacity = BigDecimal.valueOf(pageOpacity)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

        float pageAlpha = opacity.floatValue();
        binding.containerFragment.setAlpha(pageAlpha);

        BigDecimal adjustedOpacity = BackgroundManager.hasBackgroundImage(BackgroundType.MAIN_MENU)
                ? opacity.subtract(BigDecimal.valueOf(0.1)).max(BigDecimal.ZERO)
                : BigDecimal.ONE;

        binding.topLayout.setAlpha(adjustedOpacity.floatValue());
    }
}
