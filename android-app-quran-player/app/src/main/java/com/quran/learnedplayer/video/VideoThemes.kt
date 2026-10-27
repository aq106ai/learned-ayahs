package com.quran.learnedplayer.video

/** The kinds of picture [BackgroundScenes] can paint. None of them shows a person or an animal. */
enum class SceneKind {
    SKY, RAYS, WATER, RAIN, GARDEN, EMBERS, STORM, RUINS, NEBULA, MIST, STARS, SEA, DUNES,
    MOUNTAINS, FIELDS, ARCHES, LANTERN, GEOMETRIC, MANUSCRIPT,
}

/** Optional elements a scene can add. */
enum class Feature { SUN, CRESCENT, FULL_MOON, CLOUDS, RIVER, PALMS, TREES, RAIN, LIGHTNING, STARS, MIST, ROUGH }

/**
 * One background. [colors] are, by convention: top of the sky, the horizon or middle, the land or
 * main shape, and the light or accent. Each scene reads them in that order.
 */
class SceneSpec(val kind: SceneKind, val colors: IntArray, val features: Set<Feature>) {
    fun has(feature: Feature) = feature in features
}

/** A group of backgrounds for one subject. [title] is what the theme picker shows. */
class VideoTheme(val key: String, val title: String, val variants: List<SceneSpec>)

/**
 * The background themes, two or three backgrounds each. Their keys are the ones
 * `.setup/make_video_themes.py` writes into `video_themes.json`, which maps every ayah to a theme by
 * what its meaning talks about; VideoThemesTest keeps the two lists equal.
 *
 * Content norms: backgrounds are drawn, not photographs, and show only sky, land, water, light and
 * pattern, never a person or an animal, and videos carry no music, only the recitation.
 */
object VideoThemes {
    private fun c(argb: Long) = argb.toInt()

    private fun scene(kind: SceneKind, top: Long, middle: Long, land: Long, light: Long, vararg features: Feature) =
        SceneSpec(kind, intArrayOf(c(top), c(middle), c(land), c(light)), features.toSet())

    private fun theme(key: String, title: String, vararg variants: SceneSpec) = VideoTheme(key, title, variants.toList())

    val all: List<VideoTheme> = listOf(
        theme("mercy", "Mercy",
            scene(SceneKind.SKY, 0xFF2B3A67, 0xFFE8A87C, 0xFF3D2C4E, 0xFFFFE2B8, Feature.SUN, Feature.CLOUDS),
            scene(SceneKind.RAYS, 0xFF1F3B4D, 0xFF6FA3A8, 0xFF203A43, 0xFFFFF4D6),
            scene(SceneKind.WATER, 0xFF3A2E5C, 0xFFD9A5B3, 0xFF2D3E5E, 0xFFFFE9D6)),
        theme("forgiveness", "Forgiveness",
            scene(SceneKind.RAIN, 0xFF26323F, 0xFF5F7A8A, 0xFF1B2630, 0xFFDDE8EE),
            scene(SceneKind.SKY, 0xFF35577A, 0xFFB8D4E3, 0xFF2E4B5E, 0xFFFFF6E0, Feature.CLOUDS),
            scene(SceneKind.WATER, 0xFF1D3047, 0xFF7FA6BF, 0xFF16263A, 0xFFE3F1F8)),
        theme("paradise", "Gardens",
            scene(SceneKind.GARDEN, 0xFF1E4D5C, 0xFFA8D5BA, 0xFF1D5B3A, 0xFFFFF1C1, Feature.RIVER, Feature.TREES),
            scene(SceneKind.GARDEN, 0xFF2F5D50, 0xFFE9D8A6, 0xFF285E3D, 0xFFFFF8E1, Feature.PALMS, Feature.RIVER),
            scene(SceneKind.RAYS, 0xFF16423C, 0xFF6A9C78, 0xFF0F3A2E, 0xFFF4FFD8)),
        theme("rivers", "Rivers",
            scene(SceneKind.GARDEN, 0xFF23506B, 0xFFBFE3E0, 0xFF2D6A4F, 0xFFFFFFFF, Feature.RIVER),
            scene(SceneKind.WATER, 0xFF0F4C5C, 0xFF5FA8A0, 0xFF0B3640, 0xFFE0FFF8)),
        theme("provision", "Provision",
            scene(SceneKind.GARDEN, 0xFF4A6B3A, 0xFFF2D8A0, 0xFF3D5A2A, 0xFFFFF0C8, Feature.PALMS),
            scene(SceneKind.FIELDS, 0xFF5B7DA0, 0xFFF0D9A0, 0xFFC9A04A, 0xFFFFF5DA, Feature.SUN)),
        theme("hellfire", "Warning",
            scene(SceneKind.EMBERS, 0xFF120606, 0xFF3A0E08, 0xFF1A0705, 0xFFFF7A2E),
            scene(SceneKind.EMBERS, 0xFF0D0A0A, 0xFF2A1410, 0xFF120A08, 0xFFE0602A)),
        theme("punishment", "Peoples destroyed",
            scene(SceneKind.STORM, 0xFF141820, 0xFF3A3F4A, 0xFF101216, 0xFFC9D6F0, Feature.LIGHTNING),
            scene(SceneKind.RUINS, 0xFF2A1E1A, 0xFF8A5A3C, 0xFF1E1410, 0xFFF0B070)),
        theme("judgment", "The Last Day",
            scene(SceneKind.STORM, 0xFF0E1018, 0xFF3B2F3F, 0xFF0B0B10, 0xFFE8C9A0),
            scene(SceneKind.NEBULA, 0xFF07070F, 0xFF3A1F3A, 0xFF120A16, 0xFFF0D0A0, Feature.STARS),
            scene(SceneKind.MIST, 0xFF1A1C22, 0xFF4A4A52, 0xFF121318, 0xFFD8D2C4)),
        theme("resurrection", "Resurrection",
            scene(SceneKind.SKY, 0xFF1B2440, 0xFFE0B48A, 0xFF2A2420, 0xFFFFE8C0, Feature.SUN, Feature.MIST),
            scene(SceneKind.MIST, 0xFF2C3446, 0xFF8E8FA0, 0xFF22252F, 0xFFF4EEE0)),
        theme("death", "Return to Allah",
            scene(SceneKind.MIST, 0xFF20232B, 0xFF5B5E6A, 0xFF15171C, 0xFFD9D6CC),
            scene(SceneKind.SKY, 0xFF1C1F33, 0xFF8C5A6A, 0xFF16141E, 0xFFF2C9A0)),
        theme("night", "Night",
            scene(SceneKind.STARS, 0xFF050A1A, 0xFF16284A, 0xFF0A1020, 0xFFFFF6D8, Feature.CRESCENT),
            scene(SceneKind.STARS, 0xFF02040C, 0xFF0F1A33, 0xFF060A14, 0xFFE8EEFF),
            scene(SceneKind.DUNES, 0xFF060B1C, 0xFF1C2B4F, 0xFF151C30, 0xFFF4EBC8, Feature.STARS, Feature.CRESCENT)),
        theme("dawn", "Dawn",
            scene(SceneKind.SKY, 0xFF27325C, 0xFFF2A65A, 0xFF2B2235, 0xFFFFE6B0, Feature.SUN),
            scene(SceneKind.SEA, 0xFF3A3F6B, 0xFFF4B183, 0xFF2C3A5A, 0xFFFFE9C4, Feature.SUN),
            scene(SceneKind.MOUNTAINS, 0xFF34406E, 0xFFF0B27A, 0xFF2B2A44, 0xFFFFEAC0, Feature.SUN, Feature.MIST)),
        theme("sun", "The sun",
            scene(SceneKind.SKY, 0xFF2D7FC1, 0xFFBFE3F5, 0xFF8FB8D0, 0xFFFFF9E0, Feature.SUN, Feature.CLOUDS),
            scene(SceneKind.DUNES, 0xFF3A86C8, 0xFFF3D9A4, 0xFFD9A35B, 0xFFFFF8E0, Feature.SUN)),
        theme("moon", "The moon",
            scene(SceneKind.STARS, 0xFF060C20, 0xFF1A2C55, 0xFF0A1226, 0xFFFFF8E8, Feature.FULL_MOON),
            scene(SceneKind.SEA, 0xFF07122A, 0xFF1F3560, 0xFF0A1A35, 0xFFF4F1E0, Feature.FULL_MOON)),
        theme("stars", "Stars",
            scene(SceneKind.STARS, 0xFF02030A, 0xFF101A3A, 0xFF05070F, 0xFFFFFFFF),
            scene(SceneKind.NEBULA, 0xFF03040C, 0xFF1A2A5A, 0xFF070A18, 0xFFCFE0FF, Feature.STARS)),
        theme("cosmos", "The heavens",
            scene(SceneKind.NEBULA, 0xFF04030E, 0xFF2E2A6E, 0xFF0A0820, 0xFFB8C8FF, Feature.STARS),
            scene(SceneKind.NEBULA, 0xFF050208, 0xFF5A2A6A, 0xFF120818, 0xFFFFD0F0, Feature.STARS),
            scene(SceneKind.STARS, 0xFF01020A, 0xFF13224A, 0xFF03050E, 0xFFFFFFFF)),
        theme("mountains", "Mountains",
            scene(SceneKind.MOUNTAINS, 0xFF5B86B0, 0xFFD6E4EE, 0xFF34495E, 0xFFFFFFFF, Feature.MIST),
            scene(SceneKind.MOUNTAINS, 0xFF2A2F4F, 0xFFE8B48A, 0xFF22243A, 0xFFFFE2C0, Feature.SUN)),
        theme("earth", "The earth",
            scene(SceneKind.FIELDS, 0xFF6A9BC7, 0xFFDCE8D0, 0xFF6B8E4E, 0xFFFFFFF0, Feature.CLOUDS),
            scene(SceneKind.MOUNTAINS, 0xFF6F95B8, 0xFFE4E0CC, 0xFF6D6A4E, 0xFFFFFAE8)),
        theme("sea", "The sea",
            scene(SceneKind.SEA, 0xFF2E6E9E, 0xFFBFDCEB, 0xFF13456B, 0xFFFFFFFF, Feature.CLOUDS),
            scene(SceneKind.SEA, 0xFF14324F, 0xFF4F7FA0, 0xFF0B2238, 0xFFE0F0FF),
            scene(SceneKind.WATER, 0xFF0D3B5A, 0xFF3F86A8, 0xFF082A40, 0xFFDDF4FF)),
        theme("flood", "The flood",
            scene(SceneKind.SEA, 0xFF1A2128, 0xFF4A5560, 0xFF0F171E, 0xFFC8D4DC, Feature.ROUGH, Feature.RAIN),
            scene(SceneKind.STORM, 0xFF12161C, 0xFF384350, 0xFF0C1014, 0xFFB8C8D8, Feature.RAIN)),
        theme("rain", "Rain",
            scene(SceneKind.RAIN, 0xFF2C3A48, 0xFF7890A0, 0xFF1E2A35, 0xFFE8F2F8),
            scene(SceneKind.WATER, 0xFF223344, 0xFF6C8CA0, 0xFF18242F, 0xFFE0EEF6, Feature.RAIN)),
        theme("wind", "Wind and clouds",
            scene(SceneKind.SKY, 0xFF4A78A8, 0xFFD8E6F0, 0xFF5E7C94, 0xFFFFFFFF, Feature.CLOUDS),
            scene(SceneKind.MIST, 0xFF5A6A80, 0xFFB8C4D0, 0xFF4A5868, 0xFFF4F8FC)),
        theme("storm", "Thunder",
            scene(SceneKind.STORM, 0xFF151A24, 0xFF404858, 0xFF0E1218, 0xFFE0E8FF, Feature.LIGHTNING, Feature.RAIN),
            scene(SceneKind.STORM, 0xFF1A1622, 0xFF4A3F58, 0xFF110E16, 0xFFF0E0FF, Feature.LIGHTNING)),
        theme("growth", "Growth",
            scene(SceneKind.FIELDS, 0xFF6FA8C8, 0xFFE0F0D0, 0xFF4E8A3E, 0xFFFFFFF0, Feature.SUN),
            scene(SceneKind.GARDEN, 0xFF3F7A6A, 0xFFD6EBC4, 0xFF3C7A3A, 0xFFFFFDE8, Feature.TREES)),
        theme("harvest", "Harvest",
            scene(SceneKind.FIELDS, 0xFF5E86B0, 0xFFF6DFA8, 0xFFD2A24C, 0xFFFFF6DC, Feature.SUN),
            scene(SceneKind.FIELDS, 0xFF3C3A5E, 0xFFE8A86A, 0xFFB07A3A, 0xFFFFE2B8, Feature.SUN)),
        theme("desert", "The desert",
            scene(SceneKind.DUNES, 0xFF4A90C8, 0xFFF0D8A8, 0xFFD39B5A, 0xFFFFF6E0, Feature.SUN),
            scene(SceneKind.DUNES, 0xFF3A2E50, 0xFFE89A6A, 0xFFB26A44, 0xFFFFD8B0, Feature.SUN),
            scene(SceneKind.DUNES, 0xFF070C1E, 0xFF1E2C52, 0xFF2A2A3A, 0xFFF6F0D8, Feature.STARS, Feature.CRESCENT)),
        theme("ruins", "Nations before",
            scene(SceneKind.RUINS, 0xFF4A6A90, 0xFFE8C898, 0xFFA87A50, 0xFFFFF0D0),
            scene(SceneKind.RUINS, 0xFF0A1024, 0xFF2A3050, 0xFF2A2430, 0xFFE8E0C8, Feature.STARS)),
        theme("prayer", "Prayer",
            scene(SceneKind.ARCHES, 0xFF1B2A3A, 0xFF8A6A40, 0xFF0F1820, 0xFFFFE0A0),
            scene(SceneKind.ARCHES, 0xFF0E2420, 0xFF3E7A60, 0xFF08140F, 0xFFE8FFE0),
            scene(SceneKind.LANTERN, 0xFF141A2A, 0xFF4A3A2A, 0xFF0C1018, 0xFFFFD890)),
        theme("light", "Light and guidance",
            scene(SceneKind.RAYS, 0xFF16263A, 0xFF5A7A9A, 0xFF101C2A, 0xFFFFF4D0),
            scene(SceneKind.RAYS, 0xFF2A2A40, 0xFFB09070, 0xFF1A1A28, 0xFFFFF0C8),
            scene(SceneKind.LANTERN, 0xFF0F1622, 0xFF3A3A50, 0xFF0A0F18, 0xFFFFF0B8)),
        theme("light_verse", "Light upon light",
            scene(SceneKind.LANTERN, 0xFF0A0F1C, 0xFF2A2A44, 0xFF070A12, 0xFFFFE8A0),
            scene(SceneKind.LANTERN, 0xFF0C1A18, 0xFF2A4A40, 0xFF08120F, 0xFFF8FFD0)),
        theme("oneness", "Oneness",
            scene(SceneKind.GEOMETRIC, 0xFF0B1E17, 0xFF15392A, 0xFF0A1A12, 0xFFD8B866),
            scene(SceneKind.GEOMETRIC, 0xFF0C1626, 0xFF1C3354, 0xFF081020, 0xFFC8D8F0),
            scene(SceneKind.GEOMETRIC, 0xFF1A1410, 0xFF3A2A1C, 0xFF120E0A, 0xFFE8C48A)),
        theme("majesty", "Majesty",
            scene(SceneKind.MOUNTAINS, 0xFF1A2440, 0xFF6A7AA0, 0xFF141A2E, 0xFFF0F4FF, Feature.STARS),
            scene(SceneKind.NEBULA, 0xFF05060F, 0xFF243060, 0xFF080A18, 0xFFE0E8FF, Feature.STARS)),
        theme("throne", "Dominion",
            scene(SceneKind.GEOMETRIC, 0xFF0A0F1F, 0xFF1A2A4A, 0xFF060A14, 0xFFE8C870),
            scene(SceneKind.STARS, 0xFF020410, 0xFF141E40, 0xFF04060E, 0xFFFFF0C8)),
        theme("knowledge", "Knowledge",
            scene(SceneKind.MANUSCRIPT, 0xFF2A2018, 0xFFE8D8B8, 0xFF8A6A40, 0xFF2A4A3A),
            scene(SceneKind.MANUSCRIPT, 0xFF101A16, 0xFF1E3028, 0xFF0A120E, 0xFFD8B866)),
        theme("revelation", "Revelation",
            scene(SceneKind.MANUSCRIPT, 0xFF0F1B16, 0xFF1A3A2C, 0xFF0A140F, 0xFFD8BC70),
            scene(SceneKind.RAYS, 0xFF0F1A28, 0xFF3A5A7A, 0xFF0A121E, 0xFFFFF4D8),
            scene(SceneKind.GEOMETRIC, 0xFF101810, 0xFF243A24, 0xFF0A100A, 0xFFE0C880)),
        theme("prophets", "The prophets",
            scene(SceneKind.DUNES, 0xFF5A8AB8, 0xFFEED8B0, 0xFFC89A62, 0xFFFFF6E4, Feature.SUN),
            scene(SceneKind.MOUNTAINS, 0xFF2E3A5C, 0xFFE0A87A, 0xFF2A2638, 0xFFFFE4C4, Feature.SUN),
            scene(SceneKind.RUINS, 0xFF3E5A80, 0xFFE4C8A0, 0xFF9A7454, 0xFFFFF2DC)),
        theme("moses", "Musa and Pharaoh",
            scene(SceneKind.SEA, 0xFF1E3A5A, 0xFF6A9AB8, 0xFF0C2A44, 0xFFF0FAFF, Feature.ROUGH),
            scene(SceneKind.MOUNTAINS, 0xFF4A3A5A, 0xFFE8A070, 0xFF3A2A30, 0xFFFFDCB0, Feature.SUN),
            scene(SceneKind.DUNES, 0xFF4A80B0, 0xFFF0D4A0, 0xFFC88A50, 0xFFFFF4DC)),
        theme("hajj", "Pilgrimage",
            scene(SceneKind.GEOMETRIC, 0xFF0A0A0A, 0xFF1E1E1E, 0xFF050505, 0xFFD8B050),
            scene(SceneKind.ARCHES, 0xFF2A2420, 0xFFB09068, 0xFF16120E, 0xFFFFF0D0),
            scene(SceneKind.MOUNTAINS, 0xFF5A86B4, 0xFFF0DCB0, 0xFF8A6A4A, 0xFFFFF8E8, Feature.SUN)),
        theme("fasting", "Fasting",
            scene(SceneKind.STARS, 0xFF0A0E24, 0xFF2A2A5A, 0xFF0E0E20, 0xFFFFF0C0, Feature.CRESCENT),
            scene(SceneKind.LANTERN, 0xFF101428, 0xFF3A2A4A, 0xFF0A0C18, 0xFFFFD890)),
        theme("charity", "Charity",
            scene(SceneKind.FIELDS, 0xFF6A90B8, 0xFFF4DCA4, 0xFFC89A44, 0xFFFFF8E0, Feature.SUN),
            scene(SceneKind.RAYS, 0xFF2A2418, 0xFFB09058, 0xFF1A160E, 0xFFFFEEC0)),
        theme("patience", "Patience",
            scene(SceneKind.WATER, 0xFF2A3E58, 0xFFA8B8C8, 0xFF1E2E42, 0xFFF4F8FC),
            scene(SceneKind.MOUNTAINS, 0xFF5A6E88, 0xFFD0D8E0, 0xFF3A4658, 0xFFFFFFFF, Feature.MIST)),
        theme("gratitude", "Gratitude",
            scene(SceneKind.SKY, 0xFF3A6EA8, 0xFFF4D49A, 0xFF5A7A4A, 0xFFFFF4D8, Feature.SUN, Feature.CLOUDS),
            scene(SceneKind.FIELDS, 0xFF5E8EBE, 0xFFEEE0B0, 0xFF9AA84A, 0xFFFFFAE4)),
        theme("family", "Family",
            scene(SceneKind.LANTERN, 0xFF1E1812, 0xFF5A4028, 0xFF120E0A, 0xFFFFD8A0),
            scene(SceneKind.SKY, 0xFF3A3058, 0xFFF0A878, 0xFF3A2A3A, 0xFFFFE4C4, Feature.SUN)),
        theme("justice", "Justice",
            scene(SceneKind.GEOMETRIC, 0xFF101820, 0xFF24344A, 0xFF0A1018, 0xFFD0D8E0),
            scene(SceneKind.MANUSCRIPT, 0xFF22201C, 0xFFE0D4BC, 0xFF6A5A40, 0xFF2A3A4A)),
        theme("striving", "Striving",
            scene(SceneKind.DUNES, 0xFF8A6A58, 0xFFE0B48A, 0xFFA8744A, 0xFFFFE0B8, Feature.SUN),
            scene(SceneKind.STORM, 0xFF3A2A22, 0xFF8A6A50, 0xFF2A1E18, 0xFFFFD8A8)),
        theme("disbelief", "Veils",
            scene(SceneKind.MIST, 0xFF1C1E24, 0xFF4E525C, 0xFF14161A, 0xFFC8C8CC),
            scene(SceneKind.STORM, 0xFF181A20, 0xFF3A3E48, 0xFF101216, 0xFFB8C0CC)),
        theme("signs", "Signs in creation",
            scene(SceneKind.NEBULA, 0xFF040810, 0xFF1A3A5A, 0xFF060C16, 0xFFD0F0FF, Feature.STARS),
            scene(SceneKind.SEA, 0xFF2A5E8E, 0xFFE8C8A0, 0xFF143C5E, 0xFFFFF4E0, Feature.SUN),
            scene(SceneKind.MOUNTAINS, 0xFF3A5A88, 0xFFE8D0B0, 0xFF2E3A50, 0xFFFFF8EC, Feature.MIST)),
        theme("supplication", "Supplication",
            scene(SceneKind.STARS, 0xFF060A18, 0xFF1E2A50, 0xFF0A0E1C, 0xFFFFF4D8, Feature.CRESCENT),
            scene(SceneKind.LANTERN, 0xFF0E121E, 0xFF2E3448, 0xFF0A0C14, 0xFFFFE8B0),
            scene(SceneKind.RAYS, 0xFF141E30, 0xFF4A5E7A, 0xFF0E1622, 0xFFFFF6DC)),
        theme("remembrance", "Remembrance",
            scene(SceneKind.WATER, 0xFF102A2A, 0xFF4A8A84, 0xFF0A1E1E, 0xFFE8FFF8),
            scene(SceneKind.GEOMETRIC, 0xFF0E1E1A, 0xFF1E3A32, 0xFF0A1612, 0xFFB8E0C8)),
        theme("trust", "Trust in Allah",
            scene(SceneKind.MOUNTAINS, 0xFF3A6A9A, 0xFFE8EEF4, 0xFF2A3A4E, 0xFFFFFFFF, Feature.SUN),
            scene(SceneKind.SKY, 0xFF2A5A90, 0xFFC8E0F0, 0xFF3A5A78, 0xFFFFFFFF, Feature.CLOUDS)),
        theme("glad_tidings", "Glad tidings",
            scene(SceneKind.SKY, 0xFF3A5A9A, 0xFFF8C878, 0xFF3A3A50, 0xFFFFF0C0, Feature.SUN, Feature.CLOUDS),
            scene(SceneKind.RAYS, 0xFF2A3050, 0xFFE0B070, 0xFF1A1E30, 0xFFFFF4D0)),
        theme("creation_of_man", "Creation",
            scene(SceneKind.MOUNTAINS, 0xFF5A6A88, 0xFFE0C8A8, 0xFF8A6448, 0xFFFFF4E4),
            scene(SceneKind.NEBULA, 0xFF0A0606, 0xFF4A2A1E, 0xFF140C0A, 0xFFFFE0C0, Feature.STARS)),
        theme("unseen", "The unseen",
            scene(SceneKind.RAYS, 0xFF1A2232, 0xFF8A98B0, 0xFF121822, 0xFFFFFFFF),
            scene(SceneKind.MIST, 0xFF2A3040, 0xFFA8B0C0, 0xFF1E2230, 0xFFFFFFFF),
            scene(SceneKind.NEBULA, 0xFF06080E, 0xFF3A4A6A, 0xFF0A0E18, 0xFFF0F4FF, Feature.STARS)),
    )

    private val byKey = all.associateBy { it.key }

    /** Every ayah falls back to this when the map has nothing for it. */
    const val FALLBACK = "oneness"

    fun theme(key: String?): VideoTheme = byKey[key] ?: byKey.getValue(FALLBACK)

    fun variant(key: String?, index: Int): SceneSpec = theme(key).variants.let { it[Math.floorMod(index, it.size)] }
}
