package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.movtery.zalithlauncher.recorder.GameRecorder;
import com.movtery.zalithlauncher.recorder.RecorderPrefs;
import com.movtery.zalithlauncher.recorder.VoicechatButton;

import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.databinding.ActivityGameBinding;
import com.movtery.zalithlauncher.databinding.ViewControlMenuBinding;
import com.movtery.zalithlauncher.databinding.ViewGameMenuBinding;
import com.movtery.zalithlauncher.event.single.RefreshHotbarEvent;
import com.movtery.zalithlauncher.event.value.HotbarChangeEvent;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.setting.AllStaticSettings;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.ui.dialog.KeyboardDialog;
import com.movtery.zalithlauncher.ui.dialog.SelectControlsDialog;
import com.movtery.zalithlauncher.ui.dialog.SelectMouseDialog;
import com.movtery.zalithlauncher.ui.fragment.settings.VideoSettingsFragment;
import com.movtery.zalithlauncher.ui.subassembly.adapter.ObjectSpinnerAdapter;
import com.movtery.zalithlauncher.ui.subassembly.hotbar.HotbarType;
import com.movtery.zalithlauncher.ui.subassembly.hotbar.HotbarUtils;
import com.movtery.zalithlauncher.ui.subassembly.menu.MenuUtils;
import com.movtery.zalithlauncher.ui.subassembly.view.GameMenuViewWrapper;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.skydoves.powerspinner.OnSpinnerItemSelectedListener;

import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.customcontrols.mouse.GyroControl;

import org.greenrobot.eventbus.EventBus;
import org.lwjgl.glfw.CallbackBridge;

import java.io.IOException;

public class GameMenuSettingsController implements
        View.OnClickListener,
        SeekBar.OnSeekBarChangeListener,
        CompoundButton.OnCheckedChangeListener,
        OnSpinnerItemSelectedListener<HotbarType>,
        DrawerLayout.DrawerListener {

    public interface EditorState {
        void setInEditor(boolean inEditor);
        boolean isInEditor();
    }

    private final MainActivity activity;
    private final ActivityGameBinding activityBinding;
    private final ViewGameMenuBinding binding;
    private final ViewControlMenuBinding controlBinding;
    private final KeyboardDialog keyboardDialog;
    private final GameMenuViewWrapper gameMenuWrapper;
    private final Version minecraftVersion;
    private final GyroControl gyroControl;
    private final EditorState editorState;

    public GameMenuSettingsController(
            MainActivity activity,
            ActivityGameBinding activityBinding,
            ViewGameMenuBinding binding,
            ViewControlMenuBinding controlBinding,
            KeyboardDialog keyboardDialog,
            GameMenuViewWrapper gameMenuWrapper,
            Version minecraftVersion,
            GyroControl gyroControl,
            EditorState editorState
    ) {
        this.activity = activity;
        this.activityBinding = activityBinding;
        this.binding = binding;
        this.controlBinding = controlBinding;
        this.keyboardDialog = keyboardDialog;
        this.gameMenuWrapper = gameMenuWrapper;
        this.minecraftVersion = minecraftVersion;
        this.gyroControl = gyroControl;
        this.editorState = editorState;

        initState();
        initSeekBars();
        initSwitches();
        initClickListeners();
        initHotbarSpinner();
        initRecorder();
    }

    // ===================== RecordZy recorder controls =====================

    private static final int REQ_RECORD_AUDIO = 0x5EC1;

    private void initRecorder() {
        RecorderPrefs p = new RecorderPrefs(activity);
        binding.recorderEnable.setChecked(p.isEnabled());
        binding.recorderMic.setChecked(p.isRecordAudio());
        binding.recorderHevc.setChecked(p.getMimeType().toLowerCase().contains("hevc"));
        binding.recorderEnable.setOnCheckedChangeListener(this);
        binding.recorderMic.setOnCheckedChangeListener(this);
        binding.recorderHevc.setOnCheckedChangeListener(this);
        binding.recorderToggle.setOnClickListener(this);

        binding.recorderQuality.setProgress(heightToProgress(p.getHeight()));
        binding.recorderQualityValue.setText(heightLabel(progressToHeight(binding.recorderQuality.getProgress())));
        binding.recorderQuality.setOnSeekBarChangeListener(this);

        binding.recorderFps.setProgress(Math.max(0, Math.min(36, p.getFps() - 24)));
        binding.recorderFpsValue.setText((24 + binding.recorderFps.getProgress()) + " fps");
        binding.recorderFps.setOnSeekBarChangeListener(this);

        int mbps = Math.max(1, p.getBitrateKbps() / 1000);
        binding.recorderBitrate.setProgress(mbps);
        binding.recorderBitrateValue.setText(mbps + " Mbps");
        binding.recorderBitrate.setOnSeekBarChangeListener(this);

        updateRecorderToggleText();
    }

    private void updateRecorderToggleText() {
        GameRecorder r = GameRecorder.getInstance();
        if (!r.isActive()) {
            binding.recorderToggle.setText("Recorder off - enable & relaunch");
        } else if (r.isRecording()) {
            binding.recorderToggle.setText("\u25A0 Stop Recording");
        } else {
            binding.recorderToggle.setText("\u25CF Start Recording");
        }
    }

    private void onRecorderToggleClicked() {
        GameRecorder r = GameRecorder.getInstance();
        if (!r.isActive()) {
            Toast.makeText(activity, "Enable the recorder and relaunch the game first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        RecorderPrefs p = new RecorderPrefs(activity);
        if (!r.isRecording()) {
            if (p.isRecordAudio() && ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                Toast.makeText(activity, "Grant microphone, then tap Start again",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            r.startRecording(activity);
            if (p.isRecordAudio() && p.isVoicechatButton()) {
                VoicechatButton.show(activity);
            }
            Toast.makeText(activity, "Recording started", Toast.LENGTH_SHORT).show();
        } else {
            r.stopRecording();
            VoicechatButton.hide(activity);
            Toast.makeText(activity, "Recording saved", Toast.LENGTH_SHORT).show();
        }
        updateRecorderToggleText();
    }

    private static int heightToProgress(int height) {
        if (height <= 480) return 0;
        if (height <= 720) return 1;
        return 2;
    }

    private static int progressToHeight(int progress) {
        switch (progress) {
            case 0: return 480;
            case 2: return 1080;
            default: return 720;
        }
    }

    private static String heightLabel(int height) {
        return height + "p";
    }

    private void initState() {
        binding.hotbarWidth.setMax(currentDisplayMetrics.widthPixels / 2);
        binding.hotbarHeight.setMax(currentDisplayMetrics.heightPixels / 2);

        refreshLayoutVisible(binding.timeLongPressTriggerLayout, !AllSettings.getDisableGestures().getValue());
        refreshLayoutVisible(binding.gyroLayout, AllSettings.getEnableGyro().getValue());
    }

    private void initSeekBars() {
        MenuUtils.initSeekBarValue(binding.resolutionScaler, AllSettings.getResolutionRatio().getValue(), binding.resolutionScalerValue, "%");
        binding.resolutionScalerPreview.setText(
                VideoSettingsFragment.getResolutionRatioPreview(activity.getResources(), AllSettings.getResolutionRatio().getValue())
        );

        MenuUtils.initSeekBarValue(binding.timeLongPressTrigger, AllSettings.getTimeLongPressTrigger().getValue(), binding.timeLongPressTriggerValue, "ms");
        MenuUtils.initSeekBarValue(binding.mouseSpeed, AllSettings.getMouseSpeed().getValue(), binding.mouseSpeedValue, "%");
        MenuUtils.initSeekBarValue(binding.gyroSensitivity, AllSettings.getGyroSensitivity().getValue(), binding.gyroSensitivityValue, "%");
        MenuUtils.initSeekBarValue(binding.hotbarHeight, AllSettings.getHotbarHeight().getValue().getValue(), binding.hotbarHeightValue, "px");
        MenuUtils.initSeekBarValue(binding.hotbarWidth, AllSettings.getHotbarWidth().getValue().getValue(), binding.hotbarWidthValue, "px");

        binding.resolutionScaler.setOnSeekBarChangeListener(this);
        binding.timeLongPressTrigger.setOnSeekBarChangeListener(this);
        binding.mouseSpeed.setOnSeekBarChangeListener(this);
        binding.gyroSensitivity.setOnSeekBarChangeListener(this);
        binding.hotbarHeight.setOnSeekBarChangeListener(this);
        binding.hotbarWidth.setOnSeekBarChangeListener(this);
    }

    private void initSwitches() {
        binding.openMemoryInfo.setChecked(AllSettings.getGameMenuShowMemory().getValue());
        binding.openFpsInfo.setChecked(AllSettings.getGameMenuShowFPS().getValue());
        binding.disableGestures.setChecked(AllSettings.getDisableGestures().getValue());
        binding.disableDoubleTap.setChecked(AllSettings.getDisableDoubleTap().getValue());
        binding.enableGyro.setChecked(AllSettings.getEnableGyro().getValue());
        binding.gyroInvertX.setChecked(AllSettings.getGyroInvertX().getValue());
        binding.gyroInvertY.setChecked(AllSettings.getGyroInvertY().getValue());
        binding.openMemoryInfo.setOnCheckedChangeListener(this);
        binding.openFpsInfo.setOnCheckedChangeListener(this);
        binding.disableGestures.setOnCheckedChangeListener(this);
        binding.disableDoubleTap.setOnCheckedChangeListener(this);
        binding.enableGyro.setOnCheckedChangeListener(this);
        binding.gyroInvertX.setOnCheckedChangeListener(this);
        binding.gyroInvertY.setOnCheckedChangeListener(this);
        binding.forceGuiInput.setOnCheckedChangeListener(this);
        binding.forceGuiInputLayout.setOnClickListener(this);


    }

    private void initClickListeners() {
        binding.forceClose.setOnClickListener(this);
        binding.logOutput.setOnClickListener(this);
        binding.sendCustomKey.setOnClickListener(this);
        binding.openMemoryInfoLayout.setOnClickListener(this);
        binding.openFpsInfoLayout.setOnClickListener(this);
        binding.resolutionScalerRemove.setOnClickListener(this);
        binding.resolutionScalerAdd.setOnClickListener(this);
        binding.disableGesturesLayout.setOnClickListener(this);
        binding.disableDoubleTapLayout.setOnClickListener(this);
        binding.timeLongPressTriggerRemove.setOnClickListener(this);
        binding.timeLongPressTriggerAdd.setOnClickListener(this);
        binding.mouseSpeedRemove.setOnClickListener(this);
        binding.mouseSpeedAdd.setOnClickListener(this);
        binding.customMouse.setOnClickListener(this);
        binding.replacementCustomcontrol.setOnClickListener(this);
        binding.editControl.setOnClickListener(this);
        binding.enableGyroLayout.setOnClickListener(this);
        binding.gyroSensitivityRemove.setOnClickListener(this);
        binding.gyroSensitivityAdd.setOnClickListener(this);
        binding.gyroInvertXLayout.setOnClickListener(this);
        binding.gyroInvertYLayout.setOnClickListener(this);
        binding.hotbarWidthRemove.setOnClickListener(this);
        binding.hotbarWidthAdd.setOnClickListener(this);
        binding.hotbarHeightRemove.setOnClickListener(this);
        binding.hotbarHeightAdd.setOnClickListener(this);

    }

    private void initHotbarSpinner() {
        ObjectSpinnerAdapter<HotbarType> hotbarTypeAdapter = new ObjectSpinnerAdapter<>(
                binding.hotbarType,
                hotbarType -> activity.getString(hotbarType.getNameId())
        );
        hotbarTypeAdapter.setItems(HotbarType.getEntries());
        binding.hotbarType.setSpinnerAdapter(hotbarTypeAdapter);
        binding.hotbarType.setIsFocusable(true);
        binding.hotbarType.setOnSpinnerItemSelectedListener(this);
        binding.hotbarType.selectItemByIndex(HotbarUtils.getCurrentTypeIndex());
    }

    private void dialogSendCustomKey() {
        keyboardDialog.setOnMultiKeycodeSelectListener(selectedKeycodes -> {
            // Simulate pressing all selected keys together, then releasing them together.
            Task.runTask(() -> {
                selectedKeycodes.forEach(keycode -> sendKeyPress(keycode, true));
                return null;
            }).ended(a -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
                selectedKeycodes.forEach(keycode -> sendKeyPress(keycode, false));
            }).execute();
        }).show();
    }

    private void sendKeyPress(int keycode, boolean isDown) {
        int lwjglKeycode = EfficientAndroidLWJGLKeycode.getValueByIndex(keycode);
        Logging.i("MainActivity", "Selected keycode=" + keycode + ", mapped LWJGL keycode=" + lwjglKeycode);

        if (keycode >= LwjglGlfwKeycode.GLFW_KEY_UNKNOWN) {
            CallbackBridge.sendKeyPress(lwjglKeycode, CallbackBridge.getCurrentMods(), isDown);
            CallbackBridge.setModifiers(lwjglKeycode, isDown);
        }
    }

    private void replaceCustomControls() {
        SelectControlsDialog dialog = new SelectControlsDialog(activity, file -> {
            try {
                activityBinding.mainControlLayout.loadLayout(file.getAbsolutePath());
                gameMenuWrapper.setVisibility(!activityBinding.mainControlLayout.hasMenuButton());
            } catch (IOException ignored) {
            }
        });
        dialog.setTitleText(R.string.replacement_customcontrol);
        dialog.show();
    }

    private void openCustomControlsEditor() {
        activityBinding.mainControlLayout.setModifiable(true);
        activityBinding.mainNavigationView.removeAllViews();
        activityBinding.mainNavigationView.addView(controlBinding.getRoot());
        gameMenuWrapper.setVisibility(true);
        editorState.setInEditor(true);
    }

    @Override
    public void onClick(View view) {
        if (view == binding.forceClose) {
            ZHTools.dialogForceClose(activity);
        } else if (view == binding.logOutput) {
            activityBinding.mainLoggerView.toggleViewWithAnim();
        } else if (view == binding.sendCustomKey) {
            dialogSendCustomKey();
        } else if (view == binding.openMemoryInfoLayout) {
            MenuUtils.toggleSwitchState(binding.openMemoryInfo);
        } else if (view == binding.openFpsInfoLayout) {
            MenuUtils.toggleSwitchState(binding.openFpsInfo);
        } else if (view == binding.resolutionScalerRemove) {
            MenuUtils.adjustSeekbar(binding.resolutionScaler, -1);
        } else if (view == binding.resolutionScalerAdd) {
            MenuUtils.adjustSeekbar(binding.resolutionScaler, 1);
        } else if (view == binding.disableGesturesLayout) {
            MenuUtils.toggleSwitchState(binding.disableGestures);
        }else if (view == binding.forceGuiInputLayout) {
            MenuUtils.toggleSwitchState(binding.forceGuiInput);

        } else if (view == binding.disableDoubleTapLayout) {
            MenuUtils.toggleSwitchState(binding.disableDoubleTap);
        } else if (view == binding.timeLongPressTriggerRemove) {
            MenuUtils.adjustSeekbar(binding.timeLongPressTrigger, -1);
        } else if (view == binding.timeLongPressTriggerAdd) {
            MenuUtils.adjustSeekbar(binding.timeLongPressTrigger, 1);
        } else if (view == binding.mouseSpeedRemove) {
            MenuUtils.adjustSeekbar(binding.mouseSpeed, -1);
        } else if (view == binding.mouseSpeedAdd) {
            MenuUtils.adjustSeekbar(binding.mouseSpeed, 1);
        } else if (view == binding.customMouse) {
            new SelectMouseDialog(activity, () -> activityBinding.mainTouchpad.updateMouseDrawable()).show();
        } else if (view == binding.replacementCustomcontrol) {
            replaceCustomControls();
        } else if (view == binding.editControl) {
            openCustomControlsEditor();
        } else if (view == binding.enableGyroLayout) {
            MenuUtils.toggleSwitchState(binding.enableGyro);
        } else if (view == binding.gyroSensitivityRemove) {
            MenuUtils.adjustSeekbar(binding.gyroSensitivity, -1);
        } else if (view == binding.gyroSensitivityAdd) {
            MenuUtils.adjustSeekbar(binding.gyroSensitivity, 1);
        } else if (view == binding.gyroInvertXLayout) {
            MenuUtils.toggleSwitchState(binding.gyroInvertX);
        } else if (view == binding.gyroInvertYLayout) {
            MenuUtils.toggleSwitchState(binding.gyroInvertY);
        } else if (view == binding.hotbarWidthRemove) {
            MenuUtils.adjustSeekbar(binding.hotbarWidth, -1);
        } else if (view == binding.hotbarWidthAdd) {
            MenuUtils.adjustSeekbar(binding.hotbarWidth, 1);
        } else if (view == binding.hotbarHeightRemove) {
            MenuUtils.adjustSeekbar(binding.hotbarHeight, -1);
        } else if (view == binding.hotbarHeightAdd) {
            MenuUtils.adjustSeekbar(binding.hotbarHeight, 1);
        } else if (view == binding.recorderToggle) {
            onRecorderToggleClicked();
        }
    }

    @Override
    @SuppressLint("SetTextI18n")
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        updateSeekbarValue(seekBar, !fromUser);
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        updateSeekbarValue(seekBar, true);
    }

    private void updateSeekbarValue(SeekBar seekBar, boolean saveValue) {
        int progress = seekBar == null ? 0 : seekBar.getProgress();

        if (seekBar == binding.resolutionScaler) {
            if (saveValue) {
                AllSettings.getResolutionRatio().put(progress).save();
            }
            MenuUtils.updateSeekbarValue(progress, binding.resolutionScalerValue, "%");
            binding.resolutionScalerPreview.setText(
                    VideoSettingsFragment.getResolutionRatioPreview(activity.getResources(), progress)
            );
            AllStaticSettings.scaleFactor = progress / 100f;
            activityBinding.mainGameRenderView.refreshSize();
        } else if (seekBar == binding.timeLongPressTrigger) {
            if (saveValue) {
                AllSettings.getTimeLongPressTrigger().put(progress).save();
            }
            MenuUtils.updateSeekbarValue(progress, binding.timeLongPressTriggerValue, "ms");
            AllStaticSettings.timeLongPressTrigger = progress;
        } else if (seekBar == binding.mouseSpeed) {
            if (saveValue) {
                AllSettings.getMouseSpeed().put(progress).save();
            }
            MenuUtils.updateSeekbarValue(progress, binding.mouseSpeedValue, "%");
        } else if (seekBar == binding.gyroSensitivity) {
            if (saveValue) {
                AllSettings.getGyroSensitivity().put(progress).save();
            }
            MenuUtils.updateSeekbarValue(progress, binding.gyroSensitivityValue, "%");
            AllStaticSettings.gyroSensitivity = progress;
        } else if (seekBar == binding.hotbarWidth) {
            if (saveValue) {
                AllSettings.getHotbarWidth().getValue().put(progress).save();
            }
            MenuUtils.updateSeekbarValue(progress, binding.hotbarWidthValue, "px");
            EventBus.getDefault().post(new HotbarChangeEvent(progress, binding.hotbarHeight.getProgress()));
        } else if (seekBar == binding.hotbarHeight) {
            if (saveValue) {
                AllSettings.getHotbarHeight().getValue().put(progress).save();
            }
            MenuUtils.updateSeekbarValue(progress, binding.hotbarHeightValue, "px");
            EventBus.getDefault().post(new HotbarChangeEvent(binding.hotbarWidth.getProgress(), progress));
        } else if (seekBar == binding.recorderQuality) {
            int height = progressToHeight(progress);
            if (saveValue) {
                new RecorderPrefs(activity).setHeight(height);
            }
            binding.recorderQualityValue.setText(heightLabel(height));
        } else if (seekBar == binding.recorderFps) {
            int fps = 24 + progress;
            if (saveValue) {
                new RecorderPrefs(activity).setFps(fps);
            }
            binding.recorderFpsValue.setText(fps + " fps");
        } else if (seekBar == binding.recorderBitrate) {
            int mbps = Math.max(1, progress);
            if (saveValue) {
                new RecorderPrefs(activity).setBitrateKbps(mbps * 1000);
            }
            binding.recorderBitrateValue.setText(mbps + " Mbps");
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
        if (compoundButton == binding.openMemoryInfo) {
            AllSettings.getGameMenuShowMemory().put(isChecked).save();
            gameMenuWrapper.refreshSettingsState();
        } else if (compoundButton == binding.openFpsInfo) {
            AllSettings.getGameMenuShowFPS().put(isChecked).save();
            gameMenuWrapper.refreshSettingsState();
        } else if (compoundButton == binding.disableGestures) {
            refreshLayoutVisible(binding.timeLongPressTriggerLayout, !isChecked);
            AllSettings.getDisableGestures().put(isChecked).save();
        }else if (compoundButton == binding.forceGuiInput) {
            AllSettings.getForceGuiInput().put(isChecked).save();
            AllStaticSettings.forceGuiInput = isChecked;
            activityBinding.mainGameRenderView.refreshTouchProcessor();

        } else if (compoundButton == binding.disableDoubleTap) {
            AllSettings.getDisableDoubleTap().put(isChecked).save();
            AllStaticSettings.disableDoubleTap = isChecked;
        } else if (compoundButton == binding.enableGyro) {
            refreshLayoutVisible(binding.gyroLayout, isChecked);
            AllSettings.getEnableGyro().put(isChecked).save();
            AllStaticSettings.enableGyro = isChecked;
            gyroControl.updateOrientation();
            if (isChecked) {
                gyroControl.enable();
            } else {
                gyroControl.disable();
            }
        } else if (compoundButton == binding.gyroInvertX) {
            AllSettings.getGyroInvertX().put(isChecked).save();
            AllStaticSettings.gyroInvertX = isChecked;
        } else if (compoundButton == binding.gyroInvertY) {
            AllSettings.getGyroInvertY().put(isChecked).save();
            AllStaticSettings.gyroInvertY = isChecked;
        } else if (compoundButton == binding.recorderEnable) {
            new RecorderPrefs(activity).setEnabled(isChecked);
            Toast.makeText(activity, "Relaunch the game to apply", Toast.LENGTH_LONG).show();
        } else if (compoundButton == binding.recorderMic) {
            new RecorderPrefs(activity).setRecordAudio(isChecked);
        } else if (compoundButton == binding.recorderHevc) {
            new RecorderPrefs(activity).setCodec(isChecked ? "hevc" : "avc");
        }
    }

    /** Updates a view's visibility. */
    private void refreshLayoutVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onItemSelected(int oldIndex, @Nullable HotbarType oldItem, int newIndex, HotbarType newItem) {
        if (newItem == HotbarType.AUTO) {
            binding.hotbarWidthLayout.setVisibility(View.GONE);
            binding.hotbarHeightLayout.setVisibility(View.GONE);
        } else if (newItem == HotbarType.MANUALLY) {
            binding.hotbarWidthLayout.setVisibility(View.VISIBLE);
            binding.hotbarHeightLayout.setVisibility(View.VISIBLE);
            binding.hotbarWidth.setProgress(AllSettings.getHotbarWidth().getValue().getValue());
            binding.hotbarHeight.setProgress(AllSettings.getHotbarHeight().getValue().getValue());
        }

        AllSettings.getHotbarType().put(newItem.getValueName()).save();
        EventBus.getDefault().post(new RefreshHotbarEvent());
    }

    @Override
    public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
    }

    @Override
    public void onDrawerOpened(@NonNull View drawerView) {
    }

    @Override
    public void onDrawerClosed(@NonNull View drawerView) {
    }

    @Override
    public void onDrawerStateChanged(int newState) {
        closeSpinner();
    }

    public void closeSpinner() {
        binding.hotbarType.dismiss();
    }
}
