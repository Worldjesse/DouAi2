package com.douai.module.ui.miuix

import android.text.TextUtils
import android.widget.EditText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Applies one visible edit to the compatibility widget; callers suppress their own watcher feedback. */
internal fun applyNativeTextEdit(input: EditText, previous: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val editable = input.text
    val old = if (TextUtils.equals(editable, previous.text)) previous.text else editable.toString()
    if (old == next.text) {
        return next.copy(selection = next.selection.bounded(old.length),
            composition = next.composition?.bounded(old.length)?.takeUnless { it.collapsed })
            .also { input.syncSelection(it.selection) }
    }

    val edit = textEdit(old, previous.selection, next)
    // Passing the original CharSequence range keeps native InputFilter behavior for middle edits.
    editable.replace(edit.start, edit.oldEnd, next.text, edit.start, edit.newEnd)
    val latest = input.text
    if (latest !== editable) return next.nativeWatcherResult(input, latest.toString())
    val actual = if (TextUtils.equals(latest, next.text)) next.text else latest.toString()
    if (actual != next.text && !edit.keepsOutsideText(old, actual)) {
        return next.nativeWatcherResult(input, actual)
    }
    val requestedLength = edit.newEnd - edit.start
    val insertedLength = (actual.length - (old.length - (edit.oldEnd - edit.start))).coerceAtLeast(0)
    fun position(value: Int): Int = when {
        value <= edit.start -> value
        value >= edit.newEnd -> value + insertedLength - requestedLength
        else -> edit.start + (value - edit.start).coerceAtMost(insertedLength)
    }.coerceIn(0, actual.length)
    fun range(value: TextRange) = TextRange(position(value.start), position(value.end))
    val value = next.copy(text = actual, selection = range(next.selection),
        composition = next.composition?.let(::range)?.takeUnless { it.collapsed })
    input.syncSelection(value.selection)
    return value
}

private data class NativeTextEdit(val start: Int, val oldEnd: Int, val newEnd: Int)

private fun NativeTextEdit.keepsOutsideText(old: String, actual: String): Boolean {
    val suffix = old.length - oldEnd
    return actual.length >= start + suffix && old.regionMatches(0, actual, 0, start) &&
        old.regionMatches(oldEnd, actual, actual.length - suffix, suffix)
}

private fun TextFieldValue.nativeWatcherResult(input: EditText, actual: String): TextFieldValue {
    // A synchronous formatter owns its new buffer or text outside our edit; do not override its cursor.
    val selection = TextRange(input.selectionStart, input.selectionEnd).bounded(actual.length)
    input.syncSelection(selection)
    return copy(text = actual, selection = selection, composition = null)
}

private fun textEdit(old: String, selection: TextRange, next: TextFieldValue): NativeTextEdit {
    val selected = selection.bounded(old.length)
    fun replacement(start: Int, end: Int): NativeTextEdit? {
        val suffix = old.length - end
        val newEnd = next.text.length - suffix
        if (newEnd < start || !old.regionMatches(0, next.text, 0, start)
            || !old.regionMatches(end, next.text, newEnd, suffix)) return null
        return NativeTextEdit(start, end, newEnd).wholeSurrogates(old, next.text)
    }
    // Preserve the user's selected replacement, including full-selection paste and repeated text.
    replacement(selected.min, selected.max)?.let { return it }
    if (selected.collapsed && next.selection.collapsed && next.text.length < old.length) {
        val removed = old.length - next.text.length
        val caret = selected.start
        if (next.selection.start == caret - removed) replacement(caret - removed, caret)?.let { return it }
        if (next.selection.start == caret && caret + removed <= old.length) {
            replacement(caret, caret + removed)?.let { return it }
        }
    }
    var start = 0
    while (start < old.length && start < next.text.length && old[start] == next.text[start]) start++
    var oldEnd = old.length
    var newEnd = next.text.length
    while (oldEnd > start && newEnd > start && old[oldEnd - 1] == next.text[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return NativeTextEdit(start, oldEnd, newEnd).wholeSurrogates(old, next.text)
}

private fun NativeTextEdit.wholeSurrogates(old: String, next: String): NativeTextEdit {
    var safeStart = start
    var safeOldEnd = oldEnd
    var safeNewEnd = newEnd
    if (old.splitsSurrogate(safeStart) || next.splitsSurrogate(safeStart)) safeStart--
    if (old.splitsSurrogate(safeOldEnd) || next.splitsSurrogate(safeNewEnd)) {
        safeOldEnd++
        safeNewEnd++
    }
    return NativeTextEdit(safeStart, safeOldEnd, safeNewEnd)
}

private fun String.splitsSurrogate(position: Int) = position > 0 && position < length &&
    this[position - 1].isHighSurrogate() && this[position].isLowSurrogate()

private fun TextRange.bounded(length: Int) = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

private fun EditText.syncSelection(selection: TextRange) {
    if (selectionStart != selection.start || selectionEnd != selection.end) {
        setSelection(selection.start, selection.end)
    }
}
