package com.combustible12.hdresourceextractor

import org.junit.Assert.*
import org.junit.Test

class DownloadCoreTest {
    private fun parse(xml: String) = DashDownloader.parse("https://example.com/path/manifest.mpd", xml)
    @Test fun byteRangesAndInheritedBase() {
        val tracks = parse("""<MPD><BaseURL>../assets/</BaseURL><Period><AdaptationSet mimeType="video/mp4">
            <SegmentList><Initialization sourceURL="all.mp4" range="0-99"/><SegmentURL media="all.mp4" mediaRange="100-299"/></SegmentList>
            <Representation id="v" bandwidth="1000"/></AdaptationSet></Period></MPD>""")
        assertEquals(listOf("https://example.com/assets/all.mp4#__hd_range=0-99", "https://example.com/assets/all.mp4#__hd_range=100-299"), tracks.single().urls)
    }
    @Test fun multiplePeriodsAndPartialTemplateInheritance() {
        val dollar = '$'
        val tracks = parse("""<MPD mediaPresentationDuration="PT8S"><Period duration="PT4S"><AdaptationSet mimeType="video/mp4">
            <SegmentTemplate initialization="init.mp4" media="s${dollar}Number%03d${dollar}.m4s" duration="2" timescale="1"/>
            <Representation id="v"><SegmentTemplate startNumber="5"/></Representation></AdaptationSet></Period>
            <Period><AdaptationSet mimeType="video/mp4"><Representation><BaseURL>second.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>""")
        assertEquals(2, tracks.size)
        assertEquals(0, tracks[0].period); assertEquals(1, tracks[1].period)
        assertTrue(tracks[0].urls[1].endsWith("s005.m4s")); assertTrue(tracks[0].urls[2].endsWith("s006.m4s"))
    }
    @Test fun nearestAddressingModeOverridesParent() {
        val tracks = parse("""<MPD><Period duration="PT2S"><AdaptationSet mimeType="video/mp4">
            <SegmentTemplate initialization="bad" media="bad" duration="1"/>
            <Representation><SegmentList><Initialization sourceURL="init"/><SegmentURL media="part"/></SegmentList></Representation>
            </AdaptationSet></Period></MPD>""")
        assertTrue(tracks.single().urls.last().endsWith("/part"))
    }
    @Test fun negativeRepeatUsesPresentationTimeOffset() {
        val d = '$'
        val tracks = parse("""<MPD><Period duration="PT4S"><AdaptationSet mimeType="video/mp4"><Representation>
            <SegmentTemplate initialization="init" media="${d}Time${d}" timescale="1" presentationTimeOffset="10">
            <SegmentTimeline><S t="10" d="2" r="-1"/></SegmentTimeline></SegmentTemplate>
            </Representation></AdaptationSet></Period></MPD>""")
        assertEquals(listOf("https://example.com/path/init", "https://example.com/path/10", "https://example.com/path/12"), tracks.single().urls)
    }
    @Test fun identityRetainsContentSelectingQuery() {
        assertEquals(ResourceIdentity.of("https://EXAMPLE.com:443/a#x"), ResourceIdentity.of("https://example.com/a"))
        assertNotEquals(ResourceIdentity.of("https://example.com/a?id=1"), ResourceIdentity.of("https://example.com/a?id=2"))
        assertNotEquals(ResourceIdentity.of("https://one.test/image.jpg"), ResourceIdentity.of("https://two.test/image.jpg"))
    }
    @Test fun cancellationTakesPrecedenceOverPause() {
        val c = SegmentTaskQueue.Control(); c.paused.set(true); c.cancelled.set(true)
        try { c.check(); fail("must stop") } catch (e: SegmentTaskQueue.Stopped) { assertFalse(e.paused) }
    }
}
