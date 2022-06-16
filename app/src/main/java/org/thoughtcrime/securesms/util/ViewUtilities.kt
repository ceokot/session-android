package org.thoughtcrime.securesms.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.FloatEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import network.loki.messenger.R

fun View.contains(point: PointF): Boolean {
    return hitRect.contains(point.x.toInt(), point.y.toInt())
}

val View.hitRect: Rect
    get()  {
        val rect = Rect()
        getHitRect(rect)
        return rect
    }

@ColorInt
fun Context.getAccentColor() = getColorFromAttr(R.attr.colorAccent) // TODO: allow for customizable accent

@ColorInt
fun Context.getColorFromAttr(
    @AttrRes attrColor: Int,
    typedValue: TypedValue = TypedValue(),
    resolveRefs: Boolean = true
): Int {
    theme.resolveAttribute(attrColor, typedValue, resolveRefs)
    return typedValue.data
}

fun View.animateSizeChange(@DimenRes startSizeID: Int, @DimenRes endSizeID: Int, animationDuration: Long = 250) {
    val startSize = resources.getDimension(startSizeID)
    val endSize = resources.getDimension(endSizeID)
    animateSizeChange(startSize, endSize)
}

fun View.animateSizeChange(startSize: Float, endSize: Float, animationDuration: Long = 250) {
    val layoutParams = this.layoutParams
    val animation = ValueAnimator.ofObject(FloatEvaluator(), startSize, endSize)
    animation.duration = animationDuration
    animation.addUpdateListener { animator ->
        val size = animator.animatedValue as Float
        layoutParams.width = size.toInt()
        layoutParams.height = size.toInt()
        this.layoutParams = layoutParams
    }
    animation.start()
}

fun View.fadeIn(duration: Long = 150) {
    visibility = View.VISIBLE
    animate().setDuration(duration).alpha(1.0f).start()
}

fun View.fadeOut(duration: Long = 150) {
    animate().setDuration(duration).alpha(0.0f).setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator?) {
            super.onAnimationEnd(animation)
            visibility = View.GONE
        }
    })
}
