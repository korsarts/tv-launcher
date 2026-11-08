package com.korsarts.tvlauncher

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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

    /** Campo de texto no topo da página; [onSubmit] roda ao confirmar no teclado. */
    class Input(
        val hint: CharSequence,
        val onSubmit: (String) -> Unit,
    ) {
        /** Último texto digitado, restaurado ao voltar para a página. */
        internal var text: String = ""
    }

    class Page(
        val title: CharSequence,
        val subtitle: CharSequence? = null,
        val icon: Drawable? = null,
        var body: CharSequence? = null,
        val items: List<Item>,
        val input: Input? = null,
    ) {
        /** Linha focada ao (re)abrir; -1 = campo de texto. */
        internal var focusIndex = if (input != null) -1 else 0
    }

    private val overlay: ViewGroup = root.findViewById(R.id.panel_overlay)
    private val scrim: View = root.findViewById(R.id.panel_scrim)
    private val panel: View = root.findViewById(R.id.panel)
    private val iconView: ImageView = root.findViewById(R.id.panel_icon)
    private val titleView: TextView = root.findViewById(R.id.panel_title)
    private val subtitleView: TextView = root.findViewById(R.id.panel_subtitle)
    private val scroll: ScrollView = root.findViewById(R.id.panel_scroll)
    private val bodyView: TextView = root.findViewById(R.id.panel_body)
    private val inputView: EditText = root.findViewById(R.id.panel_input)
    private val itemsView: LinearLayout = root.findViewById(R.id.panel_items)
    private val inflater = LayoutInflater.from(root.context)
    private val slideDistance = root.resources.getDimension(R.dimen.panel_width)
    private val pageShift = 32f * root.resources.displayMetrics.density

    private val stack = ArrayList<Page>()

    val isOpen: Boolean get() = stack.isNotEmpty()

    init {
        // Clique no scrim (mouse/touch, raro na TV) fecha.
        scrim.setOnClickListener { close() }
        inputView.setOnEditorActionListener { _, actionId, event ->
            when {
                actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE -> {
                    submitInput()
                    true
                }
                // ENTER de teclado físico: chega como DOWN e UP; envia uma vez e consome os dois.
                event?.keyCode == KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) submitInput()
                    true
                }
                else -> false
            }
        }
    }

    /** Página no topo da pilha (a que está na tela). */
    val currentPage: Page? get() = stack.lastOrNull()

    /** Troca o texto de [page]; se ela estiver na tela, atualiza na hora. */
    fun setBody(page: Page, body: CharSequence?) {
        page.body = body
        if (currentPage === page) {
            bodyView.text = body
            bodyView.visibility = if (body.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun submitInput() {
        val input = currentPage?.input ?: return
        input.text = inputView.text.toString()
        hideKeyboard()
        if (input.text.isNotBlank()) input.onSubmit(input.text.trim())
    }

    private fun hideKeyboard() {
        val imm = inputView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(inputView.windowToken, 0)
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
        focusPage(page)
        onOpened()
    }

    fun push(page: Page) {
        stack.lastOrNull()?.let { saveState(it) }
        stack += page
        show(page, direction = 1)
        focusPage(page)
    }

    /** BACK: volta uma página ou fecha. */
    fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            val page = stack.last()
            show(page, direction = -1)
            focusPage(page)
        } else {
            close()
        }
    }

    fun close(animate: Boolean = true) {
        if (!isOpen) return
        stack.clear()
        hideKeyboard()
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
        val input = page.input
        if (input != null) {
            inputView.hint = input.hint
            inputView.setText(input.text)
            inputView.setSelection(inputView.text.length)
            inputView.visibility = View.VISIBLE
        } else {
            inputView.visibility = View.GONE
        }

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

    /** Guarda o que restaurar ao voltar para [page]: linha focada (-1 = campo) e texto. */
    private fun saveState(page: Page) {
        page.input?.text = inputView.text.toString()
        page.focusIndex = if (inputView.isFocused) -1 else focusedRowIndex()
    }

    private fun focusedRowIndex(): Int {
        for (i in 0 until itemsView.childCount) {
            if (itemsView.getChildAt(i).isFocused) return i
        }
        return 0
    }

    /** Página com campo começa nele; as demais, na linha salva. */
    private fun focusPage(page: Page) {
        if (page.input != null && page.focusIndex < 0) inputView.requestFocus() else focusRow(page.focusIndex)
    }

    private fun focusRow(index: Int) {
        val count = stack.lastOrNull()?.items?.size ?: 0
        if (count == 0) return
        itemsView.getChildAt(index.coerceIn(0, count - 1)).requestFocus()
    }
}
