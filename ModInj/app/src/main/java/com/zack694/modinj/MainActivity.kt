package com.zack694.modinj

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.io.File

/**
 * ModInj — companion app for ZalithLauncher (Reborn).
 *
 * Three tabs, all RecyclerView-based (no Compose, no bitmaps → tiny footprint):
 *  1. Files   — browse the instance dirs through the launcher's provider and
 *               pick files (across multiple directories) to add to the vault.
 *  2. Vault   — the selected injection set, per instance, with removal.
 *  3. Backups — mid-game changed files captured during play, restorable.
 *
 * Notch handling: edge-to-edge with a black root background so the display
 * cutout area renders as black space.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var statusDot: View
    private lateinit var tabLayout: TabLayout
    private lateinit var pager: FrameLayout
    private lateinit var pages: Array<ViewGroup>

    // Files tab
    private lateinit var instancePicker: MaterialAutoCompleteTextView
    private lateinit var breadcrumb: TextView
    private lateinit var fileList: RecyclerView
    private lateinit var addButton: ExtendedFloatingActionButton
    private val instances = ArrayList<String>()
    private var currentInstance: String? = null
    private var currentDirRel = ""
    /**
     * Rel-path prefix of the game home inside the provider's anchor space.
     * Newer launchers anchor the provider at `<profile>/.minecraft` directly
     * (prefix ""); older builds anchor at the bare profile root, where game
     * data lives under `.minecraft/` (prefix ".minecraft"). Detected at load.
     */
    private var anchor = ""
    private val browseEntries = ArrayList<ModSyncClient.BrowseEntry>()
    private val checkedRelPaths = HashSet<String>()
    private lateinit var fileAdapter: FileAdapter

    // Vault tab
    private lateinit var vaultList: RecyclerView
    private lateinit var vaultAdapter: VaultAdapter
    private var vaultEntries = ArrayList<Vault.Entry>()

    // Backups tab
    private lateinit var backupList: RecyclerView
    private lateinit var backupAdapter: BackupAdapter
    private var backupEntries: List<Vault.BackupEntry> = emptyList()
    private lateinit var watchButton: MaterialButton

    private val fileExecutor = ThreadPool.single

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        // Black space on the notch/cutout: black background everywhere +
        // content padded below the system bars.
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompatCompat.applyInsetsPadding(root)

        statusView = findViewById(R.id.status)
        statusDot = findViewById(R.id.status_dot)
        tabLayout = findViewById(R.id.tabs)
        pager = findViewById(R.id.pager)
        pages = arrayOf(
            findViewById(R.id.page_files),
            findViewById(R.id.page_vault),
            findViewById(R.id.page_backups)
        )

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                pages.forEachIndexed { i, p -> p.visibility = if (i == tab.position) View.VISIBLE else View.GONE }
                when (tab.position) {
                    0 -> refreshStatus()
                    1 -> refreshVault()
                    2 -> refreshBackups()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        setupFilesTab()
        setupVaultTab()
        setupBackupsTab()

        tabLayout.selectTab(tabLayout.getTabAt(0))
        refreshStatus()
        loadInstances()
    }

    // ------------------------------------------------------------- status

    private fun refreshStatus() {
        fileExecutor.execute {
            val ok = ModSyncClient.ping(this)
            runOnUiThread {
                statusView.text = if (ok) {
                    getString(R.string.status_connected, ModSyncClient.launcherPackage)
                } else {
                    getString(R.string.status_disconnected)
                }
                val color = ContextCompat.getColor(this, if (ok) R.color.ok else R.color.warn)
                statusView.setTextColor(color)
                statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            }
        }
    }

    // ------------------------------------------------------------- files

    private fun setupFilesTab() {
        instancePicker = findViewById(R.id.instance_picker)
        breadcrumb = findViewById(R.id.breadcrumb)
        fileList = findViewById(R.id.file_list)
        addButton = findViewById(R.id.add_selected)

        fileAdapter = FileAdapter { entry -> toggleChecked(entry) }
        fileList.layoutManager = LinearLayoutManager(this)
        fileList.adapter = fileAdapter

        instancePicker.setOnItemClickListener { _, _, position, _ ->
            instances.getOrNull(position)?.let { selectInstance(it) }
        }

        addButton.setOnClickListener { addCheckedToVault() }
        addButton.text = getString(R.string.add_selected, 0)
    }

    /** Rel path (vs game home) of the instance's isolated directory. */
    private fun instanceRootRel(): String {
        val inst = currentInstance ?: return ""
        return if (anchor.isEmpty()) "versions/$inst" else "$anchor/versions/$inst"
    }

    private fun displayRel(relPath: String): String =
        if (anchor.isEmpty()) relPath else relPath.removePrefix("$anchor/")

    /**
     * Detects where `versions/` sits inside the provider's anchor space by
     * listing the root: new anchors already ARE the game home (they contain
     * `versions`); older ones expose game data under a `.minecraft` directory.
     */
    private fun detectAnchor(): String {
        val root = ModSyncClient.list(this, ".") ?: return ""
        if (root.any { it.isDir && it.name == "versions" }) return ""
        if (root.any { it.isDir && it.name == ".minecraft" }) return ".minecraft"
        return ""
    }

    private fun loadInstances() {
        fileExecutor.execute {
            ModSyncClient.ping(this)
            anchor = detectAnchor()
            val versionsRel = if (anchor.isEmpty()) "versions" else "$anchor/versions"
            val list = ModSyncClient.list(this, versionsRel)
            runOnUiThread {
                instances.clear()
                if (list == null) {
                    Toast.makeText(this, R.string.instances_load_failed, Toast.LENGTH_LONG).show()
                } else {
                    list.filter { it.isDir }.forEach { instances.add(it.name) }
                }
                if (instances.isEmpty()) {
                    findViewById<View>(R.id.empty_files).visibility = View.VISIBLE
                } else {
                    findViewById<View>(R.id.empty_files).visibility = View.GONE
                    instancePicker.setSimpleItems(instances.toTypedArray())
                    // Default-select the first instance (e.g. "1.21.11 Fabric")
                    // so the Files tab is usable without extra taps.
                    if (currentInstance == null || instances.indexOf(currentInstance) < 0) {
                        selectInstance(instances[0])
                    }
                }
            }
        }
    }

    private fun selectInstance(name: String) {
        currentInstance = name
        instancePicker.setText(name, false)
        checkedRelPaths.clear()
        addButton.text = getString(R.string.add_selected, 0)
        openDir(instanceRootRel())
    }

    private fun openDir(relPath: String) {
        currentDirRel = relPath
        breadcrumb.text = getString(R.string.breadcrumb, currentInstance ?: "—", displayRel(relPath).ifEmpty { "/" })
        fileExecutor.execute {
            val list = ModSyncClient.list(this, relPath)
            runOnUiThread {
                browseEntries.clear()
                if (list == null) {
                    Toast.makeText(this, R.string.browse_failed, Toast.LENGTH_SHORT).show()
                } else {
                    // Directories first, then files; both name-sorted.
                    browseEntries.addAll(
                        list.sortedWith(compareByDescending<ModSyncClient.BrowseEntry> { it.isDir }.thenBy { it.name.lowercase() })
                    )
                }
                fileAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun toggleChecked(entry: ModSyncClient.BrowseEntry) {
        if (checkedRelPaths.contains(entry.relPath)) checkedRelPaths.remove(entry.relPath)
        else checkedRelPaths.add(entry.relPath)
        addButton.text = getString(R.string.add_selected, checkedRelPaths.size)
        fileAdapter.notifyDataSetChanged()
    }

    private fun addCheckedToVault() {
        val inst = currentInstance ?: run {
            Toast.makeText(this, R.string.pick_instance_first, Toast.LENGTH_SHORT).show()
            return
        }
        val toAdd = ArrayList(checkedRelPaths)
        if (toAdd.isEmpty()) return
        fileExecutor.execute {
            var added = 0
            toAdd.forEach { rel ->
                if (Vault.importFromLauncher(this, inst, rel) != null) added++
            }
            runOnUiThread {
                checkedRelPaths.clear()
                addButton.text = getString(R.string.add_selected, 0)
                fileAdapter.notifyDataSetChanged()
                Toast.makeText(this, getString(R.string.added_n, added), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class FileAdapter(val onToggle: (ModSyncClient.BrowseEntry) -> Unit) :
        RecyclerView.Adapter<FileAdapter.Holder>() {

        inner class Holder(val view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.item_name)
            val sub: TextView = view.findViewById(R.id.item_sub)
            val icon: ImageView = view.findViewById(R.id.item_icon)
            val check: View = view.findViewById(R.id.item_check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_browse, parent, false))

        override fun getItemCount(): Int = browseEntries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = browseEntries[position]
            holder.name.text = entry.name
            holder.sub.text = if (entry.isDir) {
                getString(R.string.folder)
            } else {
                formatSize(entry.size)
            }
            holder.icon.setImageResource(if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_file)
            holder.icon.contentDescription = if (entry.isDir) getString(R.string.cd_folder) else null
            val checked = checkedRelPaths.contains(entry.relPath)
            holder.check.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnClickListener {
                if (entry.isDir) {
                    checkedRelPaths.clear()
                    addButton.text = getString(R.string.add_selected, 0)
                    openDir(entry.relPath)
                } else {
                    onToggle(entry)
                }
            }
        }
    }

    // ------------------------------------------------------------- vault

    private fun setupVaultTab() {
        vaultList = findViewById(R.id.vault_list)
        vaultAdapter = VaultAdapter()
        vaultList.layoutManager = LinearLayoutManager(this)
        vaultList.adapter = vaultAdapter
    }

    private fun refreshVault() {
        vaultEntries = ArrayList(Vault.load(this))
        vaultAdapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty_vault).visibility =
            if (vaultEntries.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class VaultAdapter : RecyclerView.Adapter<VaultAdapter.Holder>() {
        inner class Holder(val view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.item_name)
            val sub: TextView = view.findViewById(R.id.item_sub)
            val action: TextView = view.findViewById(R.id.item_action)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_actionable, parent, false))

        override fun getItemCount(): Int = vaultEntries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = vaultEntries[position]
            val file = entry.relPath.substringAfterLast('/')
            holder.name.text = file
            holder.sub.text = "${entry.instance} · ${entry.relPath}"
            holder.action.text = getString(R.string.remove)
            holder.action.setOnClickListener {
                Vault.remove(this@MainActivity, entry)
                refreshVault()
            }
        }
    }

    // ------------------------------------------------------------- backups

    private fun setupBackupsTab() {
        backupList = findViewById(R.id.backup_list)
        backupAdapter = BackupAdapter()
        backupList.layoutManager = LinearLayoutManager(this)
        backupList.adapter = backupAdapter
        watchButton = findViewById(R.id.watch_toggle)
        watchButton.setOnClickListener { toggleWatch() }
    }

    private fun toggleWatch() {
        if (ModWatchService.isRunning) {
            startService(Intent(this, ModWatchService::class.java).setAction(ModWatchService.ACTION_STOP_WATCH))
            watchButton.text = getString(R.string.start_watching)
            watchButton.setIconResource(R.drawable.ic_play)
        } else {
            ensureNotificationPermission()
            startForegroundService(Intent(this, ModWatchService::class.java))
            watchButton.text = getString(R.string.stop_watching)
            watchButton.setIconResource(R.drawable.ic_stop)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun refreshBackups() {
        fileExecutor.execute {
            val entries = Vault.backupEntries(this)
            runOnUiThread {
                backupEntries = entries
                backupAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.empty_backups).visibility =
                    if (backupEntries.isEmpty()) View.VISIBLE else View.GONE
                watchButton.text =
                    if (ModWatchService.isRunning) getString(R.string.stop_watching)
                    else getString(R.string.start_watching)
                watchButton.setIconResource(
                    if (ModWatchService.isRunning) R.drawable.ic_stop else R.drawable.ic_play
                )
            }
        }
    }

    private inner class BackupAdapter : RecyclerView.Adapter<BackupAdapter.Holder>() {
        inner class Holder(val view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.item_name)
            val sub: TextView = view.findViewById(R.id.item_sub)
            val action: TextView = view.findViewById(R.id.item_action)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_actionable, parent, false))

        override fun getItemCount(): Int = backupEntries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = backupEntries[position]
            holder.name.text = entry.relPath.substringAfterLast('/')
            holder.sub.text = "${entry.instance} · ${entry.relPath}"
            holder.action.text = getString(R.string.restore)
            holder.action.setOnClickListener {
                fileExecutor.execute {
                    val ok = Vault.restoreBackupToVault(
                        this@MainActivity, entry.instance, entry.relPath
                    )
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            if (ok) R.string.restore_ok else R.string.restore_fail,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- util

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> getString(R.string.size_mb, bytes / 1048576.0)
        bytes >= 1024 -> getString(R.string.size_kb, bytes / 1024.0)
        else -> getString(R.string.size_b, bytes)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshVault()
        refreshBackups()
    }
}
