package com.movtery.zalithlauncher.ui.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.event.value.DownloadPageEvent
import com.movtery.zalithlauncher.feature.download.InfoViewModel
import com.movtery.zalithlauncher.feature.download.ScreenshotAdapter
import com.movtery.zalithlauncher.feature.download.VersionAdapter
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModVersionItem
import com.movtery.zalithlauncher.feature.download.item.ScreenshotItem
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.platform.AbstractPlatformHelper
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.VersionsManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.subassembly.modlist.ModListAdapter
import com.movtery.zalithlauncher.ui.subassembly.modlist.ModListFragment
import com.movtery.zalithlauncher.ui.subassembly.modlist.ModListItemBean
import com.movtery.zalithlauncher.ui.view.AnimButton
import com.movtery.zalithlauncher.utils.MCVersionRegex.Companion.RELEASE_REGEX
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.stringutils.StringUtilsKt
import net.kdt.pojavlaunch.Tools
import org.greenrobot.eventbus.EventBus
import org.jackhuang.hmcl.ui.versions.ModTranslations
import org.jackhuang.hmcl.util.versioning.VersionNumber
import java.util.Locale
import java.util.Objects
import java.util.concurrent.Future

class DownloadModFragment : ModListFragment() {
    companion object {
        const val TAG: String = "DownloadModFragment"
    }

    private lateinit var platformHelper: AbstractPlatformHelper
    private lateinit var mInfoItem: InfoItem
    private var linkGetSubmit: Future<*>? = null

    override fun init() {
        parseViewModel()
        super.init()
    }

    @SuppressLint("CheckResult")
    override fun refreshCreatedView() {
        val activity = fragmentActivity ?: return

        linkGetSubmit = TaskExecutors.getDefault().submit {
            runCatching {
                val webUrl = platformHelper.getWebUrl(mInfoItem)
                fragmentActivity?.runOnUiThread { setLink(webUrl) }
            }.getOrElse { e ->
                Logging.e("DownloadModFragment", "Failed to retrieve the website link, ${Tools.printToString(e)}")
            }
        }

        mInfoItem.apply {
            val type = ModTranslations.getTranslationsByRepositoryType(classify)
            val mod = type.getModByCurseForgeId(slug)

            setTitleText(if (ZHTools.areaChecks("zh")) mod?.displayName ?: title else title)
            setDescription(description)
            mod?.let {
                setMCMod(StringUtilsKt.getNonEmptyOrBlank(type.getMcmodUrl(it)))
            }
            loadScreenshots()

            iconUrl?.let { url ->
                Glide.with(activity).load(url).apply {
                    if (!AllSettings.resourceImageCache.getValue()) {
                        diskCacheStrategy(DiskCacheStrategy.NONE)
                    }
                }.into(getIconView())
            }
        }
    }

    override fun initRefresh(): Future<*> = refresh(false)

    override fun refresh(): Future<*> = refresh(true)

    override fun onDestroy() {
        EventBus.getDefault().post(DownloadPageEvent.RecyclerEnableEvent(true))
        linkGetSubmit?.takeIf { !it.isCancelled && !it.isDone }?.cancel(true)
        super.onDestroy()
    }

    private fun refresh(force: Boolean): Future<*> {
        return TaskExecutors.getDefault().submit {
            runCatching {
                TaskExecutors.runInUIThread {
                    cancelFailedToLoad()
                    componentProcessing(true)
                }
                processDetails(platformHelper.getVersions(mInfoItem, force))
            }.getOrElse { e ->
                TaskExecutors.runInUIThread {
                    componentProcessing(false)
                    setFailedToLoad(e.toString())
                }
                Logging.e("DownloadModFragment", Tools.printToString(e))
            }
        }
    }

    private fun processDetails(versions: List<VersionItem>?) {
        val groupedVersions: MutableMap<Pair<String, ModLoader?>, MutableList<VersionItem>> = LinkedHashMap()
        val releaseCheckBoxChecked = releaseCheckBox.isChecked

        versions.orEmpty().forEach { versionItem ->
            currentTask?.takeIf { it.isCancelled }?.let { return }

            for (mcVersion in versionItem.mcVersions) {
                currentTask?.takeIf { it.isCancelled }?.let { return }

                if (releaseCheckBoxChecked && !RELEASE_REGEX.matcher(mcVersion).matches()) {
                    continue
                }

                if (versionItem is ModVersionItem && versionItem.modloaders.isNotEmpty()) {
                    versionItem.modloaders
                        .distinct()
                        .forEach { modLoader ->
                            addIfAbsent(groupedVersions, Pair(mcVersion, modLoader), versionItem)
                        }
                    continue
                }

                addIfAbsent(groupedVersions, Pair(mcVersion, null), versionItem)
            }
        }

        currentTask?.takeIf { it.isCancelled }?.let { return }

        val currentVersion = VersionsManager.getCurrentVersion()
        var firstAdaptIndex: Int? = null
        val listData: MutableList<ModListItemBean> = ArrayList()

        groupedVersions.entries
            .sortedWith { entry1, entry2 ->
                val mcVersionComparison = -VersionNumber.compare(entry1.key.first, entry2.key.first)
                if (mcVersionComparison != 0) {
                    mcVersionComparison
                } else {
                    val name1 = entry1.key.second?.name ?: ""
                    val name2 = entry2.key.second?.name ?: ""
                    when {
                        name1.isEmpty() && name2.isNotEmpty() -> 1
                        name1.isNotEmpty() && name2.isEmpty() -> -1
                        else -> name1.compareTo(name2)
                    }
                }
            }
            .forEachIndexed { index, entry ->
                currentTask?.takeIf { it.isCancelled }?.let { return }

                val sortedVersions = entry.value.sortedByDescending { it.uploadDate.time }
                val isAdapt = isVersionGroupCompatible(entry.key.first, entry.key.second, currentVersion)

                if (isAdapt && firstAdaptIndex == null) {
                    firstAdaptIndex = index
                }

                listData.add(
                    ModListItemBean(
                        entry.key.first,
                        entry.key.second,
                        isAdapt,
                        VersionAdapter(mInfoItem, platformHelper, sortedVersions)
                    )
                )
            }

        currentTask?.takeIf { it.isCancelled }?.let { return }

        Task.runTask(TaskExecutors.getAndroidUI()) {
            runCatching {
                val activity = fragmentActivity ?: return@runCatching
                var modAdapter = recyclerView.adapter as ModListAdapter?
                if (modAdapter == null) {
                    modAdapter = ModListAdapter(this, listData)
                    recyclerView.layoutManager = LinearLayoutManager(activity)
                    recyclerView.adapter = modAdapter
                } else {
                    modAdapter.updateData(listData)
                }
            }.getOrElse { e ->
                Logging.e("Set Adapter", Tools.printToString(e))
            }

            componentProcessing(false)
            recyclerView.scheduleLayoutAnimation()

            firstAdaptIndex?.let {
                recyclerView.postDelayed(
                    { recyclerView.smoothScrollToPosition((it + 2).coerceAtMost(listData.size - 1)) },
                    500
                )
            }
        }.execute()
    }

    private fun isVersionGroupCompatible(targetMcVersion: String, targetLoader: ModLoader?, currentVersion: Any?): Boolean {
        if (mInfoItem.classify == Classify.MODPACK) return false

        return currentVersion?.let { version ->
            val versionInfo = VersionsManager.getCurrentVersion()?.getVersionInfo() ?: return@let false
            val currentMcVersion = versionInfo.minecraftVersion ?: return@let false
            val itemVersion = VersionNumber.asVersion(targetMcVersion).canonical
            val currentVersionString = VersionNumber.asVersion(currentMcVersion).canonical

            if (!Objects.equals(itemVersion, currentVersionString)) {
                return@let false
            }

            if (targetLoader == null) {
                return@let true
            }

            val loaderInfo = versionInfo.loaderInfo ?: return@let false
            val targetLoaderName = targetLoader.loaderName.lowercase(Locale.ROOT)
            loaderInfo.any { loader -> loader.name?.lowercase(Locale.ROOT) == targetLoaderName }
        } ?: false
    }

    private fun parseViewModel() {
        val activity = fragmentActivity ?: return
        val viewModel = ViewModelProvider(activity)[InfoViewModel::class.java]
        platformHelper = viewModel.platformHelper ?: run {
            ZHTools.onBackPressed(activity)
            return
        }
        mInfoItem = viewModel.infoItem ?: run {
            ZHTools.onBackPressed(activity)
            return
        }
    }

    private fun loadScreenshots() {
        val activity = fragmentActivity ?: return
        val progressBar = createProgressView(activity)
        addMoreView(progressBar)

        Task.runTask {
            platformHelper.getScreenshots(mInfoItem.projectId)
        }.ended(TaskExecutors.getAndroidUI()) { screenshotItems ->
            screenshotItems?.let addButton@{ items ->
                if (items.isEmpty()) return@addButton
                fragmentActivity?.let { safeActivity ->
                    addMoreView(AnimButton(safeActivity).apply {
                        layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        setText(R.string.download_info_load_screenshot)
                        setOnClickListener {
                            setScreenshotView(items)
                            AnimPlayer.play().apply(AnimPlayer.Entry(this, Animations.FadeOut))
                                .setOnEnd { removeMoreView(this) }
                                .start()
                        }
                    })
                }
            }
            removeMoreView(progressBar)
        }.onThrowable { e ->
            removeMoreView(progressBar)
            Logging.e(
                "DownloadModFragment",
                "Unable to load screenshots, ${Tools.printToString(e)}"
            )
        }.execute()
    }

    @SuppressLint("CheckResult")
    private fun setScreenshotView(screenshotItems: List<ScreenshotItem>) {
        fragmentActivity?.let { activity ->
            val recyclerView = RecyclerView(activity).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                layoutManager = LinearLayoutManager(activity)
                adapter = ScreenshotAdapter(screenshotItems)
            }

            addMoreView(recyclerView)
        }
    }

    private fun createProgressView(context: Context): ProgressBar {
        return ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }
}
