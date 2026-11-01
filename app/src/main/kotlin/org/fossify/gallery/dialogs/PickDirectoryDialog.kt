package org.fossify.gallery.dialogs

import android.content.Context
import android.graphics.Color
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.CreateNewFolderDialog
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beInvisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getDefaultCopyDestinationPath
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isInDownloadDir
import org.fossify.commons.extensions.isRestrictedWithSAFSdk30
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MySearchMenu
import org.fossify.gallery.R
import org.fossify.gallery.adapters.DirectoryAdapter
import org.fossify.gallery.databinding.DialogDirectoryPickerBinding
import org.fossify.gallery.extensions.addTempFolderIfNeeded
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createNavigationDir
import org.fossify.gallery.extensions.expandNavigationRoots
import org.fossify.gallery.extensions.getCachedDirectories
import org.fossify.gallery.extensions.getDirectorySortingValue
import org.fossify.gallery.extensions.getDirsToShow
import org.fossify.gallery.extensions.getDistinctPath
import org.fossify.gallery.extensions.getPathLocation
import org.fossify.gallery.extensions.getSortedDirectories
import org.fossify.gallery.extensions.navigationChildDirs
import org.fossify.gallery.extensions.shouldFolderBeVisible
import org.fossify.gallery.models.Directory
import java.io.File
import java.util.Locale

class PickDirectoryDialog(
    val activity: BaseSimpleActivity,
    val sourcePath: String,
    showOtherFolderButton: Boolean,
    val showFavoritesBin: Boolean,
    val isPickingCopyMoveDestination: Boolean,
    val isPickingFolderForWidget: Boolean,
    val callback: (path: String) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var shownDirectories = ArrayList<Directory>()
    private var allDirectories = ArrayList<Directory>()
    private var openedSubfolders = arrayListOf("")
    private var binding = DialogDirectoryPickerBinding.inflate(activity.layoutInflater)
    private var isGridViewType = activity.config.viewTypeFolders == VIEW_TYPE_GRID
    private var showHidden = activity.config.shouldShowHidden
    private var showExcluded = activity.config.temporarilyShowExcluded
    private var isLoading = false
    private var isTapInFlight = false
    private var pendingScrollPath: String? = null
    private var currentPathPrefix = ""
    private val config = activity.config
    private val searchView = binding.folderSearchView
    private val searchEditText = searchView.binding.topToolbarSearch
    private val searchBarContainer = searchView.binding.searchBarContainer

    init {
        (binding.directoriesGrid.layoutManager as MyGridLayoutManager).apply {
            orientation = if (activity.config.scrollHorizontally && isGridViewType) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL
            spanCount = if (isGridViewType) activity.config.dirColumnCnt else 1
        }

        binding.directoriesFastscroller.updateColors(activity.getProperPrimaryColor())
        binding.directoriesCreateFolder.setOnClickListener {
            val parentPath = currentPathPrefix
            if (parentPath.isNotEmpty()) {
                CreateNewFolderDialog(activity, parentPath) { newPath ->
                    pendingScrollPath = newPath
                    gotDirectories(allDirectories)
                }
            }
        }

        configureSearchView()

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.select_this_folder, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)

        if (showOtherFolderButton) {
            builder.setNeutralButton(R.string.other_folder) { dialogInterface, i -> showOtherFolder() }
        }

        builder.apply {
            activity.setupDialogStuff(binding.root, this, org.fossify.commons.R.string.select_destination) { alertDialog ->
                dialog = alertDialog
                alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    if (currentPathPrefix.isNotEmpty()) {
                        selectFolder(currentPathPrefix)
                    }
                }
                configureConfirmButton()
                binding.directoriesShowHidden.beVisibleIf(!context.config.shouldShowHidden)
                binding.directoriesShowHidden.setOnClickListener {
                    activity.handleHiddenFolderPasswordProtection {
                        binding.directoriesShowHidden.beGone()
                        showHidden = true
                        fetchDirectories(true)
                    }
                }

                alertDialog.onBackPressedDispatcher.addCallback(alertDialog) {
                    if (searchView.isSearchOpen) {
                        searchView.closeSearch()
                    } else if (currentPathPrefix.isNotEmpty()) {
                        openedSubfolders.removeAt(openedSubfolders.lastIndex)
                        currentPathPrefix = openedSubfolders.last()
                        gotDirectories(allDirectories)
                    } else {
                        isEnabled = false
                        alertDialog.onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }

        fetchDirectories(false)
    }

    private fun configureSearchView() = with(searchView) {
        updateHintText(context.getString(org.fossify.commons.R.string.search_folders))
        searchEditText.imeOptions = EditorInfo.IME_ACTION_DONE

        toggleHideOnScroll(!config.scrollHorizontally)
        setupMenu()

        onSearchOpenListener = {
            updateSearchViewLeftIcon(org.fossify.commons.R.drawable.ic_cross_vector)
        }

        onSearchClosedListener = {
            searchEditText.clearFocus()
            activity.hideKeyboard(searchEditText)
            updateSearchViewLeftIcon(org.fossify.commons.R.drawable.ic_search_vector)
        }

        onSearchTextChangedListener = { text ->
            filterFolderListBySearchQuery(text)
        }

        requireToolbar().beInvisible()
        updateColors()
        setBackgroundColor(Color.TRANSPARENT)
        searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun updateSearchViewLeftIcon(iconResId: Int) = with(searchView.binding.topToolbarSearchIcon) {
        post {
            setImageResource(iconResId)
        }
    }

    private fun filterFolderListBySearchQuery(query: String) {
        if (query.isEmpty()) {
            gotDirectories(allDirectories)
            return
        }

        val adapter = binding.directoriesGrid.adapter as? DirectoryAdapter
        val searchable = (allDirectories + shownDirectories).distinctBy { it.path.lowercase(Locale.getDefault()) }
        var dirsToShow = searchable.filter { it.name.contains(query, true) }.toMutableList() as ArrayList
        dirsToShow = activity.getSortedDirectories(dirsToShow)
        checkPlaceholderVisibility(dirsToShow)

        val filteredFolderListUpdated = adapter?.dirs != dirsToShow
        if (filteredFolderListUpdated) {
            adapter?.updateDirs(dirsToShow)

            binding.directoriesGrid.apply {
                post {
                    scrollToPosition(0)
                }
            }
        }

        configureConfirmButton()
    }

    private fun checkPlaceholderVisibility(dirs: ArrayList<Directory>) = with(binding) {
        directoriesEmptyPlaceholder.beVisibleIf(dirs.isEmpty())

        if (folderSearchView.isSearchOpen) {
            directoriesEmptyPlaceholder.text = root.context.getString(org.fossify.commons.R.string.no_items_found)
        }

        directoriesFastscroller.beVisibleIf(directoriesEmptyPlaceholder.isGone())
    }

    private fun fetchDirectories(forceShowHiddenAndExcluded: Boolean) {
        showExcluded = forceShowHiddenAndExcluded || config.temporarilyShowExcluded
        activity.getCachedDirectories(forceShowHidden = forceShowHiddenAndExcluded, forceShowExcluded = forceShowHiddenAndExcluded) {
            if (it.isNotEmpty()) {
                it.forEach {
                    it.subfoldersMediaCount = it.mediaCnt
                }

                activity.runOnUiThread {
                    allDirectories.clear()
                    gotDirectories(activity.addTempFolderIfNeeded(it))
                }
            }
        }
    }

    private fun showOtherFolder() {
        activity.hideKeyboard(searchEditText)
        FilePickerDialog(
            activity,
            activity.getDefaultCopyDestinationPath(showHidden, sourcePath),
            !isPickingCopyMoveDestination && !isPickingFolderForWidget,
            showHidden,
            true,
            true
        ) {
            config.lastCopyPath = it
            activity.handleLockedFolderOpening(it) { success ->
                if (success) {
                    callback(it)
                }
            }
        }
    }

    private fun gotDirectories(newDirs: ArrayList<Directory>) {
        if (allDirectories.isEmpty()) {
            allDirectories = newDirs.clone() as ArrayList<Directory>
        }

        isLoading = true
        val prefix = currentPathPrefix
        val navigationRoots = config.navigationRoots
        val allDirs = ArrayList(allDirectories)
        val sourceDirs = ArrayList(newDirs)
        ensureBackgroundThread {
            val distinctDirs = sourceDirs.filter { showFavoritesBin || (!it.isRecycleBin() && !it.areFavorites()) }
                .distinctBy { it.path.getDistinctPath() }.toMutableList() as ArrayList<Directory>
            val sortedDirs = activity.getSortedDirectories(distinctDirs)
            val dirs = when {
                prefix.isNotEmpty() -> activity.getSubfolderListing(
                    allDirs, sortedDirs, prefix, showHidden, showExcluded
                )
                navigationRoots.isNotEmpty() -> activity.getNavigationRootListing(
                    allDirs, navigationRoots, showHidden, showExcluded
                )
                else -> activity.getRootListing(allDirs, sortedDirs, showHidden, showExcluded)
            }

            activity.runOnUiThread {
                isLoading = false
                if (prefix == currentPathPrefix) {
                    showDirectories(dirs)
                }
            }
        }
    }

    private fun showDirectories(dirs: ArrayList<Directory>) {
        val currentDirs = (binding.directoriesGrid.adapter as? DirectoryAdapter)?.dirs
        shownDirectories = dirs
        if (currentDirs?.hashCode() == dirs.hashCode()) {
            configureConfirmButton()
            return
        }

        val adapter = DirectoryAdapter(activity, dirs.clone() as ArrayList<Directory>, null, binding.directoriesGrid, true) {
            val clickedDir = it as Directory
            if (!isLoading && !isTapInFlight) {
                isTapInFlight = true
                val path = clickedDir.path
                val startPrefix = currentPathPrefix
                val allDirs = ArrayList(allDirectories)
                ensureBackgroundThread {
                    val canDrill = !path.equals(startPrefix, true) &&
                        (activity.navigationChildDirs(path, allDirs).isNotEmpty() ||
                            activity.hasFileSystemSubfolders(path, showHidden, showExcluded))
                    activity.runOnUiThread {
                        isTapInFlight = false
                        if (dialog?.isShowing != true || currentPathPrefix != startPrefix) {
                            return@runOnUiThread
                        }

                        if (canDrill) {
                            currentPathPrefix = path
                            openedSubfolders.add(path)
                            gotDirectories(allDirectories)
                        } else {
                            selectFolder(path)
                        }
                    }
                }
            }
        }

        val scrollHorizontally = activity.config.scrollHorizontally && isGridViewType
        binding.apply {
            directoriesGrid.adapter = adapter
            directoriesFastscroller.setScrollVertically(!scrollHorizontally)
        }

        pendingScrollPath?.let { newPath ->
            pendingScrollPath = null
            val index = dirs.indexOfFirst { it.path.equals(newPath, true) }
            if (index >= 0) {
                binding.directoriesGrid.scrollToPosition(index)
            }
        }

        checkPlaceholderVisibility(dirs)
        configureConfirmButton()
    }

    private fun configureConfirmButton() {
        binding.directoriesCreateFolder.beVisibleIf(currentPathPrefix.isNotEmpty())
        val button = dialog?.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        val enabled = currentPathPrefix.isNotEmpty()
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_BUTTON_ALPHA
    }

    companion object {
        private const val DISABLED_BUTTON_ALPHA = 0.5f
    }

    private fun selectFolder(path: String) {
        if (isPickingCopyMoveDestination && path.trimEnd('/') == sourcePath) {
            activity.toast(org.fossify.commons.R.string.source_and_destination_same)
            return
        } else if (isPickingCopyMoveDestination && activity.isRestrictedWithSAFSdk30(path) && !activity.isInDownloadDir(path)) {
            activity.toast(org.fossify.commons.R.string.system_folder_copy_restriction, Toast.LENGTH_LONG)
            return
        }

        activity.handleLockedFolderOpening(path) { success ->
            if (success) {
                callback(path)
            }
        }
        dialog?.dismiss()
    }

}

private fun BaseSimpleActivity.getFileSystemDirs(
    parentPath: String,
    allDirs: ArrayList<Directory>,
    showHidden: Boolean,
    showExcluded: Boolean
): ArrayList<Directory> {
    val children = File(parentPath).listFiles() ?: return ArrayList()
    val result = ArrayList<Directory>()
    children.filter { it.isDirectory && isPickerDirVisible(it.absolutePath, showHidden, showExcluded) }
        .forEach { result.add(getPickerDirectory(it.absolutePath, allDirs)) }
    return result
}

private fun BaseSimpleActivity.getSubfolderListing(
    allDirs: ArrayList<Directory>,
    sortedDirs: ArrayList<Directory>,
    prefix: String,
    showHidden: Boolean,
    showExcluded: Boolean
): ArrayList<Directory> {
    val dbDirs = getDirsToShow(sortedDirs, allDirs, prefix, forceGrouping = true)
    val fileSystemDirs = getFileSystemDirs(prefix, allDirs, showHidden, showExcluded)
    val merged = (dbDirs + fileSystemDirs).distinctBy { it.path.lowercase(Locale.getDefault()) }
    return getSortedDirectories(ArrayList(merged))
}

private fun BaseSimpleActivity.getNavigationRootListing(
    allDirs: ArrayList<Directory>,
    navigationRoots: Set<String>,
    showHidden: Boolean,
    showExcluded: Boolean
): ArrayList<Directory> {
    val roots = navigationRoots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }
    val expanded = expandNavigationRoots(roots, allDirs)
    val rootsWithoutDirs = roots.filter { root ->
        File(root).isDirectory && isPickerDirVisible(root, showHidden, showExcluded) &&
            expanded.none { it.path.equals(root, true) || it.path.startsWith("$root/", true) }
    }.map { getPickerDirectory(it, allDirs) }
    val dirs = (expanded + rootsWithoutDirs).distinctBy { it.path.lowercase(Locale.getDefault()) }
    return getSortedDirectories(ArrayList(dirs))
}

private fun BaseSimpleActivity.hasFileSystemSubfolders(
    path: String,
    showHidden: Boolean,
    showExcluded: Boolean
): Boolean {
    val children = File(path).listFiles() ?: return false
    return children.any { it.isDirectory && isPickerDirVisible(it.absolutePath, showHidden, showExcluded) }
}

private fun BaseSimpleActivity.getRootListing(
    allDirs: ArrayList<Directory>,
    sortedDirs: ArrayList<Directory>,
    showHidden: Boolean,
    showExcluded: Boolean
): ArrayList<Directory> {
    val dbDirs = getDirsToShow(sortedDirs, allDirs, "", forceGrouping = true)
    val storageRoots = getStorageDirectories()
    val primaryRoot = storageRoots.firstOrNull { it.equals(internalStoragePath, true) } ?: storageRoots.firstOrNull()
    val fileSystemDirs = ArrayList<Directory>()
    storageRoots.forEach { root ->
        if (!isPickerDirVisible(root, showHidden, showExcluded)) {
            return@forEach
        }

        if (root.equals(primaryRoot, true)) {
            fileSystemDirs.addAll(getFileSystemDirs(root, allDirs, showHidden, showExcluded))
        } else {
            fileSystemDirs.add(getPickerDirectory(root, allDirs))
        }
    }

    val merged = (dbDirs + fileSystemDirs).distinctBy { it.path.lowercase(Locale.getDefault()) }
    return getSortedDirectories(ArrayList(merged))
}

private fun BaseSimpleActivity.isPickerDirVisible(
    path: String,
    showHidden: Boolean,
    showExcluded: Boolean
): Boolean {
    val excluded = if (showExcluded) HashSet<String>() else config.excludedFolders
    val isVisible = path.shouldFolderBeVisible(excluded, config.includedFolders, showHidden, HashMap()) { _, _ -> }
    return isVisible && (!isRestrictedWithSAFSdk30(path) || isInDownloadDir(path))
}

private fun BaseSimpleActivity.getPickerDirectory(path: String, allDirs: ArrayList<Directory>): Directory {
    return allDirs.firstOrNull { it.path.equals(path, true) }
        ?: createNavigationDir(path, allDirs)
        ?: createEmptyPickerDirectory(path)
}

private fun Context.createEmptyPickerDirectory(path: String): Directory {
    val name = path.getFilenameFromPath()
    val modified = File(path).lastModified()
    return Directory(
        id = null,
        path = path,
        tmb = "",
        name = name,
        mediaCnt = 0,
        modified = modified,
        taken = 0,
        size = 0L,
        location = getPathLocation(path),
        types = 0,
        sortValue = getDirectorySortingValue(path, name, 0, 0, modified, 0)
    )
}
