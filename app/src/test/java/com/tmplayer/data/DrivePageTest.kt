package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drive's answer pages, read off the wire (owner, 2026-11-07): the confirmation form
 * builds the follow up address whatever order or quoting the page uses, the quota page
 * is told apart from a plain refusal, and a sign in page is a refusal.
 */
class DrivePageTest {

    @Test
    fun aConfirmFormBuildsTheFollowUpAddress() {
        val page = """
            <html><body>
            <form id="download-form" action="https://drive.usercontent.google.com/download?id=FILEID&amp;export=download" method="post">
              <input type="hidden" name="id" value="FILEID">
              <input type="hidden" name="export" value="download">
              <input type="hidden" name="confirm" value="t">
              <input type="hidden" name="uuid" value="abc-123">
              <input type="submit" value="Download anyway">
            </form>
            </body></html>
        """.trimIndent()
        val reading = DrivePage.read(page)
        assertEquals(
            DrivePageReading.Confirm(
                "https://drive.usercontent.google.com/download?id=FILEID&export=download" +
                    "&id=FILEID&export=download&confirm=t&uuid=abc-123",
            ),
            reading,
        )
    }

    @Test
    fun aConfirmFormInAnotherOrderAndQuotingBuildsTheSameShape() {
        val page = """
            <html><body>
            <form method='post' action='https://drive.usercontent.google.com/download'>
              <input name='confirm' type='hidden' value='xyz'>
              <input value='FILEID' name='id' type='hidden'>
              <input type=hidden name=export value=download>
            </form>
            </body></html>
        """.trimIndent()
        assertEquals(
            DrivePageReading.Confirm(
                "https://drive.usercontent.google.com/download?confirm=xyz&id=FILEID&export=download",
            ),
            DrivePage.read(page),
        )
    }

    @Test
    fun aFormForAnotherHostIsNotAConfirmation() {
        val page = """
            <html><body>
            <form action="https://example.com/download" method="post">
              <input type="hidden" name="id" value="FILEID">
            </form>
            </body></html>
        """.trimIndent()
        assertEquals(DrivePageReading.Refused, DrivePage.read(page))
    }

    @Test
    fun aQuotaPageReadsAsQuota() {
        val page = """
            <html><body>
            <p class="uc-error-caption">Too many users have viewed or downloaded this file.</p>
            <p class="uc-error-subcaption">You can't view or download this file at this time.</p>
            </body></html>
        """.trimIndent()
        assertEquals(DrivePageReading.Quota, DrivePage.read(page))
    }

    @Test
    fun aQuotaPageWithACurlyApostropheStillReadsAsQuota() {
        val page = "<html><body><p>You can’t view or download this file at this time.</p></body></html>"
        assertEquals(DrivePageReading.Quota, DrivePage.read(page))
    }

    @Test
    fun aSignInPageReadsAsRefused() {
        val page = """
            <html><body>
            <p>Sign in to continue to Google Drive</p>
            <form action="https://accounts.google.com/signin">
              <input type="hidden" name="continue" value="https://drive.google.com/">
            </form>
            </body></html>
        """.trimIndent()
        assertEquals(DrivePageReading.Refused, DrivePage.read(page))
    }

    @Test
    fun anEmptyOrRandomPageReadsAsRefusedWithoutThrowing() {
        assertEquals(DrivePageReading.Refused, DrivePage.read(""))
        assertEquals(DrivePageReading.Refused, DrivePage.read("not a page at all"))
        assertEquals(DrivePageReading.Refused, DrivePage.read("<form action="))
        assertEquals(DrivePageReading.Refused, DrivePage.read("<input type=\"hidden\""))
    }

    @Test
    fun aDirectAddressIsReadOutOfAPage() {
        val page = "<html><body><a href=\"https://drive.usercontent.google.com/download?id=FILEID&amp;export=download&amp;confirm=t\">Download</a></body></html>"
        assertEquals(
            "https://drive.usercontent.google.com/download?id=FILEID&export=download&confirm=t",
            DrivePage.directAddress(page),
        )
        assertNull(DrivePage.directAddress("<html><body>Nothing here.</body></html>"))
        assertNull(DrivePage.directAddress(""))
    }
}
