package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Home button pressed again on Home (item 17, v1.0.16): the reselection has to
 * reach Home's own handlers, the refresh among them, and a press that is a move to
 * Home has to stay a move. The bar, its book of pages and the page's answer are the
 * real ones, composed as the app composes them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class HomeRetapTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun host(current: TouchDestination, wentHome: AtomicInteger, refreshed: AtomicInteger) {
        FormFactor.override(false)
        val chrome = TouchChrome()
        val nav = TouchNav(
            account = null,
            favoriteCount = 0,
            downloadCount = 0,
            updateVersion = null,
            onHome = { wentHome.incrementAndGet() },
            onChats = {}, onSearch = {}, onSettings = {}, onFavourites = {},
            onDownloads = {}, onHistory = {}, onUpdate = {},
        )
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize()) {
                    TouchNavHost(nav, chrome, "chats") {
                        CompositionLocalProvider(LocalTouchPageKey provides "chats") {
                            TouchPage(TouchPageSpec(current = current))
                            RefreshOnReselect(TouchDestination.Home) { refreshed.incrementAndGet() }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `home pressed again on home reaches the refresh, and does not navigate`() {
        val wentHome = AtomicInteger(0)
        val refreshed = AtomicInteger(0)
        host(TouchDestination.Home, wentHome, refreshed)
        rule.onNodeWithContentDescription("Home").performClick()
        rule.waitForIdle()
        assertEquals(1, refreshed.get())
        assertEquals(0, wentHome.get())
    }

    @Test
    fun `home pressed from chats navigates, and does not refresh`() {
        val wentHome = AtomicInteger(0)
        val refreshed = AtomicInteger(0)
        host(TouchDestination.Chats, wentHome, refreshed)
        rule.onNodeWithContentDescription("Home").performClick()
        rule.waitForIdle()
        assertEquals(1, wentHome.get())
        assertEquals(0, refreshed.get())
    }
}
