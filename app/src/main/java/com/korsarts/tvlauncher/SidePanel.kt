package com.korsarts.tvlauncher

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Painel lateral com páginas empilhadas (submenus). Vive dentro da própria Activity: abrir não
 * cria janela nem Dialog, e as linhas são reaproveitadas entre aberturas.
 */
class SidePanel(
    root: View,
    private val onOpened: () -> Unit,
    private val onClosed: () -> Unit,
) {
    class Item(
        val icon: Int, // @DrawableRes
        val label: CharSequence,
        val hint: CharSequence? = null,
        /** Não nulo = linha com switch; o painel continua aberto e o switch é atualizado. */
        val checked: (() -> Boolean)? = null,
        /** Ações que abrem subpágina devem manter o painel aberto. */
        val keepOpen: Boolean = checked != null,
        val action: () -> Unit,
    )

    class Page(
        val title: CharSequence,
        val subtitle: CharSequence? = null,
        val icon: Drawable? = null,
        val body: CharSequence? = null,
        val items: List<Item>,
    ) {
        internal var focusIndex = 0
    }

    private val overlay: ViewGroup = root.findViewById(R.id.panel_overlay)
    private val scrim: View = root.findViewById(R.id.panel_scrim)
    private val panel: View = root.findViewById(R.id.panel)
    private val iconView: ImageView = root.findViewById(R.id.panel_icon)
    private val titleView: TextView = root.findViewById(R.id.panel_title)
    private val subtitleView: TextView = root.findViewById(R.id.panel_subtitle)
    private val scroll: ScrollView = root.findViewById(R.id.panel_scroll)
    private val bodyView: TextView = root.findViewById(R.id.panel_body)
    private val itemsView: LinearLayout = root.findViewById(R.id.panel_items)
    private val inflater = LayoutInflater.from(root.context)
    private val slideDistance = root.resources.getDimension(R.dimen.panel_width)
    private val pageShift = 32f * root.resources.displayMetrics.density

    private val stack = ArrayList<Page>()

    val isOpen: Boolean get() = stack.isNotEmpty()

    init {
        // Clique no scrim (mouse/touch, raro na TV) fecha.
        scrim.setOnClickListener { close() }
    }

    fun open(page: Page) {
        val wasOpen = isOpen
        stack.clear()
        stack += page
        show(page, direction = 0)
        if (wasOpen) return

        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overlay.visibility = View.VISIBLE
        val sign = if (panel.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.alpha = 0f
        panel.translationX = slideDistance * sign
        scrim.animate().alpha(1f).setDuration(Motion.PANEL_MS).setInterpolator(Motion.STANDARD).start()
        panel.animate().translationX(0f).setDuration(Motion.PANEL_MS).setInterpolator(Motion.EMPHASIZED).start()
        focusRow(page.focusIndex)
        onOpened()
    }

    fun push(page: Page) {
        stack.lastOrNull()?.focusIndex = focusedRowIndex()
        stack += page
        show(page, direction = 1)
        focusRow(0)
    }

    /** BACK: volta uma página ou fecha. */
    fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            val page = stack.last()
            show(page, direction = -1)
            focusRow(page.focusIndex)
        } else {
            close()
        }
    }

    fun close(animate: Boolean = true) {
        if (!isOpen) return
        stack.clear()
        // Sai do caminho do foco imediatamente; a animação de saída é só visual.
        overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        onClosed()

        scrim.animate().cancel()
        panel.animate().cancel()
        if (!animate) {
            overlay.visibility = View.GONE
            return
        }
        val sign = if (panel.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        scrim.animate().alpha(0f).setDuration(Motion.PANEL_MS).setInterpolator(Motion.STANDARD).start()
        panel.animate().translationX(slideDistance * sign)
            .setDuration(Motion.PANEL_MS)
            .setInterpolator(Motion.EMPHASIZED)
            .withEndAction { if (!isOpen) overlay.visibility = View.GONE }
            .start()
    }

    // ------------------------------------------------------------------ render

    private fun show(page: Page, direction: Int) {
        titleView.text = page.title
        subtitleView.text = page.subtitle
        subtitleView.visibility = if (page.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        iconView.setImageDrawable(page.icon)
        iconView.visibility = if (page.icon == null) View.GONE else View.VISIBLE
        bodyView.text = page.body
        bodyView.visibility = if (page.body.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Reaproveita as linhas já infladas.
        while (itemsView.childCount < page.items.size) {
            itemsView.addView(inflater.inflate(R.layout.item_menu, itemsView, false))
        }
        for (i in 0 until itemsView.childCount) {
            val row = itemsView.getChildAt(i)
            if (i < page.items.size) {
                row.visibility = View.VISIBLE
                bindRow(row, page.items[i])
            } else {
                row.visibility = View.GONE
                row.setOnClickListener(null)
            }
        }
        scroll.scrollTo(0, 0)

        if (direction != 0) {
            // Troca de página: desliza + fade, com layer de hardware só durante a animação.
            scroll.animate().cancel()
            scroll.alpha = 0f
            scroll.translationX = pageShift * direction
            scroll.animate().alpha(1f).translationX(0f)
                .setDuration(Motion.FOCUS_MS + 40)
                .setInterpolator(Motion.STANDARD)
                .withLayer()
                .start()
        } else {
            scroll.animate().cancel()
            scroll.alpha = 1f
            scroll.translationX = 0f
        }
    }

    private fun bindRow(row: View, item: Item) {
        row.findViewById<ImageView>(R.id.menu_icon).setImageResource(item.icon)
        row.findViewById<TextView>(R.id.menu_label).text = item.label
        val hint = row.findViewById<TextView>(R.id.menu_hint)
        hint.text = item.hint
        hint.visibility = if (item.hint.isNullOrEmpty()) View.GONE else View.VISIBLE
        val toggle = row.findViewById<View>(R.id.menu_toggle)
        toggle.visibility = if (item.checked != null) View.VISIBLE else View.GONE
        // O estado "ligado" fica na linha (activated); o switch o recebe junto com o foco via
        // duplicateParentState, e troca de cores quando a linha fica clara.
        row.isActivated = item.checked?.invoke() == true
        row.contentDescription = item.label

        row.setOnClickListener {
            if (!isOpen) return@setOnClickListener
            if (item.keepOpen) {
                item.action()
                item.checked?.let { row.isActivated = it() }
            } else {
                close()
                item.action()
            }
        }
    }

    private fun focusedRowIndex(): Int {
        for (i in 0 until itemsView.childCount) {
            if (itemsView.getChildAt(i).isFocused) return i
        }
        return 0
    }

    private fun focusRow(index: Int) {
        val count = stack.lastOrNull()?.items?.size ?: 0
        if (count == 0) return
        itemsView.getChildAt(index.coerceIn(0, count - 1)).requestFocus()
    }
}
