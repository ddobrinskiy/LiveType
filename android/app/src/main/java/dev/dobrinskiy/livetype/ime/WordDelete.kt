package dev.dobrinskiy.livetype.ime

import android.view.inputmethod.InputConnection

/**
 * Deleting a whole word backwards, the way a desktop editor's ctrl+backspace
 * does it: the run of whitespace glued to the cursor goes first, then the word
 * in front of it.
 *
 * Deliberately free of any composing-region handling — the caller owns that,
 * because freezing a composing region also has bookkeeping consequences for
 * whoever is writing into it.
 */
object WordDelete {

    /**
     * How far back to look. One `getTextBeforeCursor` round trip is IPC to the
     * target app, and this runs on a repeat timer, so it stays small; a word
     * longer than this is deleted in more than one step, which is invisible at
     * repeat speed.
     */
    const val LOOKBEHIND_CHARS = 64

    /**
     * Deletes the word before the cursor of [connection].
     *
     * Returns false — having changed nothing — when there is nothing it can
     * safely do: no text available (`getTextBeforeCursor` returns null when the
     * connection is gone), an empty field, the cursor already at the start, or
     * a live selection, which a plain `KEYCODE_DEL` deletes correctly and this
     * method would step around. The caller should fall back to a single
     * character delete in that case.
     */
    fun beforeCursor(connection: InputConnection): Boolean {
        // With a selection up, "before the cursor" is before the selection
        // start, so deleting there would leave the selection untouched and eat
        // text the user did not point at.
        if (!connection.getSelectedText(0).isNullOrEmpty()) return false
        val before = connection.getTextBeforeCursor(LOOKBEHIND_CHARS, 0) ?: return false
        val count = charsToDelete(before)
        if (count <= 0) return false
        return connection.deleteSurroundingText(count, 0)
    }

    /**
     * How many characters of [textBeforeCursor] one word-delete should remove:
     * the trailing whitespace, then everything back to the previous whitespace
     * or the start of the text. 0 when there is nothing left to delete.
     */
    fun charsToDelete(textBeforeCursor: CharSequence?): Int {
        val text = textBeforeCursor ?: return 0
        var index = text.length
        while (index > 0 && text[index - 1].isWhitespace()) index--
        while (index > 0 && !text[index - 1].isWhitespace()) index--
        var count = text.length - index
        // The lookbehind window may itself begin in the middle of a surrogate
        // pair; never hand back a count that would split one.
        if (count == text.length && count > 0 && text[0].isLowSurrogate()) count--
        return count
    }
}
