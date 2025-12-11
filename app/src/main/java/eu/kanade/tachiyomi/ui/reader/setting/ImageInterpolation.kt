package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class ImageInterpolation(
    val stringRes: StringResource,
    val flagValue: Int,
) {
    DEFAULT(MR.strings.label_default, 0x00000000),
    LINEAR(MR.strings.interpolation_linear, 0x00000040),
    AREA(MR.strings.interpolation_area, 0x00000080),
    CUBIC(MR.strings.interpolation_cubic, 0x000000C0),
    LANCZOS3(MR.strings.interpolation_lanczos3, 0x00000100),
    ;

    companion object {
        const val MASK = 0x000001C0

        fun fromPreference(preference: Int?): ImageInterpolation =
            entries.find { it.flagValue == preference } ?: DEFAULT

        /**
         * Converts the flag value to the preference value used by ReaderPreferences.
         * Returns null for DEFAULT to indicate the global preference should be used.
         */
        fun toPreferenceValue(mode: ImageInterpolation): Int? = when (mode) {
            DEFAULT -> null
            LINEAR -> 1
            AREA -> 2
            CUBIC -> 3
            LANCZOS3 -> 4
        }

        /**
         * Converts the preference value to the enum.
         */
        fun fromPreferenceValue(value: Int): ImageInterpolation = when (value) {
            1 -> LINEAR
            2 -> AREA
            3 -> CUBIC
            4 -> LANCZOS3
            else -> DEFAULT
        }
    }
}
