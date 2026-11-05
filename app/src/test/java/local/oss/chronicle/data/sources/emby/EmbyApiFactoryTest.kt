package local.oss.chronicle.data.sources.emby

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmbyApiFactoryTest {
    @Test
    fun `normalizeServerUrl adds the Emby API path`() {
        assertThat(EmbyApiFactory.normalizeServerUrl("https://server.example:8443"))
            .isEqualTo("https://server.example:8443/emby/")
    }

    @Test
    fun `normalizeServerUrl preserves an existing Emby path`() {
        assertThat(EmbyApiFactory.normalizeServerUrl("https://server.example:8443/emby/"))
            .isEqualTo("https://server.example:8443/emby/")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalizeServerUrl rejects query parameters`() {
        EmbyApiFactory.normalizeServerUrl("https://server.example:8443?api_key=secret")
    }

    @Test
    fun `toTrack maps Emby identifiers and time values`() {
        val track =
            EmbySyncRepository.toTrack(
                item =
                    EmbyItem(
                        id = "track-id",
                        name = "Chapter One",
                        album = "A Book",
                        albumId = "album-id",
                        albumArtist = "An Author",
                        runTimeTicks = 12_000_000L,
                        userData = EmbyUserData(playbackPositionTicks = 4_500_000L),
                    ),
                libraryId = "emby:library:view-id",
                fallbackIndex = 0,
            )

        assertThat(track.id).isEqualTo("emby:track-id")
        assertThat(track.parentKey).isEqualTo("emby:album-id")
        assertThat(track.libraryId).isEqualTo("emby:library:view-id")
        assertThat(track.duration).isEqualTo(1_200L)
        assertThat(track.progress).isEqualTo(450L)
        assertThat(track.media).isEqualTo("Audio/track-id/stream?static=true")
    }
}
