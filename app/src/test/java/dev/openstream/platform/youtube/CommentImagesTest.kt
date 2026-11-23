package dev.openstream.platform.youtube

import dev.openstream.platform.CommentImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentImagesTest {
    private fun surface(key: String, gif: String?) = """
        {"entityKey": "$key", "payload": {"commentSurfaceEntityPayload": {"key": "$key"${
            if (gif == null) "" else """, "imageAttachmentViewModel": {"commentImageAttachmentViewModel": {
                "width": 282, "height": 498, "aspectRatio": 0.566,
                "image": {"sources": [
                    {"url": "https://i.ytimg.com/c_exp/small/$gif.webp", "width": 141, "height": 249},
                    {"url": "https://i.ytimg.com/c_exp/$gif.webp", "width": 282, "height": 498}
                ], "imageFormatHint": "IMAGE_FORMAT_ANIMATED_WEBP"},
                "externalImageId": "$gif", "unavailableText": "GIF unavailable"}}"""
        }}}}
    """

    // A top-level thread, one without a GIF, and a reply, which comes as a bare view model.
    private val answer = """
        {"onResponseReceivedEndpoints": [{"reloadContinuationItemsCommand": {"continuationItems": [
            {"commentThreadRenderer": {"commentViewModel": {"commentViewModel": {
                "commentId": "UgyA", "commentKey": "c1", "commentSurfaceKey": "s1"}}}},
            {"commentThreadRenderer": {"commentViewModel": {"commentViewModel": {
                "commentId": "UgyB", "commentKey": "c2", "commentSurfaceKey": "s2"}}}},
            {"commentViewModel": {"commentId": "UgyC.reply", "commentKey": "c3", "commentSurfaceKey": "s3"}}
        ]}}],
        "frameworkUpdates": {"entityBatchUpdate": {"mutations": [
            ${surface("s1", "IMcY")}, ${surface("s2", null)}, ${surface("s3", "IMAh")},
            {"entityKey": "c1", "payload": {"commentEntityPayload": {"properties": {"commentId": "UgyA"}}}}
        ]}}}
    """

    @Test fun findsEachCommentsGifByItsSurface() {
        val found = commentImages(answer)
        assertEquals(setOf("UgyA", "UgyC.reply"), found.keys)
        assertEquals(CommentImage("https://i.ytimg.com/c_exp/IMcY.webp", 282, 498), found["UgyA"])
        assertEquals("https://i.ytimg.com/c_exp/IMAh.webp", found["UgyC.reply"]?.url)
    }

    @Test fun answersWithoutGifsAreLeftAlone() {
        assertTrue(commentImages("""{"contents": {"commentId": "x", "commentSurfaceKey": "s"}}""").isEmpty())
        assertTrue(commentImages("""{"commentImageAttachmentViewModel": """).isEmpty())
    }

    @Test fun keepsWhatItReadsForTheCommentsMadeLater() {
        CommentImages.read(answer)
        assertEquals(282, CommentImages["UgyA"]?.width)
        assertNull(CommentImages["UgyB"])
    }
}
