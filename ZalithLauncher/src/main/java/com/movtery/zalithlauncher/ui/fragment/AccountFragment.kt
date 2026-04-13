package com.movtery.zalithlauncher.ui.fragment

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.angcyo.tablayout.DslTabLayout
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.FragmentAccountBinding
import com.movtery.zalithlauncher.databinding.ItemOtherServerBinding
import com.movtery.zalithlauncher.databinding.ViewAddOtherServerBinding
import com.movtery.zalithlauncher.databinding.ViewSingleActionPopupBinding
import com.movtery.zalithlauncher.event.single.AccountUpdateEvent
import com.movtery.zalithlauncher.event.value.LocalLoginEvent
import com.movtery.zalithlauncher.event.value.OtherLoginEvent
import com.movtery.zalithlauncher.feature.accounts.AccountUtils
import com.movtery.zalithlauncher.feature.accounts.AccountsManager
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils.CheckResultListener
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils.Companion.checkUsageAllowed
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils.Companion.openDialog
import com.movtery.zalithlauncher.feature.accounts.OtherLoginHelper
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.login.OtherLoginApi
import com.movtery.zalithlauncher.feature.login.Servers
import com.movtery.zalithlauncher.feature.login.Servers.Server
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog
import com.movtery.zalithlauncher.ui.dialog.OtherLoginDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.ui.layout.AnimRelativeLayout
import com.movtery.zalithlauncher.ui.subassembly.account.AccountAdapter
import com.movtery.zalithlauncher.ui.subassembly.account.AccountAdapter.AccountUpdateListener
import com.movtery.zalithlauncher.ui.subassembly.account.AccountViewWrapper
import com.movtery.zalithlauncher.ui.subassembly.account.SelectAccountListener
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.http.NetworkUtils
import com.movtery.zalithlauncher.utils.path.PathManager
import com.movtery.zalithlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.fragments.MicrosoftLoginFragment
import net.kdt.pojavlaunch.value.MinecraftAccount
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

// Handles managing accounts DNA Mobile
class AccountFragment : FragmentWithAnim(R.layout.fragment_account), View.OnClickListener {
    companion object {
        const val TAG = "AccountFragment"
    }

    private lateinit var binding: FragmentAccountBinding
    private lateinit var accountViewWrapper: AccountViewWrapper
    private lateinit var progressDialog: AlertDialog

    private val accountsData: MutableList<MinecraftAccount> =
        AccountsManager.allAccounts.toMutableList()
    private val accountAdapter = AccountAdapter(accountsData)

    private val selectAccountListener = object : SelectAccountListener {
        override fun onSelect(account: MinecraftAccount) {
            if (!isTaskRunning()) {
                AccountsManager.currentAccount = account
            } else {
                TaskExecutors.runInUIThread {
                    Toast.makeText(
                        requireActivity(),
                        R.string.tasks_ongoing,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private val serverActionPopupWindow: PopupWindow = PopupWindow().apply {
        isFocusable = true
        isOutsideTouchable = true
    }

    private val localNamePattern = Pattern.compile("[^a-zA-Z0-9_]")

    private var otherServerConfig: Servers? = null
    private val otherServerConfigFile = File(PathManager.DIR_GAME_HOME, "servers.json")
    private val otherServerList: MutableList<Server> = ArrayList()
    private val otherServerViewList: MutableList<View> = ArrayList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAccountBinding.inflate(layoutInflater)
        accountViewWrapper = AccountViewWrapper(binding = binding.viewAccount)
        progressDialog = ZHTools.createTaskRunningDialog(binding.root.context)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireActivity()

        accountAdapter.setAccountUpdateListener(object : AccountUpdateListener {
            override fun onViewClick(account: MinecraftAccount) {
                selectAccountListener.onSelect(account)
            }

            override fun onRefresh(account: MinecraftAccount) {
                if (!isTaskRunning()) {
                    if (!NetworkUtils.isNetworkAvailable(context)) {
                        Toast.makeText(
                            context,
                            R.string.account_login_no_network,
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    AccountsManager.performLogin(context, account)
                } else {
                    Toast.makeText(context, R.string.tasks_ongoing, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDelete(account: MinecraftAccount) {
                TipDialog.Builder(context)
                    .setTitle(R.string.generic_warning)
                    .setMessage(R.string.account_remove)
                    .setConfirm(R.string.generic_delete)
                    .setWarning()
                    .setConfirmClickListener {
                        val accountFile = File(PathManager.DIR_ACCOUNT_NEW, account.uniqueUUID)
                        val userSkinFile = File(PathManager.DIR_USER_SKIN, "${account.uniqueUUID}.png")

                        if (accountFile.exists()) FileUtils.deleteQuietly(accountFile)
                        if (userSkinFile.exists()) FileUtils.deleteQuietly(userSkinFile)

                        reloadAccounts()
                    }
                    .showDialog()
            }
        })

        binding.apply {
            accountsRecycler.layoutManager = LinearLayoutManager(context)
            accountsRecycler.setLayoutAnimation(
                LayoutAnimationController(
                    AnimationUtils.loadAnimation(context, R.anim.fade_downwards)
                )
            )
            accountsRecycler.adapter = accountAdapter

            accountTypeTab.observeIndexChange { _, toIndex, _, fromUser ->
                fun handleNonMicrosoftLogin(message: Int, login: () -> Unit) {
                    checkUsageAllowed(object : CheckResultListener {
                        override fun onUsageAllowed() {
                            login()
                        }

                        override fun onUsageDenied() {
                            if (!AllSettings.localAccountReminders.getValue()) {
                                login()
                            } else {
                                openDialog(
                                    context,
                                    TipDialog.OnConfirmClickListener { checked ->
                                        LocalAccountUtils.saveReminders(checked)
                                        login()
                                    },
                                    getString(message) + getString(R.string.account_purchase_minecraft_account_tip),
                                    R.string.account_no_microsoft_account_continue
                                )
                            }
                        }
                    })
                }

                // We only want to respond to direct user taps.
                // Otherwise, it may keep opening the Microsoft login screen repeatedly.
                if (fromUser) {
                    when (toIndex) {
                        // Microsoft account
                        0 -> ZHTools.swapFragmentWithAnim(
                            this@AccountFragment,
                            MicrosoftLoginFragment::class.java,
                            MicrosoftLoginFragment.TAG,
                            null
                        )

                        // Offline account
                        1 -> {
                            handleNonMicrosoftLogin(R.string.account_no_microsoft_account_local) {
                                localLogin()
                            }
                        }

                        // External / third-party account
                        else -> {
                            handleNonMicrosoftLogin(R.string.account_no_microsoft_account_other) {
                                // Server index starts from 0.
                                otherLogin(toIndex - 2)
                            }
                        }
                    }
                }
            }

            addServer.setOnClickListener(this@AccountFragment)
            returnButton.setOnClickListener(this@AccountFragment)
        }

        reloadAccounts()
        refreshOtherServer()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun reloadRecyclerView() {
        accountsData.clear()
        accountsData.addAll(AccountsManager.allAccounts)
        accountAdapter.notifyDataSetChanged()
        binding.accountsRecycler.scheduleLayoutAnimation()
    }

    private fun reloadAccounts() {
        Task.runTask {
            AccountsManager.reload()
        }.ended(TaskExecutors.getAndroidUI()) {
            reloadRecyclerView()
            accountViewWrapper.refreshAccountInfo()
        }.execute()
    }

    private fun SpannableString.applySpan(start: Int, end: Int, what: Any) {
        setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun localLogin() {
        fun startLogin(name: String) {
            EventBus.getDefault().post(LocalLoginEvent(name.trim()))
        }

        EditTextDialog.Builder(requireActivity())
            .setTitle(R.string.account_login_local_name)
            .setConfirmText(R.string.generic_login)
            .setEmptyErrorText(R.string.account_local_account_empty)
            .setAsRequired()
            .setConfirmListener { editText, _ ->
                val name = editText.text.toString()

                if (name.length <= 2 || name.length > 16 || localNamePattern.matcher(name).find()) {
                    TipDialog.Builder(requireContext())
                        .setTitle(R.string.generic_warning)
                        .setMessage(R.string.account_local_account_invalid)
                        .setWarning()
                        .setTextBeautifier { _, messageText ->
                            val text = messageText.text.toString()
                            val startTag = "[RED;BOLD]"
                            val endTag = "[/RED;BOLD]"

                            val startIndex = text.indexOf(startTag)
                            val endIndex = text.indexOf(endTag)

                            if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                                val styledText =
                                    text.substring(startIndex + startTag.length, endIndex)
                                val plainText = text.replace(startTag, "").replace(endTag, "")
                                val adjustedEndIndex = startIndex + styledText.length

                                val spannableString = SpannableString(plainText)
                                spannableString.applySpan(
                                    startIndex,
                                    adjustedEndIndex,
                                    ForegroundColorSpan(Color.RED)
                                )
                                spannableString.applySpan(
                                    startIndex,
                                    adjustedEndIndex,
                                    StyleSpan(Typeface.BOLD)
                                )

                                messageText.text = spannableString
                            }
                        }
                        .setCenterMessage(false)
                        .setConfirmClickListener { startLogin(name) }
                        .setCancelable(false)
                        .setConfirmButtonCountdown(3000L)
                        .showDialog()
                } else {
                    startLogin(name)
                }

                true
            }
            .showDialog()
    }

    private fun otherLogin(index: Int) {
        val server = otherServerList[index]

        OtherLoginDialog(
            requireActivity(),
            server,
            object : OtherLoginHelper.OnLoginListener {
                override fun onLoading() {
                    progressDialog.show()
                }

                override fun unLoading() {
                    progressDialog.dismiss()
                }

                override fun onSuccess(account: MinecraftAccount) {
                    EventBus.getDefault().post(OtherLoginEvent(account))
                }

                override fun onFailed(error: String) {
                    progressDialog.dismiss()

                    TipDialog.Builder(requireActivity())
                        .setTitle(R.string.generic_warning)
                        .setMessage(getString(R.string.other_login_error) + error)
                        .setWarning()
                        .setCancel(android.R.string.copy)
                        .setCancelClickListener {
                            StringUtils.copyText("error", error, requireActivity())
                        }
                        .showDialog()
                }
            }
        ).show()
    }

    private fun refreshOtherServer() {
        Task.runTask {
            otherServerList.clear()

            if (otherServerConfigFile.exists()) {
                runCatching {
                    val serverConfig = Tools.GLOBAL_GSON.fromJson(
                        Tools.read(otherServerConfigFile.absolutePath),
                        Servers::class.java
                    )
                    otherServerConfig = serverConfig
                    serverConfig.server.forEach { server ->
                        otherServerList.add(server)
                    }
                }
            }
        }.ended(TaskExecutors.getAndroidUI()) {
            // Add external servers to the account type tab.
            otherServerViewList.forEach { view ->
                binding.accountTypeTab.removeView(view)
            }
            otherServerViewList.clear()

            val activity = requireActivity()
            val layoutInflater = activity.layoutInflater

            fun createView(server: Server): AnimRelativeLayout {
                val padding = Tools.dpToPx(8f).toInt()
                val itemBinding = ItemOtherServerBinding.inflate(layoutInflater)

                itemBinding.text.text = server.serverName
                itemBinding.root.setOnLongClickListener { anchorView ->
                    refreshActionPopupWindow(
                        anchorView,
                        ViewSingleActionPopupBinding.inflate(LayoutInflater.from(activity)).apply {
                            icon.setImageDrawable(
                                ContextCompat.getDrawable(
                                    requireActivity(),
                                    R.drawable.ic_menu_delete_forever
                                )
                            )
                            text.setText(R.string.generic_delete)
                            text.setOnClickListener {
                                deleteOtherServer(server)
                                serverActionPopupWindow.dismiss()
                            }
                        }
                    )
                    true
                }

                itemBinding.root.layoutParams = DslTabLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                return itemBinding.root.apply {
                    setPadding(padding, 0, padding, 0)
                }
            }

            otherServerList.forEach { server ->
                val serverView = createView(server)
                otherServerViewList.add(serverView)
                binding.accountTypeTab.addView(serverView)
            }
        }.execute()
    }

    private fun showServerTypeSelectDialog(titleResId: Int, type: Int) {
        EditTextDialog.Builder(requireActivity())
            .setTitle(titleResId)
            .setAsRequired()
            .setConfirmListener { editText, _ ->
                addOtherServer(editText, type)
                true
            }
            .showDialog()
    }

    private fun ensureServerConfig() {
        if (otherServerConfig == null) {
            otherServerConfig = Servers().apply {
                server = ArrayList()
            }
        }
    }

    private fun normalizeSpecialServerUrl(url: String): String {
        return when (url.removeSuffix("/").lowercase()) {
            "https://ely.by",
            "http://ely.by",
            "https://www.ely.by",
            "http://www.ely.by",
            "https://account.ely.by",
            "http://account.ely.by" -> "https://authserver.ely.by"

            else -> url.removeSuffix("/")
        }
    }

    private fun addOtherServer(editText: EditText, type: Int) {
        Task.runTask {
            val input = editText.text.toString().trim()

            val rawServerUrl = if (type == 0) {
                AccountUtils.tryGetFullServerUrl(input)
            } else {
                input
            }

            val normalizedServerUrl = if (type == 0) {
                normalizeSpecialServerUrl(rawServerUrl)
            } else {
                rawServerUrl
            }

            val serverInfoUrl = if (type == 0) {
                normalizedServerUrl
            } else {
                "https://auth.mc-user.com:233/$normalizedServerUrl"
            }

            OtherLoginApi.getServeInfo(requireActivity(), serverInfoUrl)?.let { data ->
                val server = Server()

                JSONObject(data).optJSONObject("meta")?.let { meta ->
                    server.serverName = meta.optString("serverName")

                    if (type == 0) {
                        server.baseUrl = normalizedServerUrl
                        server.register = meta.optJSONObject("links")?.optString("register") ?: ""
                    } else {
                        server.baseUrl = "https://auth.mc-user.com:233/$normalizedServerUrl"
                        server.register = "https://login.mc-user.com:233/$normalizedServerUrl"
                    }

                    ensureServerConfig()
                    otherServerConfig?.server?.apply addServer@{
                        forEach {
                            // Make sure the same server is not added twice.
                            if (it.baseUrl == server.baseUrl) return@addServer
                        }
                        add(server)
                    }

                    Tools.write(
                        otherServerConfigFile.absolutePath,
                        Tools.GLOBAL_GSON.toJson(otherServerConfig, Servers::class.java)
                    )
                }
            }
        }.beforeStart(TaskExecutors.getAndroidUI()) {
            progressDialog.show()
        }.ended(TaskExecutors.getAndroidUI()) {
            refreshOtherServer()
            progressDialog.dismiss()
        }.onThrowable { e ->
            Logging.e("Add Other Server", Tools.printToString(e))
        }.execute()
    }

    private fun deleteOtherServer(server: Server) {
        TipDialog.Builder(requireActivity())
            .setTitle(getString(R.string.account_remove_login_type_title, server.serverName))
            .setMessage(R.string.account_remove_login_type_message)
            .setWarning()
            .setConfirmClickListener {
                ensureServerConfig()
                otherServerConfig?.server?.remove(server)

                Tools.write(
                    otherServerConfigFile.absolutePath,
                    Tools.GLOBAL_GSON.toJson(otherServerConfig, Servers::class.java)
                )

                refreshOtherServer()
            }
            .showDialog()
    }

    private fun refreshActionPopupWindow(anchorView: View, popupBinding: ViewBinding) {
        serverActionPopupWindow.apply {
            popupBinding.root.measure(0, 0)
            contentView = popupBinding.root
            width = popupBinding.root.measuredWidth
            height = popupBinding.root.measuredHeight
            showAsDropDown(anchorView)
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun event(event: AccountUpdateEvent) {
        accountViewWrapper.refreshAccountInfo()
        reloadRecyclerView()
    }

    override fun onClick(v: View) {
        val activity = requireActivity()

        binding.apply {
            when (v) {
                returnButton -> ZHTools.onBackPressed(activity)

                addServer -> {
                    refreshActionPopupWindow(
                        v,
                        ViewAddOtherServerBinding.inflate(LayoutInflater.from(activity)).apply {
                            val clickListener = View.OnClickListener { clickedView ->
                                when (clickedView) {
                                    addOtherServer -> {
                                        showServerTypeSelectDialog(
                                            R.string.other_login_yggdrasil_api,
                                            0
                                        )
                                    }

                                    addUniformPass -> {
                                        showServerTypeSelectDialog(
                                            R.string.other_login_32_bit_server,
                                            1
                                        )
                                    }
                                }
                                serverActionPopupWindow.dismiss()
                            }

                            addOtherServer.setOnClickListener(clickListener)
                            addUniformPass.setOnClickListener(clickListener)
                        }
                    )
                }
            }
        }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(operationLayout, Animations.BounceInLeft))
                .apply(AnimPlayer.Entry(accountTypeLayout, Animations.BounceInDown))
                .apply(AnimPlayer.Entry(accountsRecycler, Animations.BounceInUp))
        }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        binding.apply {
            animPlayer.apply(AnimPlayer.Entry(operationLayout, Animations.FadeOutRight))
                .apply(AnimPlayer.Entry(accountTypeLayout, Animations.FadeOutUp))
                .apply(AnimPlayer.Entry(accountsRecycler, Animations.FadeOutDown))
        }
    }
}