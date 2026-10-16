package io.github.kmphub.idea

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.kmphub.core.KlibsPackage
import io.github.kmphub.core.KlibsProject
import io.github.kmphub.core.Library
import io.github.kmphub.core.Platform
import io.github.kmphub.core.RemoteArtifact
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** One row of the library list. */
internal sealed class Entry {
    abstract val key: String

    /** A klibs.io project; [recipe] is set when the hub has a curated recipe for it. */
    class Project(val project: KlibsProject, val recipe: Library?) : Entry() {
        override val key get() = "klibs:${project.id}"
        @Volatile var packages: List<KlibsPackage>? = null
        @Volatile var packagesFailed = false
    }

    /** A curated recipe that isn't on klibs.io (e.g. Android-only Hilt, Retrofit). */
    class Recipe(val library: Library) : Entry() {
        override val key get() = "recipe:${library.id}"
    }

    /** A raw Maven Central / Google Maven hit. */
    class Artifact(val artifact: RemoteArtifact) : Entry() {
        override val key get() = "maven:${artifact.module}"
        @Volatile var library: Library? = null
        @Volatile var failed = false
    }

    class Header(val title: String) : Entry() {
        override val key get() = "header:$title"
    }

    class Footer(val text: String, val isError: Boolean = false) : Entry() {
        override val key get() = "footer"
    }
}

internal object HubColors {
    val android = JBColor(Color(0x2E7D32), Color(0x7BC67E))
    val ios = JBColor(Color(0x455A64), Color(0xB0BEC5))
    val jvm = JBColor(Color(0xB45309), Color(0xF0B36A))
    val js = JBColor(Color(0x8D7A00), Color(0xE6D45C))
    val wasm = JBColor(Color(0x5B4BC4), Color(0xA99BFF))
    val native = JBColor(Color(0x00796B), Color(0x6FD3C5))
    val recipe = JBColor(Color(0x1565C0), Color(0x7FB2F0))
    val star = JBColor(Color(0xC28A00), Color(0xF2C14E))

    fun of(platform: Platform): Color = when (platform) {
        Platform.ANDROID -> android
        Platform.IOS -> ios
        Platform.JVM -> jvm
        Platform.JS -> js
        Platform.WASM -> wasm
        Platform.NATIVE -> native
    }

    fun label(platform: Platform) = when (platform) {
        Platform.ANDROID -> "Android"
        Platform.IOS -> "iOS"
        Platform.JVM -> "JVM"
        Platform.JS -> "JS"
        Platform.WASM -> "Wasm"
        Platform.NATIVE -> "Native"
    }
}

/**
 * Renderer root that lays out its whole subtree when sized. CellRendererPane only validates components that
 * belong to a displayable window, so off-screen painting (snapshots, previews) would otherwise show empty cells.
 */
internal class SelfLayingPanel(layout: java.awt.LayoutManager) : JPanel(layout) {
    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val resized = width != this.width || height != this.height
        super.setBounds(x, y, width, height)
        if (resized || !isValid) layoutTree(this)
    }

    private fun layoutTree(c: java.awt.Container) {
        c.doLayout()
        c.components.forEach { if (it is java.awt.Container) layoutTree(it) }
    }
}

/** Panel inside a scroll pane that always matches the viewport width, so wrapped text wraps instead of clipping. */
internal class WidthTrackingPanel : JPanel(), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

/** Read-only text area whose preferred height follows its parent's width, so BoxLayout gives it enough lines. */
internal class WrappingText(text: String) : com.intellij.ui.components.JBTextArea(text) {
    override fun getPreferredSize(): Dimension {
        val available = parent?.let { it.width - it.insets.left - it.insets.right } ?: 0
        if (available > 0 && width != available) setSize(available, Short.MAX_VALUE.toInt())
        return super.getPreferredSize()
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** Read-only text that wraps at word boundaries. */
internal fun wrappingText(text: String, small: Boolean = false, muted: Boolean = false) = WrappingText(text).apply {
    isEditable = false
    isFocusable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    border = JBUI.Borders.emptyBottom(6)
    font = if (small) JBFont.small() else JBFont.label()
    if (muted) foreground = UIUtil.getContextHelpForeground()
    alignmentX = Component.LEFT_ALIGNMENT
}

/** A small rounded, outlined tag. */
internal class Badge : JComponent() {
    var text: String = ""
        set(value) { field = value; revalidate(); repaint() }
    var color: Color = HubColors.recipe
        set(value) { field = value; repaint() }
    var filled = false

    init {
        font = JBFont.small()
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(10), fm.height + JBUI.scale(2))
    }

    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            if (filled) {
                g2.color = color
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = JBColor.background()
            } else {
                g2.color = ColorUtilAlpha.withAlpha(color, 30)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = color
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            g2.font = font
            val fm = g2.fontMetrics
            g2.drawString(text, JBUI.scale(5), (height - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}

private object ColorUtilAlpha {
    fun withAlpha(c: Color, alpha: Int) = Color(c.red, c.green, c.blue, alpha)
}

internal fun formatStars(stars: Int): String = when {
    stars >= 10_000 -> "${stars / 1000}k"
    stars >= 1_000 -> String.format("%.1fk", stars / 1000.0)
    else -> stars.toString()
}

/** Platforms worth showing as badges, with JVM libraries also marked as Android-capable. */
internal fun displayPlatforms(platforms: Set<Platform>): List<Platform> {
    val shown = platforms.toMutableSet()
    if (Platform.JVM in shown) shown += Platform.ANDROID
    return Platform.entries.filter { it in shown }
}

/**
 * Two-line card: checkbox, name, owner, stars and version on top; description below; then platform badges.
 * One instance is reused for every row.
 */
internal class EntryRenderer(private val isChecked: (Entry) -> Boolean) : ListCellRenderer<Entry> {
    private val check = JCheckBox().apply { isOpaque = false }
    private val title = JLabel().apply { font = JBFont.label().asBold() }
    private val owner = JLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val stars = JLabel().apply { foreground = HubColors.star; horizontalAlignment = SwingConstants.RIGHT }
    private val description = JLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val version = JLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }
    private val badges = List(8) { Badge() }
    private val badgeRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }

    private val card = SelfLayingPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 8))
        val top = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false; add(title); add(owner) }, BorderLayout.CENTER)
            add(stars, BorderLayout.EAST)
        }
        val bottom = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(badgeRow, BorderLayout.CENTER)
            add(version, BorderLayout.EAST)
        }
        val center = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(top, BorderLayout.NORTH)
            add(description, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        add(JPanel(BorderLayout()).apply { isOpaque = false; add(check, BorderLayout.NORTH) }, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
    }

    private val header = JLabel().apply {
        font = JBFont.small().asBold()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(10, 10, 4, 10)
    }
    private val footer = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(12)
    }

    override fun getListCellRendererComponent(
        list: JList<out Entry>, value: Entry, index: Int, selected: Boolean, focused: Boolean,
    ): Component {
        when (value) {
            is Entry.Header -> return header.apply { text = value.title.uppercase() }
            is Entry.Footer -> return footer.apply {
                text = value.text
                foreground = if (value.isError) JBColor.RED else UIUtil.getContextHelpForeground()
            }
            else -> Unit
        }
        card.background = if (selected) com.intellij.ui.render.RenderingUtil.getSelectionBackground(list) else list.background
        card.isOpaque = true
        val fg = if (selected) com.intellij.ui.render.RenderingUtil.getSelectionForeground(list) else list.foreground
        val muted = if (selected) fg else UIUtil.getContextHelpForeground()
        title.foreground = fg
        owner.foreground = muted
        description.foreground = muted
        version.foreground = muted
        check.isSelected = isChecked(value)

        var platforms: Set<Platform> = emptySet()
        var tags = emptyList<Pair<String, Color>>()
        when (value) {
            is Entry.Project -> {
                val p = value.project
                title.text = value.recipe?.name ?: p.name
                owner.text = p.owner
                stars.text = "★ ${formatStars(p.stars)}"
                description.text = (value.recipe?.description ?: p.description).ifBlank { "No description" }
                version.text = value.recipe?.primaryVersion ?: p.latestRelease.orEmpty()
                platforms = value.recipe?.platforms ?: p.platforms
                if (value.recipe != null) tags = listOf("Recipe" to HubColors.recipe)
            }
            is Entry.Recipe -> {
                val lib = value.library
                title.text = lib.name
                owner.text = lib.category
                stars.text = ""
                description.text = lib.description
                version.text = lib.primaryVersion.orEmpty()
                platforms = lib.platforms
                tags = listOf("Recipe" to HubColors.recipe)
            }
            is Entry.Artifact -> {
                val lib = value.library
                title.text = value.artifact.name
                owner.text = value.artifact.group
                stars.text = ""
                description.text = when {
                    lib != null -> lib.description
                    value.failed -> "No stable release found"
                    else -> "Looking up versions and platforms…"
                }
                version.text = lib?.primaryVersion ?: if (value.failed) "" else "…"
                platforms = lib?.platforms.orEmpty()
                tags = listOf(value.artifact.repository to UIUtil.getContextHelpForeground())
            }
            else -> Unit
        }

        badgeRow.removeAll()
        var i = 0
        for (platform in displayPlatforms(platforms)) {
            if (i >= badges.size - 1) break
            badgeRow.add(badges[i++].apply { text = HubColors.label(platform); color = HubColors.of(platform); filled = false })
        }
        for ((text, color) in tags) {
            if (i >= badges.size) break
            badgeRow.add(badges[i++].apply { this.text = text; this.color = color; filled = text == "Recipe" })
        }
        return card
    }
}
