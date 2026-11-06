package ro.rdstr.ui

import android.view.View

/**
 * The emulator element this view draws, for the visual parity check (`emulator/scripts/capture-layout.mjs` on the
 * browser side, `core-ui` ParityCapture and `:core-ui:compareParity` on this side).
 *
 * The value is one segment of the element's key: its class list without state classes (`is-*`, `has-*`), joined with
 * ".", or its tag name when no class is left (`"wifi-bars.bar-wifi"`, `"bar-group"`, `"span"`). The full key is the
 * path of segments from the screen down, built from the keyed ancestors: views without a key are left out of the path.
 * Among keyed siblings with the same segment, ":<index>" is appended in child order, `View.GONE` ones not counted, as
 * the browser does not count `display: none` elements.
 */
var View.uiKey: String?
    get() = getTag(R.id.rdstr_ui_key) as String?
    set(value) = setTag(R.id.rdstr_ui_key, value)
