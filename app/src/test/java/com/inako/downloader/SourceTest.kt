package com.inako.downloader

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SourceTest {
    @Test fun findsLinkInsideSharedText() {
        assertEquals("https://example.org/watch?v=5", UrlResolver.find("Смотри (https://example.org/watch?v=5).")?.toString())
        assertEquals("https://example.org/watch(a)", UrlResolver.find("Видео https://example.org/watch(a).")?.toString())
    }

    @Test fun preservesEncodingAndSignedQuery() {
        assertEquals("https://example.org/a%20b.mp4?key=a%2Bb%2F", UrlResolver.find("https://example.org/a%20b.mp4?key=a%2Bb%2F")?.toString())
    }

    @Test fun rejectsCredentialsAndOtherSchemes() {
        listOf("file:///secret.mp4", "intent://test", "https://user:pass@example.org", "https://",
            "https:///example.org", "https://example.org/a\\b").forEach { assertNull(it, UrlResolver.validate(it)) }
    }

    @Test fun capsSelectedQuality() {
        assertEquals("bv+ba/b", formatSelector(0))
        assertEquals("bv[height<=720]+ba/b[height<=720]", formatSelector(720))
    }

    @Test fun classifiesErrorsAndBoundsRetries() {
        assertEquals(Problem.DRM, Problem.fromEngine("ERROR: this video is DRM protected"))
        assertEquals(Problem.AUTH, Problem.fromEngine("Sign in to confirm"))
        assertEquals(Problem.EXPIRED, Problem.fromHttp(410))
        assertEquals(Problem.AUTH, Problem.fromHttp(403))
        assertEquals(Problem.UNSUPPORTED, Problem.fromEngine("Unsupported URL"))
        assertTrue(shouldRetry(Problem.NETWORK, 2))
        assertFalse(shouldRetry(Problem.NETWORK, 3))
        assertFalse(shouldRetry(Problem.DRM, 1))
    }

    @Test fun progressNeedsKnownTotal() {
        assertNull(TransferProgress("loading", 10).fraction)
        assertEquals(0.5f, TransferProgress("loading", 50, 100).fraction ?: -1f, 0.0f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentTest {
    @Test fun acceptsTextSendAndExplicitView() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Видео https://example.org/video.mp4")
        assertEquals("https://example.org/video.mp4", ShareIntentHandler.link(send)?.toString())
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/live.m3u8"))
        assertTrue(UrlResolver.isManifest(requireNotNull(ShareIntentHandler.link(view))))
    }

    @Test fun rejectsOtherIntents() {
        assertNull(ShareIntentHandler.link(Intent(Intent.ACTION_SEND).setType("image/png")))
        assertNull(ShareIntentHandler.link(Intent(Intent.ACTION_VIEW, Uri.parse("file:///tracks/video.mp4"))))
    }
}
