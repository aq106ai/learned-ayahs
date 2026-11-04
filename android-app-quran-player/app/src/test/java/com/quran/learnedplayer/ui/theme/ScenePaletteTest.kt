package com.quran.learnedplayer.ui.theme

import androidx.compose.ui.graphics.Color
import com.quran.learnedplayer.video.SceneKind
import com.quran.learnedplayer.video.SceneSpec
import com.quran.learnedplayer.video.VideoThemes
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePaletteTest {

    @Test
    fun every_scene_keeps_text_readable_on_its_panels() {
        val scenes = VideoThemes.all.flatMap { it.variants } + photoChrome()
        for (scene in scenes) {
            val palette = paletteFor(scene)
            assertTrue(scene.kind.name, contrastRatio(palette.textPrimary, palette.panel) >= 4.5f)
            assertTrue(scene.kind.name, contrastRatio(palette.textPrimary, palette.bg) >= 4.5f)
            assertTrue(scene.kind.name, contrastRatio(palette.accent, palette.panel) >= 4.5f)
            assertTrue(scene.kind.name, !palette.isLight)
        }
    }

    /** The flat colours [com.quran.learnedplayer.video.NaturePhotos.scene] gives every photo. */
    private fun photoChrome() = SceneSpec(
        SceneKind.PHOTO,
        intArrayOf(0xFF101418.toInt(), 0xFF101418.toInt(), 0xFF101418.toInt(), -1),
        emptySet(),
    )
}
