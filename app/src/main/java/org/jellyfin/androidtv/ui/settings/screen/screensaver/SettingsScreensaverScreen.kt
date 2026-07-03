package org.jellyfin.androidtv.ui.settings.screen.screensaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsScreensaverScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings_title).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_screensaver)) },
			)
		}

		item {
			var screensaverInAppEnabled by rememberPreference(userPreferences, UserPreferences.screensaverInAppEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_inapp_enabled)) },
				trailingContent = { Checkbox(checked = screensaverInAppEnabled) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_inapp_enabled_description)) },
				onClick = { screensaverInAppEnabled = !screensaverInAppEnabled }
			)
		}

		item {
			var screensaverInAppTimeout by rememberPreference(userPreferences, UserPreferences.screensaverInAppTimeout)
			val caption = getScreensaverTimeoutOptions()
				.firstOrNull { (duration) -> duration.inWholeMilliseconds == screensaverInAppTimeout }
				?.second.orEmpty()

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_inapp_timeout)) },
				captionContent = { Text(caption) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_TIMEOUT) }
			)
		}

		item {
			var screensaverHideNowPlayingCover by rememberPreference(userPreferences, UserPreferences.screensaverHideNowPlayingCover)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_hide_nowplaying_cover)) },
				trailingContent = { Checkbox(checked = screensaverHideNowPlayingCover) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_hide_nowplaying_cover_description)) },
				onClick = { screensaverHideNowPlayingCover = !screensaverHideNowPlayingCover }
			)
		}

		item {
			var screensaverShowLyrics by rememberPreference(userPreferences, UserPreferences.screensaverShowLyrics)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_show_lyrics)) },
				trailingContent = { Checkbox(checked = screensaverShowLyrics) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_show_lyrics_description)) },
				onClick = { screensaverShowLyrics = !screensaverShowLyrics }
			)
		}

		item {
			var screensaverAudioVisualizer by rememberPreference(userPreferences, UserPreferences.screensaverAudioVisualizer)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_audio_visualizer)) },
				trailingContent = { Checkbox(checked = screensaverAudioVisualizer) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_audio_visualizer_description)) },
				onClick = { screensaverAudioVisualizer = !screensaverAudioVisualizer }
			)
		}

		item {
			var screensaverVisualizerRadial by rememberPreference(userPreferences, UserPreferences.screensaverVisualizerRadial)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_visualizer_radial)) },
				trailingContent = { Checkbox(checked = screensaverVisualizerRadial) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_visualizer_radial_description)) },
				onClick = { screensaverVisualizerRadial = !screensaverVisualizerRadial }
			)
		}

		item {
			var screensaverVisualizerCenterOut by rememberPreference(userPreferences, UserPreferences.screensaverVisualizerCenterOut)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_visualizer_centerout)) },
				trailingContent = { Checkbox(checked = screensaverVisualizerCenterOut) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_visualizer_centerout_description)) },
				onClick = { screensaverVisualizerCenterOut = !screensaverVisualizerCenterOut }
			)
		}

		item {
			var screensaverVisualizerCoverColor by rememberPreference(userPreferences, UserPreferences.screensaverVisualizerCoverColor)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_visualizer_covercolor)) },
				trailingContent = { Checkbox(checked = screensaverVisualizerCoverColor) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_visualizer_covercolor_description)) },
				onClick = { screensaverVisualizerCoverColor = !screensaverVisualizerCoverColor }
			)
		}

		item {
			var screensaverCenteredLayout by rememberPreference(userPreferences, UserPreferences.screensaverCenteredLayout)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_centered_layout)) },
				trailingContent = { Checkbox(checked = screensaverCenteredLayout) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_centered_layout_description)) },
				onClick = { screensaverCenteredLayout = !screensaverCenteredLayout }
			)
		}

		item {
			var screensaverHideSongTitle by rememberPreference(userPreferences, UserPreferences.screensaverHideSongTitle)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_hide_song_title)) },
				trailingContent = { Checkbox(checked = screensaverHideSongTitle) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_hide_song_title_description)) },
				onClick = { screensaverHideSongTitle = !screensaverHideSongTitle }
			)
		}

		item {
			var screensaverHideArtist by rememberPreference(userPreferences, UserPreferences.screensaverHideArtist)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_hide_artist)) },
				trailingContent = { Checkbox(checked = screensaverHideArtist) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_hide_artist_description)) },
				onClick = { screensaverHideArtist = !screensaverHideArtist }
			)
		}

		item {
			var screensaverHideClock by rememberPreference(userPreferences, UserPreferences.screensaverHideClock)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_hide_clock)) },
				trailingContent = { Checkbox(checked = screensaverHideClock) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_hide_clock_description)) },
				onClick = { screensaverHideClock = !screensaverHideClock }
			)
		}

		item {
			var screensaverAgeRatingRequired by rememberPreference(userPreferences, UserPreferences.screensaverAgeRatingRequired)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_ageratingrequired_title)) },
				trailingContent = { Checkbox(checked = screensaverAgeRatingRequired) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_ageratingrequired_enabled)) },
				onClick = { screensaverAgeRatingRequired = !screensaverAgeRatingRequired }
			)
		}

		item {
			var screensaverAgeRatingMax by rememberPreference(userPreferences, UserPreferences.screensaverAgeRatingMax)
			val caption = getScreensaverAgeRatingOptions()
				.firstOrNull { (ageRating) -> ageRating == screensaverAgeRatingMax }
				?.second.orEmpty()

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_ageratingmax)) },
				captionContent = { Text(caption) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_AGE_RATING) }
			)
		}
	}
}
