package com.movtery.zalithlauncher.ui.fragment

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.FragmentModsBinding
import com.movtery.zalithlauncher.feature.mod.ModJarIconHelper
import com.movtery.zalithlauncher.feature.mod.ModToggleHandler
import com.movtery.zalithlauncher.feature.mod.ModUtils
import com.movtery.zalithlauncher.feature.mod.ModrinthModUpdater
import com.movtery.zalithlauncher.feature.mod.ModrinthUpdateChecker
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.FilesDialog
import com.movtery.zalithlauncher.ui.dialog.FilesDialog.FilesButton
import com.movtery.zalithlauncher.ui.subassembly.filelist.FileIcon
import com.movtery.zalithlauncher.ui.subassembly.filelist.FileItemBean
import com.movtery.zalithlauncher.ui.subassembly.filelist.FileItemBean.UpdateUiStatus
import com.movtery.zalithlauncher.ui.subassembly.filelist.FileSelectedListener
import com.movtery.zalithlauncher.ui.subassembly.view.SearchViewWrapper
import com.movtery.zalithlauncher.utils.NewbieGuideUtils
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.anim.AnimUtils.Companion.setVisibilityAnim
import com.movtery.zalithlauncher.utils.file.FileCopyHandler
import com.movtery.zalithlauncher.utils.file.FileTools
import com.movtery.zalithlauncher.utils.file.PasteFile
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension
import java.io.File
import java.util.function.Consumer

class ModsFragment : FragmentWithAnim(R.layout.fragment_mods) {
    companion object {
        const val TAG: String = "ModsFragment"
        const val BUNDLE_ROOT_PATH: String = "root_path"
        const val BUNDLE_MC_VERSION: String = "mc_version"
    }

    private enum class UpdateSource {
        MODRINTH,
        CURSEFORGE
    }

    private data class UpdateUiInfo(
        val status: UpdateUiStatus,
        val text: String?
    )

    private data class ScanResult(
        val updates: MutableList<String>,
        val current: MutableList<String>,
        val unknown: MutableList<String>,
        val statuses: MutableMap<String, UpdateUiInfo>
    )

    private lateinit var binding: FragmentModsBinding
    private lateinit var mSearchViewWrapper: SearchViewWrapper
    private lateinit var mRootPath: String
    private var mMinecraftVersion: String? = null
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Any>

    private val mUpdateResults = mutableMapOf<String, UpdateUiInfo>()
    private var mShowUpdatesOnly: Boolean = false
    private var mAllModItems: MutableList<FileItemBean> = mutableListOf()
    private var mActiveUpdateSource: UpdateSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDocumentLauncher = registerForActivityResult(OpenDocumentWithExtension("jar", true)) { uris: List<Uri>? ->
            uris?.let { uriList ->
                val dialog = ZHTools.showTaskRunningDialog(requireContext())
                Task.runTask {
                    uriList.forEach { uri ->
                        FileTools.copyFileInBackground(requireContext(), uri, mRootPath)
                    }
                }.ended(TaskExecutors.getAndroidUI()) {
                    Toast.makeText(requireContext(), getString(R.string.profile_mods_added_mod), Toast.LENGTH_SHORT).show()
                    refreshAndReapply()
                }.onThrowable { e ->
                    Tools.showErrorRemote(e)
                }.finallyTask(TaskExecutors.getAndroidUI()) {
                    dialog.dismiss()
                }.execute()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentModsBinding.inflate(layoutInflater)
        mSearchViewWrapper = SearchViewWrapper(this)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initViews()
        parseBundle()

        binding.apply {
            versionDebugText.text = "MC: ${mMinecraftVersion ?: "null"}"

            showUpdatesOnly.setOnCheckedChangeListener { _, isChecked ->
                mShowUpdatesOnly = isChecked
                applyUpdateResultsToVisibleItems()

                if (!isChecked && !hasAnyVisibleUpdates()) {
                    binding.showUpdatesOnly.visibility = View.GONE
                }
            }

            fileRecyclerView.apply {
                setShowFiles(true)
                setShowFolders(false)

                setFileSelectedListener(object : FileSelectedListener() {
                    override fun onFileSelected(file: File?, path: String?) {
                        file?.takeIf { it.isFile }?.let { openFileActions(it) }
                    }

                    override fun onItemLongClick(file: File?, path: String?) = Unit
                })

                setOnMultiSelectListener { itemBeans: List<FileItemBean> ->
                    if (itemBeans.isNotEmpty()) {
                        openMultiSelectActions(itemBeans)
                    }
                }

                setRefreshListener {
                    mAllModItems = binding.fileRecyclerView.adapter.data.toMutableList()
                    applyUpdateResultsToVisibleItems()
                }
            }

            multiSelectFiles.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                selectAll.apply {
                    this.isChecked = false
                    visibility = if (isChecked) View.VISIBLE else View.GONE
                }
                updateActionsRow.visibility = if (isChecked) View.GONE else View.VISIBLE
                showUpdatesOnly.visibility = if (isChecked) View.GONE else getUpdatesOnlyVisibility()
                fileRecyclerView.adapter.setMultiSelectMode(isChecked)
                if (mSearchViewWrapper.isVisible()) {
                    mSearchViewWrapper.setVisibility(!isChecked)
                }
            }

            selectAll.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                fileRecyclerView.adapter.selectAllFiles(isChecked)
            }

            operateView.apply {
                returnButton.setOnClickListener {
                    closeMultiSelect()
                    ZHTools.onBackPressed(requireActivity())
                }

                addFileButton.setOnClickListener {
                    closeMultiSelect()
                    val suffix = ".jar"
                    Toast.makeText(
                        requireActivity(),
                        String.format(getString(R.string.file_add_file_tip), suffix),
                        Toast.LENGTH_SHORT
                    ).show()
                    openDocumentLauncher.launch(suffix)
                }

                pasteButton.setOnClickListener {
                    PasteFile.getInstance().pasteFiles(
                        requireActivity(),
                        fileRecyclerView.fullPath,
                        object : FileCopyHandler.FileExtensionGetter {
                            override fun onGet(file: File?): String? {
                                return file?.let { getFileSuffix(it) }
                            }
                        },
                        Task.runTask(TaskExecutors.getAndroidUI()) {
                            closeMultiSelect()
                            pasteButton.visibility = View.GONE
                            refreshAndReapply()
                        }
                    )
                }

                createFolderButton.setOnClickListener { goDownloadMod() }

                searchButton.setOnClickListener {
                    closeMultiSelect()
                    mSearchViewWrapper.setVisibility()
                }

                refreshButton.setOnClickListener {
                    closeMultiSelect()
                    refreshAndReapply()
                }
            }

            goDownloadText.setOnClickListener { goDownloadMod() }
            checkUpdatesModrinthButton.setOnClickListener { runModrinthUpdateCheck() }
            checkUpdatesCurseforgeButton.setOnClickListener { runCurseForgeUpdateCheck() }

            fileRecyclerView.lockAndListAt(File(mRootPath), File(mRootPath))
        }

        startNewbieGuide()
    }
    private fun hasAnyVisibleUpdates(): Boolean {
        return mUpdateResults.values.any { it.status == UpdateUiStatus.UPDATE_AVAILABLE }
    }

    private fun openFileActions(selectedFile: File) {
        val fileName = selectedFile.name
        val isModFile = fileName.endsWith(ModUtils.JAR_FILE_SUFFIX) ||
                fileName.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)
        val updateInfo = mUpdateResults[selectedFile.absolutePath]
        val updateAvailable = updateInfo?.status == UpdateUiStatus.UPDATE_AVAILABLE

        val filesButton = FilesButton().apply {
            setButtonVisibility(true, true, true, true, true, isModFile, updateAvailable)
            setMessageText(getString(R.string.file_message))

            if (fileName.endsWith(ModUtils.JAR_FILE_SUFFIX)) {
                setMoreButtonText(getString(R.string.profile_mods_disable))
            } else if (fileName.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)) {
                setMoreButtonText(getString(R.string.profile_mods_enable))
            }

            if (updateAvailable) {
                setExtraButtonText("Update Mod")
            }
        }

        val filesDialog = FilesDialog(
            requireContext(),
            filesButton,
            Task.runTask(TaskExecutors.getAndroidUI()) { refreshAndReapply() },
            binding.fileRecyclerView.fullPath,
            selectedFile
        )

        filesDialog.setCopyButtonClick { binding.operateView.pasteButton.visibility = View.VISIBLE }

        when {
            fileName.endsWith(ModUtils.JAR_FILE_SUFFIX) -> {
                filesDialog.setFileSuffix(ModUtils.JAR_FILE_SUFFIX)
                filesDialog.setMoreButtonClick {
                    ModUtils.disableMod(selectedFile)
                    refreshAndReapply()
                    filesDialog.dismiss()
                }
            }
            fileName.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX) -> {
                filesDialog.setFileSuffix(ModUtils.DISABLE_JAR_FILE_SUFFIX)
                filesDialog.setMoreButtonClick {
                    ModUtils.enableMod(selectedFile)
                    refreshAndReapply()
                    filesDialog.dismiss()
                }
            }
        }

        if (updateAvailable) {
            filesDialog.setExtraButtonClick {
                val dialog = ZHTools.showTaskRunningDialog(requireContext())
                Task.runTask {
                    ModrinthModUpdater.updateSingleMod(
                        context = requireContext(),
                        installedFile = selectedFile,
                        minecraftVersion = mMinecraftVersion
                    )
                }.ended(TaskExecutors.getAndroidUI()) { result ->
                    Toast.makeText(requireContext(), result?.message ?: "Update finished", Toast.LENGTH_SHORT).show()
                    mUpdateResults.remove(selectedFile.absolutePath)
                    refreshAndReapply()
                }.onThrowable { e ->
                    Tools.showErrorRemote(e)
                }.finallyTask(TaskExecutors.getAndroidUI()) {
                    dialog.dismiss()
                }.execute()
            }
        }

        filesDialog.show()
    }

    private fun openMultiSelectActions(itemBeans: List<FileItemBean>) {
        Task.runTask {
            val selectedFiles: MutableList<File> = ArrayList()
            itemBeans.forEach(Consumer { value: FileItemBean ->
                val file = value.file
                file?.apply { selectedFiles.add(this) }
            })
            selectedFiles
        }.ended(TaskExecutors.getAndroidUI()) { selectedFiles ->
            val filesButton = FilesButton().apply {
                setButtonVisibility(true, true, false, false, true, true)
                setDialogText(
                    getString(R.string.file_multi_select_mode_title),
                    getString(R.string.file_multi_select_mode_message, itemBeans.size),
                    getString(R.string.profile_mods_disable_or_enable)
                )
            }

            val filesDialog = FilesDialog(
                requireContext(),
                filesButton,
                Task.runTask(TaskExecutors.getAndroidUI()) {
                    closeMultiSelect()
                    refreshAndReapply()
                },
                binding.fileRecyclerView.fullPath,
                selectedFiles!!
            )
            filesDialog.setCopyButtonClick { binding.operateView.pasteButton.visibility = View.VISIBLE }
            filesDialog.setMoreButtonClick {
                ModToggleHandler(
                    requireContext(),
                    selectedFiles,
                    Task.runTask(TaskExecutors.getAndroidUI()) {
                        closeMultiSelect()
                        refreshAndReapply()
                    }
                ).start()
            }
            filesDialog.show()
        }.execute()
    }

    private fun runModrinthUpdateCheck() {
        mActiveUpdateSource = UpdateSource.MODRINTH
        closeMultiSelect()
        val dialog = ZHTools.showTaskRunningDialog(requireContext())

        Task.runTask {
            val modFiles = File(mRootPath).listFiles()
                ?.filter { file ->
                    file.isFile && (
                            file.name.endsWith(ModUtils.JAR_FILE_SUFFIX) ||
                                    file.name.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)
                            )
                }
                .orEmpty()

            val updateAvailable = mutableListOf<String>()
            val upToDate = mutableListOf<String>()
            val unknown = mutableListOf<String>()
            val statusMap = mutableMapOf<String, UpdateUiInfo>()

            for (file in modFiles) {
                val result = runCatching {
                    ModrinthUpdateChecker.checkForUpdate(
                        context = requireContext(),
                        file = file,
                        minecraftVersion = mMinecraftVersion
                    )
                }.onFailure {
                    com.movtery.zalithlauncher.feature.log.Logging.e(
                        "ModsFragment",
                        "Modrinth update check failed for ${file.absolutePath}",
                        it
                    )
                }.getOrNull()

                val label = result?.projectTitle
                    ?: ModJarIconHelper.read(requireContext(), file)?.displayName
                    ?: file.name

                when (result?.status) {
                    ModrinthUpdateChecker.UpdateStatus.UPDATE_AVAILABLE -> {
                        val detail = "Update: ${result.installedVersion ?: "?"} → ${result.latestVersion ?: "?"}"
                        updateAvailable.add("$label (${result.installedVersion ?: "?"} → ${result.latestVersion ?: "?"})")
                        statusMap[file.absolutePath] = UpdateUiInfo(UpdateUiStatus.UPDATE_AVAILABLE, detail)
                    }

                    ModrinthUpdateChecker.UpdateStatus.UP_TO_DATE -> {
                        upToDate.add(label)
                        statusMap[file.absolutePath] = UpdateUiInfo(UpdateUiStatus.UP_TO_DATE, "Up to date")
                    }

                    else -> {
                        val reason = result?.reason ?: "Update status unknown"
                        unknown.add(label)
                        statusMap[file.absolutePath] = UpdateUiInfo(UpdateUiStatus.UNKNOWN, reason)
                    }
                }
            }

            ScanResult(updateAvailable, upToDate, unknown, statusMap)
        }.ended(TaskExecutors.getAndroidUI()) { result ->
            val safeResult = result ?: ScanResult(
                mutableListOf(),
                mutableListOf(),
                mutableListOf(),
                mutableMapOf()
            )

            mUpdateResults.clear()
            mUpdateResults.putAll(safeResult.statuses)
            mAllModItems = binding.fileRecyclerView.adapter.data.toMutableList()

            val hasUpdates = safeResult.updates.isNotEmpty()
            binding.showUpdatesOnly.visibility = if (hasUpdates) View.VISIBLE else View.GONE
            if (!hasUpdates) mShowUpdatesOnly = false
            binding.showUpdatesOnly.isChecked = mShowUpdatesOnly

            applyUpdateResultsToVisibleItems()

            Toast.makeText(
                requireContext(),
                "Modrinth updates: ${safeResult.updates.size}\nUp to date: ${safeResult.current.size}" +
                        (if (safeResult.unknown.isNotEmpty()) "\nUnknown: ${safeResult.unknown.size}" else ""),
                Toast.LENGTH_LONG
            ).show()
        }.onThrowable { e ->
            Tools.showErrorRemote(e)
        }.finallyTask(TaskExecutors.getAndroidUI()) {
            dialog.dismiss()
        }.execute()
    }

    private fun runCurseForgeUpdateCheck() {
        mActiveUpdateSource = UpdateSource.CURSEFORGE
        mUpdateResults.clear()
        mShowUpdatesOnly = false
        binding.showUpdatesOnly.isChecked = false
        binding.showUpdatesOnly.visibility = View.GONE
        applyUpdateResultsToVisibleItems()
        Toast.makeText(requireContext(), "CurseForge update checker not wired yet", Toast.LENGTH_SHORT).show()
    }

    private fun refreshAndReapply() {
        binding.fileRecyclerView.refreshPath()
        if (mUpdateResults.isEmpty()) {
            mAllModItems.clear()
            binding.showUpdatesOnly.visibility = View.GONE
            mShowUpdatesOnly = false
        }
        applyUpdateResultsToVisibleItems()
    }

    private fun startNewbieGuide() {
        if (NewbieGuideUtils.showOnlyOne(TAG)) return
        binding.operateView.apply {
            val fragmentActivity = requireActivity()
            TapTargetSequence(fragmentActivity)
                .targets(
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, refreshButton, getString(R.string.generic_refresh), getString(R.string.newbie_guide_general_refresh)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, searchButton, getString(R.string.generic_search), getString(R.string.newbie_guide_mod_search)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, addFileButton, getString(R.string.profile_mods_add_mod), getString(R.string.newbie_guide_mod_import)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, createFolderButton, getString(R.string.profile_mods_download_mod), getString(R.string.newbie_guide_mod_download)),
                    NewbieGuideUtils.getSimpleTarget(fragmentActivity, returnButton, getString(R.string.generic_close), getString(R.string.newbie_guide_general_close))
                )
                .start()
        }
    }

    private fun closeMultiSelect() {
        binding.apply {
            multiSelectFiles.isChecked = false
            selectAll.visibility = View.GONE
            updateActionsRow.visibility = View.VISIBLE
            showUpdatesOnly.visibility = getUpdatesOnlyVisibility()
        }
    }

    private fun getUpdatesOnlyVisibility(): Int {
        val hasUpdates = mUpdateResults.values.any { it.status == UpdateUiStatus.UPDATE_AVAILABLE }
        return if (hasUpdates && mActiveUpdateSource != null) View.VISIBLE else View.GONE
    }

    private fun getFileSuffix(file: File): String {
        val name = file.name
        return when {
            name.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX) -> ModUtils.DISABLE_JAR_FILE_SUFFIX
            name.endsWith(ModUtils.JAR_FILE_SUFFIX) -> ModUtils.JAR_FILE_SUFFIX
            else -> {
                val dotIndex = file.name.lastIndexOf('.')
                if (dotIndex == -1) "" else file.name.substring(dotIndex)
            }
        }
    }

    private fun goDownloadMod() {
        closeMultiSelect()
        ZHTools.swapFragmentWithAnim(this, DownloadFragment::class.java, DownloadFragment.TAG, null)
    }

    private fun parseBundle() {
        val bundle = arguments ?: throw NullPointerException("The argument is null!")
        mRootPath = bundle.getString(BUNDLE_ROOT_PATH)
            ?: throw IllegalStateException("Root path is not set!")

        val rawVersion = bundle.getString(BUNDLE_MC_VERSION)
            ?: guessMinecraftVersionFromRootPath(mRootPath)

        mMinecraftVersion = extractMinecraftVersion(rawVersion)
    }

    private fun extractMinecraftVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("""\d+\.\d+(?:\.\d+)?""").find(raw)?.value
    }

    private fun guessMinecraftVersionFromRootPath(rootPath: String): String? {
        val modsDir = File(rootPath)
        val versionDir = modsDir.parentFile ?: return null
        return versionDir.name
    }

    private fun applyUpdateResultsToVisibleItems() {
        val sourceItems = if (mAllModItems.isNotEmpty()) {
            mAllModItems
        } else {
            binding.fileRecyclerView.adapter.data.toMutableList()
        }

        sourceItems.forEach { item ->
            item.updateStatus = UpdateUiStatus.NONE
            item.updateText = null

            val filePath = item.file?.absolutePath ?: return@forEach
            val updateInfo = mUpdateResults[filePath] ?: return@forEach

            item.updateStatus = updateInfo.status
            item.updateText = updateInfo.text
        }

        val visibleItems = if (mShowUpdatesOnly) {
            sourceItems.filter { it.updateStatus == UpdateUiStatus.UPDATE_AVAILABLE }
        } else {
            sourceItems
        }

        binding.fileRecyclerView.adapter.updateItems(visibleItems)
        setVisibilityAnim(binding.nothingLayout, visibleItems.isEmpty())
    }

    private fun initViews() {
        binding.apply {
            mSearchViewWrapper.apply {
                setSearchListener(object : SearchViewWrapper.SearchListener {
                    override fun onSearch(string: String?, caseSensitive: Boolean): Int {
                        return fileRecyclerView.searchFiles(string, caseSensitive)
                    }
                })
                setShowSearchResultsListener(object : SearchViewWrapper.ShowSearchResultsListener {
                    override fun onSearch(show: Boolean) {
                        fileRecyclerView.setShowSearchResultsOnly(show)
                    }
                })
            }

            fileRecyclerView.setFileIcon(FileIcon.MOD)

            operateView.apply {
                addFileButton.setContentDescription(getString(R.string.profile_mods_add_mod))
                createFolderButton.setContentDescription(getString(R.string.profile_mods_download_mod))
                createFolderButton.setImageDrawable(
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_download)
                )
                pasteButton.setVisibility(if (PasteFile.getInstance().pasteType != null) View.VISIBLE else View.GONE)

                ZHTools.setTooltipText(
                    returnButton,
                    addFileButton,
                    pasteButton,
                    createFolderButton,
                    searchButton,
                    refreshButton
                )
            }
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(modsLayout, Animations.BounceInDown))
                .apply(AnimPlayer.Entry(operateLayout, Animations.BounceInLeft))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(modsLayout, Animations.FadeOutUp))
                .apply(AnimPlayer.Entry(operateLayout, Animations.FadeOutRight))
        }
    }
}
