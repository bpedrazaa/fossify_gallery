package org.fossify.gallery.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.views.MyGridLayoutManager

class CenteredGridLayoutManager(
    context: Context,
    attrs: AttributeSet,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : MyGridLayoutManager(context, attrs, defStyleAttr, defStyleRes) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        super.onLayoutChildren(recycler, state)
        centerLastRow(state)
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        val scrolled = super.scrollVerticallyBy(dy, recycler, state)
        centerLastRow(state)
        return scrolled
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        val scrolled = super.scrollHorizontallyBy(dx, recycler, state)
        centerLastRow(state)
        return scrolled
    }

    private fun centerLastRow(state: RecyclerView.State) {
        if (spanCount <= 1 || state.isPreLayout) {
            return
        }

        val total = state.itemCount
        val remainder = total % spanCount
        resetTranslations()
        if (total == 0 || remainder == 0) {
            return
        }

        val lastRowStart = total - remainder
        if (findViewByPosition(lastRowStart) == null) {
            return
        }

        if (orientation == RecyclerView.VERTICAL) {
            val contentWidth = width - paddingLeft - paddingRight
            if (contentWidth <= 0) {
                return
            }

            val spanWidth = contentWidth.toFloat() / spanCount
            val offset = (spanCount - remainder) * spanWidth / 2f
            val signedOffset = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -offset else offset
            for (position in lastRowStart until total) {
                findViewByPosition(position)?.translationX = signedOffset
            }
        } else {
            val contentHeight = height - paddingTop - paddingBottom
            if (contentHeight <= 0) {
                return
            }

            val spanHeight = contentHeight.toFloat() / spanCount
            val offset = (spanCount - remainder) * spanHeight / 2f
            for (position in lastRowStart until total) {
                findViewByPosition(position)?.translationY = offset
            }
        }
    }

    private fun resetTranslations() {
        for (index in 0 until childCount) {
            getChildAt(index)?.apply {
                translationX = 0f
                translationY = 0f
            }
        }
    }
}
