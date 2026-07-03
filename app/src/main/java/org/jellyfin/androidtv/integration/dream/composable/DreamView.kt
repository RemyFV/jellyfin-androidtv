package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jellyfin.androidtv.integration.dream.model.DreamContent
import org.jellyfin.androidtv.preference.UserPreferences
import org.koin.compose.koinInject

@Composable
fun DreamView(
	content: DreamContent,
	showClock: Boolean,
) = Box(
	modifier = Modifier
		.fillMaxSize()
) {
	AnimatedContent(
		targetState = content,
		transitionSpec = {
			fadeIn(tween(durationMillis = 1_000)) togetherWith fadeOut(snap(delayMillis = 1_000))
		},
		label = "DreamContentTransition"
	) { content ->
		when (content) {
			DreamContent.Logo -> DreamContentLogo()
			is DreamContent.LibraryShowcase -> DreamContentLibraryShowcase(content)
			is DreamContent.NowPlaying -> DreamContentNowPlaying(content)
		}
	}

	// Header overlay. For now-playing, the centered layout draws its own centered clock and the
	// clock-hide option removes it entirely, so suppress the default top-right one in both cases.
	val preferences = koinInject<UserPreferences>()
	val suppressHeaderClock = content is DreamContent.NowPlaying && (
		preferences[UserPreferences.screensaverCenteredLayout] ||
			preferences[UserPreferences.screensaverHideClock]
		)

	DreamHeader(
		showClock = showClock && !suppressHeaderClock,
	)
}
