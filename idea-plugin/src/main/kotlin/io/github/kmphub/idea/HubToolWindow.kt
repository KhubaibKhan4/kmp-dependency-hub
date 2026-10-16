package io.github.kmphub.idea

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.kmphub.core.Categories
import io.github.kmphub.core.Category
import io.github.kmphub.core.InstallRequest
import io.github.kmphub.core.Installer
import io.github.kmphub.core.KlibsClient
import io.github.kmphub.core.KlibsPackage
import io.github.kmphub.core.Library
import io.github.kmphub.core.LibraryCatalog
import io.github.kmphub.core.ModuleInfo
import io.github.kmphub.core.ModuleKind
import io.github.kmphub.core.Platform
import io.github.kmphub.core.ProjectPager
import io.github.kmphub.core.ProjectScanner
import io.github.kmphub.core.RemoteRecipes
import io.github.kmphub.core.toLibrary
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class HubToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(HubPanel(project, toolWindow.disposable), null, false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Browse the top KMP libraries from klibs.io (50 per page, 20 per category page, more on scroll), search
 * klibs.io plus Maven Central / Google Maven, pick artifacts, choose a module and source set, review, apply.
 */
class HubPanel(
    private val project: Project,
    parent: Disposable,
    private val goOnline: Boolean = true,
) : JPanel(BorderLayout()) {
    private var catalog: LibraryCatalog = HubData.snapshot.catalog
    private val klibs = KlibsClient(IdeHttp)

    /** Cart: recipe id or `group:name` -> key of the list entry it came from (for checkbox state). */
    private val cart = LinkedHashMap<String, String>()
    private val cartLabels = LinkedHashMap<String, String>()
    private val packagesByModule = ConcurrentHashMap<String, KlibsPackage>()
    private val resolved = ConcurrentHashMap<String, Library>()

    private val generation = AtomicInteger()
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private var category: Category = Categories.TOP
    private var pager: ProjectPager? = null
    private var loading = false
    private var mavenAppended = false

    private val search = SearchTextField(false)
    private val listModel = CollectionListModel<Entry>()
    // Always as wide as the viewport: cards truncate instead of pushing the stars off-screen.
    private val list = object : JBList<Entry>(listModel) {
        override fun getScrollableTracksViewportWidth() = true
    }
    private val details = WidthTrackingPanel()
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }
    private val selectedLabel = JBLabel().apply { font = JBFont.small(); minimumSize = java.awt.Dimension(0, 0) }
    private val modules = ComboBox<ModuleInfo>()
    private val sourceSet = ComboBox<String>()
    private val addButton = JButton()
    private val categoryBox = ComboBox((listOf(Categories.TOP, RECIPES) + Categories.all.drop(1)).toTypedArray())

    @Volatile
    private var tagCounts: Map<String, Int> = emptyMap()

    init {
        search.textEditor.emptyText.text = "Search KMP libraries on klibs.io and Maven"
        // Long module names and targets must not widen the whole tool window.
        listOf(modules, sourceSet, categoryBox).forEach { it.setMinimumAndPreferredWidth(JBUI.scale(140)) }
        list.apply {
            cellRenderer = EntryRenderer(::isChecked)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "Loading libraries…"
            addListSelectionListener { if (!it.valueIsAdjusting) showDetails() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point).takeIf { it >= 0 && getCellBounds(it, it).contains(e.point) } ?: return
                    when (val entry = listModel.getElementAt(index)) {
                        is Entry.Footer -> if (entry.isError) loadMore()
                        is Entry.Header -> Unit
                        else -> if (e.x - getCellBounds(index, index).x < JBUI.scale(34) || e.clickCount == 2) toggle(entry)
                    }
                }
            })
        }
        val listScroll = ScrollPaneFactory.createScrollPane(list, true).apply {
            verticalScrollBar.addAdjustmentListener {
                val bar = verticalScrollBar
                if (!it.valueIsAdjusting && bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(400)) loadMore()
            }
        }

        add(header(), BorderLayout.NORTH)
        add(OnePixelSplitter(true, 0.6f).apply {
            firstComponent = listScroll
            secondComponent = JBScrollPane(details).apply { border = JBUI.Borders.customLineTop(JBColor.border()) }
        }, BorderLayout.CENTER)
        add(footer(), BorderLayout.SOUTH)

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchAlarm.cancelAllRequests()
                searchAlarm.addRequest({ reload() }, SEARCH_DELAY_MS)
            }
        })
        status.text = HubData.describe()
        showDetails()
        updateSelection()
        scanModules()
        if (goOnline) {
            refreshVersions(force = false)
            ApplicationManager.getApplication().executeOnPooledThread {
                klibs.tagCounts()?.let { counts -> onEdt { tagCounts = counts; categoryBox.repaint() } }
            }
            reload()
        } else {
            list.emptyText.text = "Offline"
        }
    }

    // ---- Layout -----------------------------------------------------------------------------------------

    private fun header(): JComponent {
        categoryBox.renderer = object : ColoredListCellRenderer<Category>() {
            override fun customizeCellRenderer(list: JList<out Category>, c: Category?, index: Int, selected: Boolean, focus: Boolean) {
                c ?: return
                append(c.name)
                val count = when (c) {
                    RECIPES -> catalog.libraries.size
                    else -> tagCounts[c.tag.orEmpty()]
                }
                if (count != null) append("  ${String.format("%,d", count)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        categoryBox.addActionListener {
            val c = categoryBox.selectedItem as? Category ?: return@addActionListener
            if (c != category) { category = c; reload() }
        }
        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(status)
            add(ActionLink("Refresh") { refreshVersions(force = true) }.apply { font = JBFont.small() })
        }
        return panel {
            row { cell(search).align(AlignX.FILL).resizableColumn() }
            row("Browse:") { cell(categoryBox).align(AlignX.FILL).resizableColumn() }
            row { cell(statusRow) }
        }.apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 8, 2, 8))
        }
    }

    private fun footer(): JComponent {
        modules.renderer = object : ColoredListCellRenderer<ModuleInfo>() {
            override fun customizeCellRenderer(list: JList<out ModuleInfo>, m: ModuleInfo?, index: Int, selected: Boolean, focus: Boolean) {
                if (m == null) return append("No Gradle modules found", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(m.path)
                val targets = if (m.targets.isEmpty()) m.kind.name.lowercase() else m.targets.joinToString { it.name }
                append("  $targets", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        modules.addActionListener { refreshSourceSets() }
        addButton.addActionListener { install() }

        return panel {
            row {
                cell(selectedLabel).resizableColumn()
                cell(ActionLink("Clear") { cart.clear(); cartLabels.clear(); list.repaint(); updateSelection(); showDetails() })
            }
            row("Module:") {
                cell(modules).align(AlignX.FILL).resizableColumn()
                cell(ActionLink("Rescan") { scanModules() })
            }
            row("Common artifacts to:") { cell(sourceSet).align(AlignX.FILL).resizableColumn() }
            row {
                cell(addButton).align(AlignX.FILL).resizableColumn()
                cell(ActionLink("Run Doctor") { DoctorAction.runDoctor(project) })
            }
        }.apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(6, 8))
        }
    }

    // ---- Loading ----------------------------------------------------------------------------------------

    private fun query(): String? = search.text.trim().takeIf { it.isNotEmpty() }

    /** Starts the list over for the current category and query. */
    private fun reload() {
        generation.incrementAndGet()
        listModel.removeAll()
        loading = false
        mavenAppended = false
        val q = query()
        if (category == RECIPES) {
            pager = null
            catalog.search(q.orEmpty()).forEach { listModel.add(Entry.Recipe(it)) }
            list.emptyText.text = "No recipes match"
            showDetails()
            return
        }
        // Top libraries load 50 at a time; a category or a search, 20 at a time.
        val pageSize = if (category == Categories.TOP && q == null) 50 else 20
        pager = ProjectPager(klibs, q, category, pageSize)
        list.emptyText.text = "Loading libraries…"
        loadMore()
    }

    /** Appends the next page; called on open, on scroll near the bottom, and from the retry footer. */
    private fun loadMore() {
        val pager = pager ?: return
        if (loading || pager.exhausted || !goOnline) return
        loading = true
        val gen = generation.get()
        setFooter(Entry.Footer("Loading…"))
        ApplicationManager.getApplication().executeOnPooledThread {
            val page = pager.loadNext()
            onEdt {
                if (gen != generation.get()) return@onEdt
                setFooter(null)
                loading = false
                val known = (0 until listModel.size).mapNotNull { (listModel.getElementAt(it) as? Entry.Project)?.project?.id }.toSet()
                page.filter { it.id !in known }.forEach { listModel.add(Entry.Project(it, catalog.forKlibs(it))) }
                when {
                    pager.failed -> setFooter(Entry.Footer("Couldn't reach klibs.io. Click to retry.", isError = true))
                    pager.exhausted && query() != null -> appendMavenResults(gen)
                    pager.exhausted -> setFooter(Entry.Footer("That's all ${countProjects()} libraries"))
                }
                if (listModel.isEmpty) list.emptyText.text = "Nothing on klibs.io matches"
                showDetails()
            }
        }
    }

    /** After klibs.io results run out for a search, add raw Maven Central / Google Maven hits. */
    private fun appendMavenResults(gen: Int) {
        val q = query() ?: return
        if (mavenAppended || q.length < 3) return
        mavenAppended = true
        setFooter(Entry.Footer("Searching Maven Central and Google Maven…"))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = HubData.index.search(q, limit = 20)
            val klibsModules = packagesByModule.keys
            val entries = result.artifacts.filter { it.module !in klibsModules }.map { Entry.Artifact(it) }
            onEdt {
                if (gen != generation.get()) return@onEdt
                setFooter(null)
                if (entries.isNotEmpty()) {
                    listModel.add(Entry.Header("More on Maven Central & Google Maven"))
                    entries.forEach(listModel::add)
                }
                if (result.unavailable.isNotEmpty()) {
                    setFooter(Entry.Footer("${result.unavailable.joinToString(" and ")} didn't respond"))
                }
            }
            entries.parallelStream().forEach { entry ->
                if (gen != generation.get()) return@forEach
                val lib = runCatching { RemoteRecipes.resolve(HubData.index, entry.artifact.module, entry.artifact.repository) }.getOrNull()
                if (lib == null) entry.failed = true else { entry.library = lib; resolved[lib.id] = lib }
                onEdt { list.repaint(); if (list.selectedValue === entry) showDetails() }
            }
        }
    }

    private fun setFooter(footer: Entry.Footer?) {
        val last = listModel.size - 1
        if (last >= 0 && listModel.getElementAt(last) is Entry.Footer) listModel.remove(last)
        if (footer != null) listModel.add(footer)
    }

    private fun countProjects() = (0 until listModel.size).count { listModel.getElementAt(it) is Entry.Project }

    private fun refreshVersions(force: Boolean) {
        status.text = "Checking for the latest versions…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshot = HubData.refresh(force)
            onEdt {
                catalog = snapshot.catalog
                status.text = HubData.describe(snapshot)
                list.repaint()
            }
        }
    }

    /** Loads a project's artifacts once; [then] runs on the EDT. */
    private fun loadPackages(entry: Entry.Project, then: () -> Unit = {}) {
        if (entry.packages != null) return then()
        ApplicationManager.getApplication().executeOnPooledThread {
            val packages = klibs.packages(entry.project)
            if (packages == null) entry.packagesFailed = true else {
                entry.packages = packages
                packages.forEach { packagesByModule[it.module] = it }
            }
            onEdt { then() }
        }
    }

    // ---- Selection --------------------------------------------------------------------------------------

    private fun isChecked(entry: Entry): Boolean = when (entry) {
        is Entry.Project -> entry.recipe?.id?.let { it in cart } ?: cart.values.any { it == entry.key }
        is Entry.Recipe -> entry.library.id in cart
        is Entry.Artifact -> entry.artifact.module in cart
        else -> false
    }

    private fun toggle(entry: Entry) {
        when (entry) {
            is Entry.Recipe -> flip(entry.library.id, entry.key, entry.library.name)
            is Entry.Artifact -> flip(entry.artifact.module, entry.key, entry.artifact.module)
            is Entry.Project -> when {
                entry.recipe != null -> flip(entry.recipe.id, entry.key, entry.recipe.name)
                isChecked(entry) -> {
                    val owned = cart.filterValues { it == entry.key }.keys
                    cart.keys.removeAll(owned)
                    cartLabels.keys.removeAll(owned)
                }
                else -> loadPackages(entry) {
                    val primary = entry.packages?.firstOrNull()
                    if (primary == null) {
                        notify(project, "${entry.project.name} has no Maven artifacts listed on klibs.io.", NotificationType.WARNING)
                    } else {
                        flip(primary.module, entry.key, primary.artifact)
                    }
                    if (list.selectedValue === entry) showDetails()
                }
            }
            else -> Unit
        }
        list.repaint()
        updateSelection()
        if (list.selectedValue === entry) showDetails()
    }

    private fun flip(id: String, owner: String, label: String) {
        if (id in cart) {
            cart.remove(id)
            cartLabels.remove(id)
        } else {
            cart[id] = owner
            cartLabels[id] = label
        }
        list.repaint()
        updateSelection()
    }

    private fun updateSelection() {
        selectedLabel.text = when (cart.size) {
            0 -> "Tick libraries to add them"
            else -> "${cart.size} selected: " + cartLabels.values.joinToString(", ").let { if (it.length > 42) it.take(40) + "…" else it }
        }
        addButton.text = if (cart.isEmpty()) "Add to Module" else "Add ${cart.size} to Module…"
        addButton.isEnabled = cart.isNotEmpty() && modules.selectedItem != null
    }

    // ---- Details ----------------------------------------------------------------------------------------

    private fun showDetails() {
        details.removeAll()
        val entry = list.selectedValue
        val body = details
        body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
        body.border = JBUI.Borders.empty(10)
        fun text(html: String, small: Boolean = false) = body.add(JBLabel("<html>$html</html>").apply {
            alignmentX = LEFT_ALIGNMENT
            if (small) {
                font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
            }
            border = JBUI.Borders.emptyBottom(4)
        })
        fun para(plain: String, small: Boolean = false, muted: Boolean = false) = body.add(wrappingText(plain, small, muted))
        fun badges(platforms: Set<Platform>) = body.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            alignmentX = LEFT_ALIGNMENT
            displayPlatforms(platforms).forEach { p -> add(Badge().apply { text = HubColors.label(p); color = HubColors.of(p) }) }
        })
        fun link(label: String, url: String) = body.add(ActionLink(label) { BrowserUtil.browse(url) }.apply { alignmentX = LEFT_ALIGNMENT })
        fun recipeContents(lib: Library) {
            text("<b>Installs the full recipe</b>")
            lib.artifacts.forEach { a ->
                val where = if (a.configuration == "ksp") "ksp, ${a.placement.key}" else a.placement.key
                text("&nbsp;• ${a.module} <font color='gray'>→ $where${if (a.bom) " (BOM)" else ""}</font>", small = true)
            }
            lib.plugins.forEach { text("&nbsp;• plugin ${it.id}", small = true) }
            lib.blocks.forEach { text("&nbsp;• ${it.header} { } block", small = true) }
            lib.notes.forEach { para("Then: $it", small = true, muted = true) }
        }

        when (entry) {
            null, is Entry.Header, is Entry.Footer -> {
                text("<b>KMP Dependency Hub</b>")
                para("Tick a library's box (or double-click it) to select it. Select as many as you like, pick a module, then Add.", muted = true)
                para("Libraries marked Recipe install everything they need: per-platform engines, BOMs, KSP processors and Gradle plugins.", muted = true)
            }
            is Entry.Project -> {
                val p = entry.project
                text("<b><font size='+1'>${entry.recipe?.name ?: p.name}</font></b> &nbsp;<font color='gray'>${p.owner} · ★ ${formatStars(p.stars)}</font>")
                para(p.description.ifBlank { "No description." })
                badges(entry.recipe?.platforms ?: p.platforms)
                if (p.scmLink.isNotBlank()) link("Open on GitHub", p.scmLink)
                body.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
                if (entry.recipe != null) recipeContents(entry.recipe) else artifactPicker(entry, body)
            }
            is Entry.Recipe -> {
                val lib = entry.library
                text("<b><font size='+1'>${lib.name}</font></b> &nbsp;<font color='gray'>${lib.category} · ${lib.primaryVersion.orEmpty()}</font>")
                para(lib.description)
                badges(lib.platforms)
                lib.website?.let { link("Documentation", it) }
                recipeContents(lib)
            }
            is Entry.Artifact -> {
                val lib = entry.library
                text("<b><font size='+1'>${entry.artifact.name}</font></b> &nbsp;<font color='gray'>${entry.artifact.group}</font>")
                text(when {
                    lib != null -> "Latest stable: <b>${lib.primaryVersion}</b> on ${entry.artifact.repository}"
                    entry.failed -> "No stable release found."
                    else -> "Looking up the latest version…"
                })
                lib?.let { badges(it.platforms) }
            }
        }
        details.revalidate()
        details.repaint()
    }

    private fun artifactPicker(entry: Entry.Project, body: JPanel) {
        val packages = entry.packages
        when {
            packages == null && entry.packagesFailed -> body.add(JBLabel("Couldn't load artifacts from klibs.io."))
            packages == null -> {
                body.add(JBLabel("Loading artifacts…").apply { foreground = UIUtil.getContextHelpForeground() })
                loadPackages(entry) { if (list.selectedValue === entry) showDetails() }
            }
            packages.isEmpty() -> body.add(JBLabel("No Maven artifacts listed on klibs.io."))
            else -> {
                body.add(JBLabel("<html><b>Artifacts</b> <font color='gray'>(${packages.size}, most likely first)</font></html>").apply { alignmentX = LEFT_ALIGNMENT })
                packages.take(MAX_ARTIFACTS).forEach { pkg ->
                    body.add(JBCheckBox("<html>${pkg.artifact} <font color='gray'>${pkg.group} · ${pkg.version.orEmpty()}</font></html>", pkg.module in cart).apply {
                        alignmentX = LEFT_ALIGNMENT
                        isOpaque = false
                        toolTipText = pkg.description.ifBlank { pkg.module }
                        addActionListener { flipPackage(entry, pkg, isSelected) }
                    })
                }
                if (packages.size > MAX_ARTIFACTS) {
                    body.add(JBLabel("…and ${packages.size - MAX_ARTIFACTS} more; search for them by name.").apply {
                        font = JBFont.small()
                        foreground = UIUtil.getContextHelpForeground()
                    })
                }
            }
        }
    }

    private fun flipPackage(entry: Entry.Project, pkg: KlibsPackage, selected: Boolean) {
        if (selected) {
            cart[pkg.module] = entry.key
            cartLabels[pkg.module] = pkg.artifact
        } else {
            cart.remove(pkg.module)
            cartLabels.remove(pkg.module)
        }
        list.repaint()
        updateSelection()
    }

    // ---- Modules and install ----------------------------------------------------------------------------

    private fun scanModules() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = readAction { ProjectScanner.scan(IdeProjectFiles(project)) }.filter { it.kind != ModuleKind.UNKNOWN }
            onEdt {
                val previous = (modules.selectedItem as? ModuleInfo)?.path
                modules.model = DefaultComboBoxModel(found.toTypedArray())
                modules.selectedItem = found.firstOrNull { it.path == previous }
                    ?: found.firstOrNull { it.path == ":composeApp" }
                    ?: found.firstOrNull { it.kind == ModuleKind.KMP }
                    ?: found.firstOrNull()
                refreshSourceSets()
            }
        }
    }

    private fun refreshSourceSets() {
        val module = modules.selectedItem as? ModuleInfo
        val options = when (module?.kind) {
            ModuleKind.KMP -> listOf(RECIPE_DEFAULT) + buildList {
                if (module.targets.any { it.platform == Platform.ANDROID }) add("androidMain")
                if (module.targets.any { it.platform == Platform.IOS }) add("iosMain")
                module.targets.filter { it.platform in setOf(Platform.JVM, Platform.JS, Platform.WASM) }.forEach { add("${it.name}Main") }
            }
            null -> emptyList()
            else -> listOf(RECIPE_DEFAULT)
        }
        sourceSet.model = DefaultComboBoxModel(options.toTypedArray())
        sourceSet.isEnabled = options.size > 1
        updateSelection()
    }

    private fun install() {
        val module = modules.selectedItem as? ModuleInfo ?: return
        val override = (sourceSet.selectedItem as? String)?.takeIf { it != RECIPE_DEFAULT }
        val ids = cart.keys.toList()

        // Artifacts get their latest stable version and platforms from Maven right before installing.
        val pending = ids.filter { catalog[it] == null && resolved[it] == null }
        if (pending.isNotEmpty()) {
            val done = ProgressManager.getInstance().runProcessWithProgressSynchronously({
                pending.parallelStream().forEach { id ->
                    val lib = packagesByModule[id]?.toLibrary(HubData.index) ?: RemoteRecipes.resolve(HubData.index, id)
                    if (lib != null) resolved[id] = lib
                }
            }, "Looking Up Latest Versions", true, project)
            if (!done) return
        }
        val missing = ids.filter { catalog[it] == null && resolved[it] == null }
        if (missing.isNotEmpty()) {
            notify(project, "No stable release found for ${missing.joinToString()}.", NotificationType.WARNING)
            return
        }

        val installCatalog = LibraryCatalog(catalog.libraries + ids.mapNotNull { resolved[it] }.filter { catalog[it.id] == null })
        val result = readAction { Installer(installCatalog).install(InstallRequest(ids, module, override), IdeProjectFiles(project)) }
        if (!ApplyChangesDialog(project, result).showAndGet() || result.hasErrors || result.changes.isEmpty()) return
        ChangeWriter.apply(project, result.changes)
        notify(project, "Added ${ids.size} librar${if (ids.size == 1) "y" else "ies"} to ${module.path}. Sync Gradle to use them.")
        cart.clear()
        cartLabels.clear()
        list.repaint()
        updateSelection()
        showDetails()
    }

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any(), project.disposed)

    // ---- Test hooks -------------------------------------------------------------------------------------

    internal val searchField: SearchTextField get() = search
    internal val projectCount: Int get() = countProjects()
    internal val isLoading: Boolean get() = loading
    internal val entryKeys: List<String> get() = (0 until listModel.size).map { listModel.getElementAt(it).key }
    internal fun selectCategory(name: String) {
        categoryBox.selectedItem = (0 until categoryBox.itemCount).map { categoryBox.getItemAt(it) }.first { it.name == name }
    }
    internal fun requestMore() = loadMore()
    internal fun toggleAt(index: Int) = toggle(listModel.getElementAt(index))
    internal val cartIds: List<String> get() = cart.keys.toList()
    internal fun selectListIndex(index: Int) { list.selectedIndex = index }
    internal fun indexOfName(name: String): Int = (0 until listModel.size).firstOrNull {
        val e = listModel.getElementAt(it)
        e is Entry.Project && (e.recipe?.name == name || e.project.name == name)
    } ?: -1

    private companion object {
        val RECIPES = Category("Recipes", null)
        const val RECIPE_DEFAULT = "commonMain (recipe default)"
        const val SEARCH_DELAY_MS = 450
        const val MAX_ARTIFACTS = 40
    }
}
