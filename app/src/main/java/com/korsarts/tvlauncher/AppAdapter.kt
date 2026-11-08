package com.korsarts.tvlauncher

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val listener: Listener,
) : ListAdapter<AppEntry, AppAdapter.Holder>(Diff) {

    interface Listener {
        fun onAppClick(app: AppEntry, card: View)
        fun onAppMenu(app: AppEntry)

        /** Chamado antes do tratamento padrão de teclas do card (ex.: modo mover). true = consumida. */
        fun onAppKey(app: AppEntry, keyCode: Int, event: KeyEvent): Boolean
    }

    /** Pacotes ocultos (exibidos esmaecidos quando "mostrar ocultos" está ativo). */
    private var hidden: Set<String> = emptySet()

    /** Pacote em modo mover (reordenação de favoritos). */
    private var moving: String? = null

    init {
        setHasStableIds(true)
    }

    /** Atualiza o esmaecimento só dos itens que mudaram (sem rebind de imagens). */
    fun setHidden(value: Set<String>) {
        if (value == hidden) return
        val old = hidden
        hidden = value
        currentList.forEachIndexed { i, app ->
            if ((app.packageName in old) != (app.packageName in value)) notifyItemChanged(i, PAYLOAD_STATE)
        }
    }

    fun setMoving(pkg: String?) {
        if (pkg == moving) return
        val old = moving
        moving = pkg
        currentList.forEachIndexed { i, app ->
            if (app.packageName == old || app.packageName == pkg) notifyItemChanged(i, PAYLOAD_STATE)
        }
    }

    fun indexOf(pkg: String): Int = currentList.indexOfFirst { it.packageName == pkg }

    override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) holder.bind(getItem(position)) else holder.bindState(getItem(position))
    }

    override fun onViewRecycled(holder: Holder) = holder.recycle()

    inner class Holder(view: View) : RecyclerView.ViewHolder(view),
        View.OnClickListener, View.OnLongClickListener, View.OnKeyListener, View.OnFocusChangeListener {

        private val card: View = view.findViewById(R.id.card)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val iconGroup: View = view.findViewById(R.id.icon_group)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val iconLabel: TextView = view.findViewById(R.id.icon_label)
        private val label: TextView = view.findViewById(R.id.label)
        private val moveOverlay: View = view.findViewById(R.id.move_overlay)

        private var app: AppEntry? = null

        init {
            // Recorta o banner no outline arredondado do fundo (barato em HWUI: round rect).
            card.clipToOutline = true
            // Listeners criados uma vez por ViewHolder, não a cada bind.
            view.setOnClickListener(this)
            view.setOnLongClickListener(this)
            view.setOnKeyListener(this)
            view.onFocusChangeListener = this
        }

        fun bind(app: AppEntry) {
            this.app = app
            if (app.banner != null) {
                banner.setImageDrawable(app.banner)
                banner.visibility = View.VISIBLE
                iconGroup.visibility = View.GONE
                icon.setImageDrawable(null)
                card.backgroundTintList = null
            } else {
                banner.setImageDrawable(null)
                banner.visibility = View.GONE
                iconGroup.visibility = View.VISIBLE
                icon.setImageDrawable(app.icon)
                iconLabel.text = app.label
                card.backgroundTintList = tintFor(app.packageName)
            }
            label.text = app.label
            itemView.contentDescription = app.label
            bindState(app)
            applyFocus(itemView.hasFocus(), animate = false)
        }

        fun bindState(app: AppEntry) {
            this.app = app
            val alpha = if (app.packageName in hidden) HIDDEN_ALPHA else 255
            // imageAlpha/textColor em vez de View.alpha: evita camada offscreen no card.
            banner.imageAlpha = alpha
            icon.imageAlpha = alpha
            iconLabel.setTextColor(Color.argb(alpha, 0xF3, 0xF4, 0xF6))

            val isMoving = app.packageName == moving
            itemView.isActivated = isMoving
            moveOverlay.visibility = if (isMoving) View.VISIBLE else View.GONE
        }

        fun recycle() {
            app = null
            banner.setImageDrawable(null)
            icon.setImageDrawable(null)
            card.animate().cancel()
            label.animate().cancel()
        }

        private fun applyFocus(focused: Boolean, animate: Boolean) {
            val scale = if (focused) Motion.FOCUS_SCALE else 1f
            val labelAlpha = if (focused) 1f else 0f
            // Só ordena o desenho (card focado por cima dos vizinhos); sem outline visível, sem sombra.
            itemView.translationZ = if (focused) 1f else 0f
            if (animate) {
                card.animate().scaleX(scale).scaleY(scale)
                    .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.STANDARD).start()
                label.animate().alpha(labelAlpha)
                    .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.STANDARD).start()
            } else {
                card.animate().cancel()
                label.animate().cancel()
                card.scaleX = scale
                card.scaleY = scale
                label.alpha = labelAlpha
            }
        }

        override fun onFocusChange(v: View, hasFocus: Boolean) = applyFocus(hasFocus, animate = true)

        override fun onClick(v: View) {
            app?.let { listener.onAppClick(it, card) }
        }

        override fun onLongClick(v: View): Boolean {
            app?.let { listener.onAppMenu(it) }
            return true
        }

        override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
            val app = app ?: return false
            if (listener.onAppKey(app, keyCode, event)) return true
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) listener.onAppMenu(app)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // Feedback de "aperto"; o clique/long-press padrão continua acontecendo.
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        card.animate().scaleX(Motion.PRESS_SCALE).scaleY(Motion.PRESS_SCALE)
                            .setDuration(Motion.PRESS_MS).setInterpolator(Motion.STANDARD).start()
                    } else if (event.action == KeyEvent.ACTION_UP && v.hasFocus()) {
                        card.animate().scaleX(Motion.FOCUS_SCALE).scaleY(Motion.FOCUS_SCALE)
                            .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.STANDARD).start()
                    }
                }
            }
            return false
        }
    }

    private object Diff : DiffUtil.ItemCallback<AppEntry>() {
        override fun areItemsTheSame(a: AppEntry, b: AppEntry) = a.packageName == b.packageName
        override fun areContentsTheSame(a: AppEntry, b: AppEntry) =
            a.label == b.label && a.lastUpdate == b.lastUpdate && a.component == b.component
    }

    companion object {
        private const val PAYLOAD_STATE = "state"
        private const val HIDDEN_ALPHA = 100

        /** Cores discretas para cards de apps sem banner, escolhidas pelo pacote (estável). */
        private val TINTS: Array<ColorStateList> = intArrayOf(
            0xFF23324A.toInt(), 0xFF2E2A4D.toInt(), 0xFF1F3B3A.toInt(), 0xFF3C2A35.toInt(),
            0xFF2D3A26.toInt(), 0xFF3B3226.toInt(), 0xFF25344F.toInt(), 0xFF352A45.toInt(),
        ).map { ColorStateList.valueOf(it) }.toTypedArray()

        private fun tintFor(pkg: String): ColorStateList = TINTS[(pkg.hashCode() and 0x7FFFFFFF) % TINTS.size]
    }
}
