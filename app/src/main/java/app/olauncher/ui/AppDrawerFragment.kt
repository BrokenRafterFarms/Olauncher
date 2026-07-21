package app.olauncher.ui

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall
import androidx.activity.OnBackPressedCallback

class AppDrawerFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false
    private var currentFolders: Map<String, List<String>>? = null
    private var currentExpandedFolders: Set<String>? = null
    private var currentFolderPath = mutableListOf<String>()

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
        }

        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP)
            binding.search.queryHint = "Please select an app"
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.gravity = prefs.appLabelAlignment
        } catch (e: Exception) {
            e.printStackTrace()
        }

        _binding?.folderBack?.setOnClickListener {
            if (currentFolderPath.isNotEmpty()) {
                currentFolderPath.removeAt(currentFolderPath.size - 1)
                updateCombinedAppList()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentFolderPath.isNotEmpty()) {
                        currentFolderPath.removeAt(currentFolderPath.size - 1)
                        updateCombinedAppList()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            })
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    adapter.filter.filter(newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                viewModel.selectedApp(appModel, flag)
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    findNavController().popBackStack(R.id.mainFragment, false)
                else
                    findNavController().popBackStack()
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.FolderHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    findNavController().popBackStack()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    findNavController().navigate(R.id.action_appListFragment_to_settingsFragment2)
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.shortcutId
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            folderToggleListener = { folderName ->
                currentFolderPath.add(folderName)
                updateCombinedAppList()
            },
            folderSettingsListener = { showFolderSettingsDialog(it) },
            appFolderListener = { showAppFolderDialog(it) }
        )

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay().not())
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
    }

    private fun initObservers() {
        viewModel.showAppIcons.observe(viewLifecycleOwner) {
            adapter.showIcons = it
            adapter.notifyDataSetChanged()
        }
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
                viewModel.folders.observe(viewLifecycleOwner) {
                    currentFolders = it
                    updateCombinedAppList()
                }
                viewModel.expandedFolders.observe(viewLifecycleOwner) {
                    currentExpandedFolders = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        var combined = mutableListOf<AppModel>()
        val query = binding.search.query

        val folders = currentFolders ?: emptyMap()
        val currentFolder = currentFolderPath.lastOrNull()
        
        // Update Folder Header UI
        if (currentFolder != null) {
            _binding?.folderHeaderLayout?.visibility = View.VISIBLE
            _binding?.folderName?.text = currentFolder
        } else {
            _binding?.folderHeaderLayout?.visibility = View.GONE
        }

        if (flag == Constants.FLAG_LAUNCH_APP) {
            if (!query.isNullOrBlank()) {
                // Global search from root, or scoped search within folder
                if (currentFolder == null) {
                    // Global search: include all apps, ignore folders
                    combined.addAll(apps.filter { it is AppModel.App })
                } else {
                    // Scoped search: apps in this folder or its subfolders
                    val allAppsInFolder = getAllItemsInFolder(currentFolder, folders)
                        .filter { it.startsWith("pkg:") }
                        .map { it.removePrefix("pkg:") }
                        .toSet()
                    combined.addAll(apps.filter { it is AppModel.App && allAppsInFolder.contains(it.appPackage) })
                }
            } else {
                // No search query: show items in current folder or root items
                val itemsToShow = if (currentFolder == null) {
                    // Root items: apps/folders NOT in any other folder
                    val allItemsInAnyFolder = folders.values.flatten().toSet()
                    val appsInRoot = apps.filter { 
                        (it is AppModel.App || it is AppModel.PinnedShortcut) && 
                        !allItemsInAnyFolder.contains("pkg:${it.appPackage}") 
                    }
                    val foldersInRoot = folders.keys.filter { folderName ->
                        !allItemsInAnyFolder.contains("folder:$folderName")
                    }
                    
                    val list = mutableListOf<AppModel>()
                    list.addAll(appsInRoot)
                    list.addAll(foldersInRoot.map { AppModel.FolderHeader(it, false) })
                    // Alphabetical sorting
                    list.sortBy { it.appLabel.lowercase() }
                    list
                } else {
                    // Items in current folder
                    val items = folders[currentFolder] ?: emptyList()
                    val list = mutableListOf<AppModel>()
                    items.forEach { item ->
                        if (item.startsWith("pkg:")) {
                            val pkg = item.removePrefix("pkg:")
                            // Include all apps/shortcuts for this package to avoid missing items
                            list.addAll(apps.filter { (it is AppModel.App || it is AppModel.PinnedShortcut) && it.appPackage == pkg })
                        } else if (item.startsWith("folder:")) {
                            val folderName = item.removePrefix("folder:")
                            list.add(AppModel.FolderHeader(folderName, false))
                        }
                    }
                    // Alphabetical sorting
                    list.sortBy { it.appLabel.lowercase() }
                    list
                }
                combined.addAll(itemsToShow)
            }
        } else {
            // Other flags (Hidden, Home apps)
            combined.addAll(apps)
            combined.sortBy { it.appLabel.lowercase() }
        }
        
        // Final safeguard against duplicates
        combined = combined.distinctBy { 
            when (it) {
                is AppModel.App -> "pkg:${it.appPackage}|${it.user}"
                is AppModel.PinnedShortcut -> "shortcut:${it.shortcutId}|${it.user}"
                is AppModel.FolderHeader -> "folder:${it.appLabel}"
                else -> it.toString()
            }
        }.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable && currentFolder == null) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked || !query.isNullOrBlank()) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        adapter.setAppList(combined)
        adapter.filter.filter(query)
    }

    private fun getAllItemsInFolder(folderName: String, folders: Map<String, List<String>>): Set<String> {
        val result = mutableSetOf<String>()
        val items = folders[folderName] ?: return result
        result.addAll(items)
        items.forEach { item ->
            if (item.startsWith("folder:")) {
                result.addAll(getAllItemsInFolder(item.removePrefix("folder:"), folders))
            }
        }
        return result
    }

    private fun showFolderSettingsDialog(folderName: String) {
        val folders = viewModel.folders.value ?: emptyMap()
        val allItemsInAnyFolder = folders.values.flatten().toSet()
        val isNested = allItemsInAnyFolder.contains("folder:$folderName")
        
        val options = mutableListOf<String>()
        options.add(getString(R.string.rename_folder))
        options.add(getString(R.string.delete_folder))
        
        if (isNested) {
            options.add("Remove from parent folder")
        } else {
            options.add("Move to folder")
        }

        AlertDialog.Builder(requireContext())
            .setTitle(folderName)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folderName)
                    1 -> viewModel.deleteFolder(folderName)
                    2 -> {
                        if (isNested) {
                            viewModel.removeFolderFromParent(folderName)
                        } else {
                            showMoveFolderDialog(folderName)
                        }
                    }
                }
            }
            .show()
    }

    private fun showMoveFolderDialog(folderToMove: String) {
        val folders = viewModel.folders.value ?: emptyMap()
        val otherFolders = folders.keys.filter { it != folderToMove }.toList()
        
        if (otherFolders.isEmpty()) {
            requireContext().showToast("No other folders available")
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Move '$folderToMove' to:")
            .setItems(otherFolders.toTypedArray()) { _, which ->
                viewModel.addFolderToFolder(otherFolders[which], folderToMove)
            }
            .show()
    }

    private fun showRenameFolderDialog(oldName: String) {
        val input = EditText(requireContext())
        input.setText(oldName)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_folder)
            .setView(input)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    viewModel.renameFolder(oldName, newName)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showAppFolderDialog(app: AppModel) {
        val folders = viewModel.folders.value ?: emptyMap()
        val folderNames = folders.keys.toList()
        val options = mutableListOf<String>()
        options.add(getString(R.string.create_folder))
        options.addAll(folderNames)
        options.add(getString(R.string.remove_from_folder))

        AlertDialog.Builder(requireContext())
            .setTitle(app.appLabel)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showCreateFolderDialog(app)
                    options.size - 1 -> viewModel.removeAppFromFolder(app.appPackage)
                    else -> viewModel.addAppToFolder(options[which], app.appPackage)
                }
            }
            .show()
    }

    private fun showCreateFolderDialog(app: AppModel) {
        val input = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_folder)
            .setView(input)
            .setPositiveButton(R.string.folder) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    viewModel.createFolder(name)
                    viewModel.addAppToFolder(name, app.appPackage)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun initClickListeners() {
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            findNavController().popBackStack()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop && isRemoving.not())
                                binding.search.showKeyboard(prefs.autoShowKeyboard)
                    }
                }
            }
        }
    }

    private fun checkMessageAndExit() {
        findNavController().popBackStack()
        if (flag == Constants.FLAG_LAUNCH_APP)
            viewModel.checkForMessages.call()
    }

    override fun onStart() {
        super.onStart()
        binding.search.showKeyboard(prefs.autoShowKeyboard)
        
        // Reset navigation to root and refresh list on re-entry
        currentFolderPath.clear()
        
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.getHiddenApps()
        } else {
            val includeHidden = flag != Constants.FLAG_LAUNCH_APP
            viewModel.getAppList(includeHidden)
        }

        if (flag == Constants.FLAG_LAUNCH_APP) {
            viewModel.getPrivateSpaceAppList()
        }
        updateCombinedAppList()
    }

    override fun onStop() {
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchTextView = null
        _binding = null
    }
}
