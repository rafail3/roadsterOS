package ro.rdstr.os.screens

import android.app.Activity
import android.view.View
import kotlinx.coroutines.CoroutineScope
import ro.rdstr.ui.shell.Layer

/**
 * One screen's glue, made once per activity: its view (a core-ui view) and the collectors that keep it bound to
 * `CarRuntime` while the activity is in front. Its actions call `CarRuntime` (`shellAction`, `send`, the `@Emulates`
 * functions).
 */
interface Screen {
    val view: View

    /** Collects the runtime's state into [view] while [scope] lives (from resume to pause). */
    fun bind(scope: CoroutineScope)
}

/** Every screen the shell stacks, one line each: the layer its view goes in and the glue that makes it. */
object Screens {
    val ALL: List<Pair<Layer, (Activity) -> Screen>> = listOf(
    )
}
