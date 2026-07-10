package org.jellyfin.androidtv.integration.dream.composable

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.integration.dream.model.DreamContent
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ClockBehavior
import org.jellyfin.androidtv.ui.base.SeekbarDefaults
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.LyricsDtoBox
import org.jellyfin.androidtv.ui.composable.modifier.fadingEdges
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.composable.rememberCurrentTime
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.jellyfin.lyrics.lyrics
import org.jellyfin.playback.jellyfin.lyrics.lyricsFlow
import org.jellyfin.playback.media3.exoplayer.AudioSpectrum
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject

// Soft black shadow used as an outline so text/controls stay readable on top of the backdrop
// (there is no dark overlay behind them).
private val overlayShadow = Shadow(
	color = Color.Black.copy(alpha = 0.75f),
	offset = Offset(0f, 2f),
	blurRadius = 8f,
)

@Composable
fun DreamContentNowPlaying(
	content: DreamContent.NowPlaying,
) = Box(
	modifier = Modifier.fillMaxSize(),
) {
	val api = koinInject<ApiClient>()
	val playbackManager = koinInject<PlaybackManager>()
	val userPreferences = koinInject<UserPreferences>()

	val hideNowPlayingCover = userPreferences[UserPreferences.screensaverHideNowPlayingCover]
	val showLyrics = userPreferences[UserPreferences.screensaverShowLyrics]
	val showVisualizer = userPreferences[UserPreferences.screensaverAudioVisualizer]
	val showRipple = userPreferences[UserPreferences.screensaverBackdropRipple]
	val visualizerRadial = userPreferences[UserPreferences.screensaverVisualizerRadial]
	val visualizerCenterOut = userPreferences[UserPreferences.screensaverVisualizerCenterOut]
	val visualizerCoverColor = userPreferences[UserPreferences.screensaverVisualizerCoverColor]
	val centeredLayout = userPreferences[UserPreferences.screensaverCenteredLayout]
	val hideSongTitle = userPreferences[UserPreferences.screensaverHideSongTitle]
	val hideArtist = userPreferences[UserPreferences.screensaverHideArtist]
	val hideClock = userPreferences[UserPreferences.screensaverHideClock]
	val clockEnabled = userPreferences[UserPreferences.clockBehavior].let {
		it == ClockBehavior.ALWAYS || it == ClockBehavior.IN_MENUS
	}

	val lyrics = content.entry.run { lyricsFlow.collectAsState(lyrics) }.value

	val primaryImage = content.item.itemImages[ImageType.PRIMARY]
		?: content.item.albumPrimaryImage
		?: content.item.parentImages[ImageType.PRIMARY]

	// Prefer a real backdrop (e.g. the album-cover backdrop) over the low-res blurhash wash,
	// falling back to the cover so there is always a full-resolution background.
	val backgroundImage = content.item.itemBackdropImages.firstOrNull()
		?: content.item.parentBackdropImages.firstOrNull()
		?: primaryImage

	val visualizerPalette = rememberVisualizerPalette(primaryImage?.getUrl(api), showVisualizer || showRipple, visualizerCoverColor)

	// Bass-driven backdrop pulse (zoom + rings). The rings reuse the visualizer's main accent colour so
	// the effect stays cohesive with the bars; the middle stop is the cover's dominant colour (or white).
	val backdropPulse = rememberBackdropPulse(showRipple)
	val rippleColor = visualizerPalette.stops[visualizerPalette.stops.size / 2].second

	val artistText = content.item.run {
		val artistNames = artists.orEmpty()
		val albumArtistNames = albumArtists?.mapNotNull { it.name }.orEmpty()

		when {
			artistNames.isNotEmpty() -> artistNames
			albumArtistNames.isNotEmpty() -> albumArtistNames
			else -> listOfNotNull(albumArtist)
		}.joinToString(", ")
	}

	// The visualizer and the ripple both read the live spectrum, so keep the tap alive if either is on.
	if (showVisualizer || showRipple) {
		DisposableEffect(Unit) {
			AudioSpectrum.acquire()
			onDispose { AudioSpectrum.release() }
		}
	}

	// Background. graphicsLayer scale reads backdropPulse.scale in the draw phase (1f when ripple is
	// off), so the artwork zooms very slightly on the bass without triggering recomposition.
	if (backgroundImage != null) {
		AsyncImage(
			url = backgroundImage.getUrl(api),
			blurHash = backgroundImage.blurHash,
			scaleType = ImageView.ScaleType.CENTER_CROP,
			modifier = Modifier
				.fillMaxSize()
				.graphicsLayer {
					scaleX = backdropPulse.scale
					scaleY = backdropPulse.scale
				},
		)
	}

	// Expanding bass rings over the backdrop, under the text/visualizer.
	if (showRipple) {
		BackdropRippleOverlay(
			pulse = backdropPulse,
			color = rippleColor,
			modifier = Modifier.fillMaxSize(),
		)
	}

	// Audio visualizer (over the side fill). No top inset in the centered layout: the clock is
	// centered, so the top corners are free.
	if (showVisualizer) {
		AudioVisualizer(
			modifier = Modifier.fillMaxSize(),
			radial = visualizerRadial,
			centerOut = visualizerCenterOut,
			colorStops = visualizerPalette.stops,
			topInset = !centeredLayout,
		)
	}

	// Lyrics overlay (on top of background)
	if (lyrics != null && showLyrics) {
		val playState by remember { playbackManager.state.playState }.collectAsState()
		val positionInfo by rememberPlayerPositionInfo(playbackManager)

		LyricsDtoBox(
			lyricDto = lyrics,
			currentTimestamp = positionInfo.active,
			duration = positionInfo.duration,
			paused = playState != PlayState.PLAYING,
			fontSize = 22.sp,
			color = Color.White,
			shadow = overlayShadow,
			modifier = Modifier
				.fillMaxSize()
				.fadingEdges(vertical = 250.dp)
				.padding(horizontal = 50.dp),
		)
	}

	// Centered clock at the top for the focused layout (the default top-right clock is suppressed
	// by DreamView in this case).
	if (centeredLayout && clockEnabled && !hideClock) {
		val currentTime by rememberCurrentTime()
		Box(
			modifier = Modifier
				.align(Alignment.TopCenter)
				.overscan(),
		) {
			OutlinedText(
				text = currentTime,
				color = Color.White,
				fontSize = 20.sp,
			)
		}
	}

	if (centeredLayout) {
		// Track info + seek bar centered over the (square) cover, so the visualizer frames them.
		val configuration = LocalConfiguration.current
		val coverFraction = (configuration.screenHeightDp.toFloat() / configuration.screenWidthDp)
			.coerceIn(0.35f, 1f)

		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth(coverFraction)
				.overscan(),
		) {
			if (!hideSongTitle) {
				OutlinedText(
					text = content.item.name.orEmpty(),
					color = Color.White,
					fontSize = 26.sp,
					textAlign = TextAlign.Center,
					fillWidth = true,
				)
			}

			if (!hideArtist) {
				OutlinedText(
					text = artistText,
					color = Color(0.8f, 0.8f, 0.8f),
					fontSize = 18.sp,
					textAlign = TextAlign.Center,
					fillWidth = true,
				)
			}

			Spacer(modifier = Modifier.height(10.dp))

			PlayerSeekbar(
				playbackManager = playbackManager,
				colors = SeekbarDefaults.colors(
					backgroundColor = Color.White.copy(alpha = 0.2f),
					progressColor = Color.White,
					bufferColor = Color.Transparent,
				),
				modifier = Modifier
					.fillMaxWidth()
					.height(4.dp)
					.shadow(2.dp, RoundedCornerShape(2.dp)),
			)
		}
	} else {
		// Default bottom-left metadata row.
		Row(
			verticalAlignment = Alignment.Bottom,
			horizontalArrangement = Arrangement.spacedBy(20.dp),
			modifier = Modifier
				.align(Alignment.BottomStart)
				.overscan(),
		) {
			if (primaryImage != null && !hideNowPlayingCover) {
				AsyncImage(
					url = primaryImage.getUrl(api),
					blurHash = primaryImage.blurHash,
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier
						.size(128.dp)
						.clip(RoundedCornerShape(5.dp))
				)
			}

			Column(
				modifier = Modifier
					.padding(bottom = 10.dp)
			) {
				if (!hideSongTitle) {
					Text(
						text = content.item.name.orEmpty(),
						style = TextStyle(
							color = Color.White,
							fontSize = 26.sp,
							shadow = overlayShadow,
						),
					)
				}

				if (!hideArtist) {
					Text(
						text = artistText,
						style = TextStyle(
							color = Color(0.8f, 0.8f, 0.8f),
							fontSize = 18.sp,
							shadow = overlayShadow,
						),
					)
				}

				Spacer(modifier = Modifier.height(10.dp))

				PlayerSeekbar(
					playbackManager = playbackManager,
					colors = SeekbarDefaults.colors(
						backgroundColor = Color.White.copy(alpha = 0.2f),
						progressColor = Color.White,
						bufferColor = Color.Transparent,
					),
					modifier = Modifier
						.fillMaxWidth()
						.height(4.dp)
						.shadow(2.dp, RoundedCornerShape(2.dp))
				)
			}
		}
	}
}
