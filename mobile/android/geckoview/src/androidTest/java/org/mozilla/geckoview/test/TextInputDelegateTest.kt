/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

package org.mozilla.geckoview.test

import android.os.SystemClock
import android.support.test.InstrumentationRegistry
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.AssertCalled
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.ReuseSession
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.WithDevToolsAPI
import org.mozilla.geckoview.test.util.Callbacks

import android.support.test.filters.MediumTest
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

import org.hamcrest.Matchers.*
import org.junit.Assume.assumeThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter

@MediumTest
@RunWith(Parameterized::class)
@WithDevToolsAPI
class TextInputDelegateTest : BaseSessionTest() {
    // "parameters" needs to be a static field, so it has to be in a companion object.
    companion object {
        @get:Parameterized.Parameters(name = "{0}")
        @JvmStatic
        val parameters: List<Array<out Any>> = listOf(
                arrayOf("#input"),
                arrayOf("#textarea"),
                arrayOf("#contenteditable"),
                arrayOf("#designmode"))
    }

    @field:Parameter(0) @JvmField var id: String = ""

    private val textContent: String get() = when (id) {
        "#contenteditable" -> mainSession.evaluateJS("$('$id').textContent")
        "#designmode" -> mainSession.evaluateJS("$('$id').contentDocument.body.textContent")
        else -> mainSession.evaluateJS("$('$id').value")
    } as String

    private fun getJSOffsetPair(offsets: Any?): Pair<Int, Int> {
        val list = offsets.asJSList<Double>()
        return Pair(list[0].toInt(), list[1].toInt())
    }

    private val selectionOffsets: Pair<Int, Int> get() = getJSOffsetPair(when (id) {
        "#contenteditable" -> mainSession.evaluateJS("""[
                document.getSelection().anchorOffset,
                document.getSelection().focusOffset]""")
        "#designmode" -> mainSession.evaluateJS("""(function() {
                    var sel = $('$id').contentDocument.getSelection();
                    var text = $('$id').contentDocument.body.firstChild;
                    return [sel.anchorOffset, sel.focusOffset];
                })()""")
        else -> mainSession.evaluateJS("[ $('$id').selectionStart, $('$id').selectionEnd ]")
    })

    private fun pressKey(keyCode: Int) {
        // Create a Promise to listen to the key event, and wait on it below.
        val promise = mainSession.evaluateJS(
                "new Promise(r => window.addEventListener('keyup', r, { once: true }))")
        val time = SystemClock.uptimeMillis()
        val keyEvent = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0);
        mainSession.textInput.onKeyDown(keyCode, keyEvent)
        mainSession.textInput.onKeyUp(keyCode, KeyEvent.changeAction(keyEvent, KeyEvent.ACTION_UP))
        promise.asJSPromise().value
    }

    @Test fun restartInput() {
        // Check that restartInput is called on focus and blur.
        mainSession.loadTestPath(INPUTS_PATH)
        mainSession.waitForPageStop()

        mainSession.evaluateJS("$('$id').focus()")
        mainSession.waitUntilCalled(object : Callbacks.TextInputDelegate {
            @AssertCalled(count = 1)
            override fun restartInput(session: GeckoSession, reason: Int) {
                assertThat("Reason should be correct",
                           reason, equalTo(GeckoSession.TextInputDelegate.RESTART_REASON_FOCUS))
            }
        })

        mainSession.evaluateJS("$('$id').blur()")
        mainSession.waitUntilCalled(object : Callbacks.TextInputDelegate {
            @AssertCalled(count = 1)
            override fun restartInput(session: GeckoSession, reason: Int) {
                assertThat("Reason should be correct",
                           reason, equalTo(GeckoSession.TextInputDelegate.RESTART_REASON_BLUR))
            }

            // Also check that showSoftInput/hideSoftInput are not called before a user action.
            @AssertCalled(count = 0)
            override fun showSoftInput(session: GeckoSession) {
            }

            @AssertCalled(count = 0)
            override fun hideSoftInput(session: GeckoSession) {
            }
        })
    }

    @Test fun restartInput_temporaryFocus() {
        // Our user action trick doesn't work for design-mode, so we can't test that here.
        assumeThat("Not in designmode", id, not(equalTo("#designmode")))

        mainSession.loadTestPath(INPUTS_PATH)
        mainSession.waitForPageStop()

        // Focus the input once here and once below, but we should only get a
        // single restartInput or showSoftInput call for the second focus.
        mainSession.evaluateJS("$('$id').focus(); $('$id').blur()")

        // Simulate a user action so we're allowed to show/hide the keyboard.
        pressKey(KeyEvent.KEYCODE_CTRL_LEFT)
        mainSession.evaluateJS("$('$id').focus()")

        mainSession.waitUntilCalled(object : Callbacks.TextInputDelegate {
            @AssertCalled(count = 1, order = [1])
            override fun restartInput(session: GeckoSession, reason: Int) {
                assertThat("Reason should be correct",
                           reason, equalTo(GeckoSession.TextInputDelegate.RESTART_REASON_FOCUS))
            }

            @AssertCalled(count = 1, order = [2])
            override fun showSoftInput(session: GeckoSession) {
                super.showSoftInput(session)
            }

            @AssertCalled(count = 0)
            override fun hideSoftInput(session: GeckoSession) {
                super.hideSoftInput(session)
            }
        })
    }

    @Test fun restartInput_temporaryBlur() {
        // Our user action trick doesn't work for design-mode, so we can't test that here.
        assumeThat("Not in designmode", id, not(equalTo("#designmode")))

        mainSession.loadTestPath(INPUTS_PATH)
        mainSession.waitForPageStop()

        // Simulate a user action so we're allowed to show/hide the keyboard.
        pressKey(KeyEvent.KEYCODE_CTRL_LEFT)
        mainSession.evaluateJS("$('$id').focus()")
        mainSession.waitUntilCalled(GeckoSession.TextInputDelegate::class,
                                    "restartInput", "showSoftInput")

        // We should get a pair of restartInput calls for the blur/focus,
        // but only one showSoftInput call and no hideSoftInput call.
        mainSession.evaluateJS("$('$id').blur(); $('$id').focus()")

        mainSession.waitUntilCalled(object : Callbacks.TextInputDelegate {
            @AssertCalled(count = 2, order = [1])
            override fun restartInput(session: GeckoSession, reason: Int) {
                assertThat("Reason should be correct", reason, equalTo(forEachCall(
                        GeckoSession.TextInputDelegate.RESTART_REASON_BLUR,
                        GeckoSession.TextInputDelegate.RESTART_REASON_FOCUS)))
            }

            @AssertCalled(count = 1, order = [2])
            override fun showSoftInput(session: GeckoSession) {
            }

            @AssertCalled(count = 0)
            override fun hideSoftInput(session: GeckoSession) {
            }
        })
    }

    @Test fun showHideSoftInput() {
        // Our user action trick doesn't work for design-mode, so we can't test that here.
        assumeThat("Not in designmode", id, not(equalTo("#designmode")))

        mainSession.loadTestPath(INPUTS_PATH)
        mainSession.waitForPageStop()

        // Simulate a user action so we're allowed to show/hide the keyboard.
        pressKey(KeyEvent.KEYCODE_CTRL_LEFT)

        mainSession.evaluateJS("$('$id').focus()")
        mainSession.waitUntilCalled(object : Callbacks.TextInputDelegate {
            @AssertCalled(count = 1, order = [1])
            override fun restartInput(session: GeckoSession, reason: Int) {
            }

            @AssertCalled(count = 1, order = [2])
            override fun showSoftInput(session: GeckoSession) {
            }

            @AssertCalled(count = 0)
            override fun hideSoftInput(session: GeckoSession) {
            }
        })

        mainSession.evaluateJS("$('$id').blur()")
        mainSession.waitUntilCalled(object : Callbacks.TextInputDelegate {
            @AssertCalled(count = 1, order = [1])
            override fun restartInput(session: GeckoSession, reason: Int) {
            }

            @AssertCalled(count = 0)
            override fun showSoftInput(session: GeckoSession) {
            }

            @AssertCalled(count = 1, order = [2])
            override fun hideSoftInput(session: GeckoSession) {
            }
        })
    }

    private fun getText(ic: InputConnection) =
            ic.getExtractedText(ExtractedTextRequest(), 0).text.toString()

    private fun assertText(message: String, actual: String, expected: String) =
            // In an HTML editor, Gecko may insert an additional element that show up as a
            // return character at the end. Deal with that here.
            assertThat(message, actual.trimEnd('\n'), equalTo(expected))

    private fun assertText(message: String, ic: InputConnection, expected: String) {
        assertText(message, textContent, expected)
        assertText(message, getText(ic), expected)
    }

    private fun assertSelection(message: String, ic: InputConnection, start: Int, end: Int) {
        assertThat(message, selectionOffsets, equalTo(Pair(start, end)))

        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        assertThat(message, extracted.selectionStart, equalTo(start))
        assertThat(message, extracted.selectionEnd, equalTo(end))
    }

    private fun assertSelectionAt(message: String, ic: InputConnection, value: Int) =
            assertSelection(message, ic, value, value)

    private fun assertTextAndSelection(message: String, ic: InputConnection,
                                       expected: String, start: Int, end: Int) {
        assertText(message, textContent, expected)
        assertThat(message, selectionOffsets, equalTo(Pair(start, end)))

        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        assertText(message, extracted.text.toString(), expected)
        assertThat(message, extracted.selectionStart, equalTo(start))
        assertThat(message, extracted.selectionEnd, equalTo(end))
    }

    private fun assertTextAndSelectionAt(message: String, ic: InputConnection,
                                         expected: String, value: Int) =
            assertTextAndSelection(message, ic, expected, value, value)

    @ReuseSession(false)
    @Test fun inputConnection() {
        mainSession.textInput.view = View(InstrumentationRegistry.getTargetContext())

        mainSession.loadTestPath(INPUTS_PATH)
        mainSession.waitForPageStop()

        mainSession.evaluateJS("$('$id').focus()")
        mainSession.waitUntilCalled(GeckoSession.TextInputDelegate::class, "restartInput")

        val ic = mainSession.textInput.onCreateInputConnection(EditorInfo())!!
        ic.deleteSurroundingText(0, getText(ic).length)
        assertTextAndSelectionAt("Input should be empty", ic, "", 0)

        /*
        ic.setComposingText("f", 1)
        assertTextAndSelectionAt("Can start composition", ic, "f", 1)
        ic.setComposingText("foo", 1)
        assertTextAndSelectionAt("Can update composition", ic, "foo", 3)
        ic.finishComposingText()
        assertTextAndSelectionAt("Can finish composition", ic, "foo", 0)

        ic.setComposingRegion(0, 2)
        assertTextAndSelectionAt("Can set composing region", ic, "foo", 3)
        ic.setComposingText("d", 1)
        assertTextAndSelectionAt("Can set composing region text", ic, "do", 1)*/
    }
}
