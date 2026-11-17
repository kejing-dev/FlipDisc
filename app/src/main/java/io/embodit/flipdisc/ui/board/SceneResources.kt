package io.embodit.flipdisc.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.flipdisc.domain.BoardGeometry
import io.embodit.flipdisc.ui.theme.FlipColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The shared meshes / materials / shapes (design budget: <= 8 meshes, <= 8 materials). Creating a resource waits on the
 * renderer (~1 s round trip on the emulator), so everything is created concurrently on Dispatchers.IO (Xiangqi
 * BoardScene.createResources) and kept global until [close].
 *
 * Geometry note (B12 / ui_gates "geometry"): the design package's asset list makes the board, the discs and the troughs
 * procedural on purpose (64 identical discs split exactly at half thickness, a grid aligned to the click pads; gate B
 * question 1); the gate:allow lines say so per call.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var frame: MeshResource; private set
    lateinit var felt: MeshResource; private set
    lateinit var tile: MeshResource; private set
    lateinit var discHalf: MeshResource; private set
    lateinit var legalRing: MeshResource; private set
    lateinit var snapRing: MeshResource; private set
    lateinit var markPlane: MeshResource; private set
    lateinit var dot: MeshResource; private set
    lateinit var trough: MeshResource; private set

    lateinit var walnut: Material; private set
    lateinit var walnutTrough: Material; private set
    lateinit var feltMat: Material; private set
    lateinit var tileMat: Material; private set
    lateinit var black: Material; private set
    lateinit var ivory: Material; private set
    lateinit var legalMat: Material; private set
    lateinit var snapMat: Material; private set
    lateinit var hintSure: Material; private set
    lateinit var hintUnsure: Material; private set
    lateinit var hintLost: Material; private set
    lateinit var illegalMat: Material; private set
    lateinit var dotMat: Material; private set

    lateinit var padShape: ShapeResource; private set
    lateinit var discShape: ShapeResource; private set
    lateinit var troughShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        /** Hint / frame marks are drawn on a 5 cm textured plane (no ring of boxes, B12 ring-from-boxes). */
        const val MARK_SIZE = 0.05f

        private fun pbr(color: Color, roughness: Float, glow: Float = 0f): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                setRoughness(roughness)
                setMetallic(0f)
                if (glow > 0f) setEmissiveColor(Color4(color.red * glow, color.green * glow, color.blue * glow, 1f))
            }

        private fun unlit(color: Color): UnlitMaterial =
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        private fun maskedUnlit(tex: TextureResource): UnlitMaterial =
            UnlitMaterial.create(BlendingMode.MASKED).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(tex)
                setOpacityThreshold(0.5f)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        /** A ring (solid / [dashes] segments) or a square frame on a transparent 256² bitmap, for the textured mark plane. */
        fun markBitmap(color: Color, bandM: Float, dashes: Int = 0, square: Boolean = false, outerM: Float = 0.021f): Bitmap {
            val n = 256
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val px = n / MARK_SIZE
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb(); style = Paint.Style.STROKE; strokeWidth = bandM * px }
            val r = (outerM - bandM / 2) * px
            val cx = n / 2f
            when {
                square -> c.drawRect(cx - r, cx - r, cx + r, cx + r, p)
                dashes > 0 -> {
                    val seg = 360f / dashes
                    for (i in 0 until dashes) c.drawArc(cx - r, cx - r, cx + r, cx + r, i * seg, seg * 0.6f, false, p)
                }
                else -> c.drawCircle(cx, cx, r, p)
            }
            return bmp
        }

        /** Builds everything concurrently off the main thread. */
        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            val feltTex = async(io) { TextureResource.create(BoardTextures.felt(2048)).keep() }
            val walnutTex = async(io) { TextureResource.create(BoardTextures.walnut(FlipColors.Frame.toArgb())).keep() }
            val troughTex = async(io) { TextureResource.create(BoardTextures.walnut(FlipColors.Trough.toArgb())).keep() }
            val sureTex = async(io) { TextureResource.create(markBitmap(FlipColors.HintRing, 0.004f)).keep() }
            val unsureTex = async(io) { TextureResource.create(markBitmap(FlipColors.HintRing, 0.004f, dashes = 8)).keep() }
            val lostTex = async(io) { TextureResource.create(markBitmap(FlipColors.LostRing, 0.002f)).keep() }
            val frameTex = async(io) { TextureResource.create(markBitmap(FlipColors.IllegalFrame, 0.004f, square = true, outerM = 0.024f)).keep() }
            val g = BoardGeometry
            listOf(
                // gate:allow geometry design-package assets: the walnut frame is a procedural slab (grid must align to the pads mm-exact)
                async(io) { r.frame = MeshResource.createBox(Vector3(g.OUTER, g.THICK, g.OUTER), cornerRadius = 0.004f).keep() },
                async(io) { r.felt = MeshResource.createPlane(g.FELT, g.FELT, 0f).keep() },
                // gate:allow geometry visible 4 x 4 cm square tile on every square: the drop-point marker the system hover draws on (board-input 通用模式)
                async(io) { r.tile = MeshResource.createBox(Vector3(g.TILE, g.TILE_H, g.TILE), cornerRadius = 0.003f).keep() },
                // gate:allow geometry disc halves: 64 identical procedural two-colour discs split at half thickness (design assets.棋子)
                async(io) { r.discHalf = MeshResource.createCylinder(radius = g.DISC_R, height = g.DISC_H / 2).keep() },
                async(io) { r.legalRing = MeshResource.createTorus(outerRingRadius = 0.019f, innerRingRadius = 0.016f).keep() },
                async(io) { r.snapRing = MeshResource.createTorus(outerRingRadius = 0.021f, innerRingRadius = 0.017f).keep() },
                async(io) { r.markPlane = MeshResource.createPlane(MARK_SIZE, MARK_SIZE, 0f).keep() },
                // gate:allow geometry last-move dot: an abstract 1.2 cm marker on the disc top (design palette.boardMarks)
                async(io) { r.dot = MeshResource.createCylinder(radius = 0.006f, height = 0.0012f).keep() },
                // gate:allow geometry walnut troughs: procedural straight-edged boxes (design assets.棋盘 + 边框 + 棋槽)
                async(io) { r.trough = MeshResource.createBox(Vector3(g.TROUGH_W, g.TROUGH_H, g.TROUGH_L), cornerRadius = 0.004f).keep() },
                async(io) { r.padShape = ShapeResource.createBox(Vector3(g.PAD, 0.006f, g.PAD)).keep() },
                async(io) { r.discShape = ShapeResource.createBox(Vector3(2 * g.DISC_R, g.DISC_H, 2 * g.DISC_R)).keep() },
                async(io) { r.troughShape = ShapeResource.createBox(Vector3(g.TROUGH_W, g.SLOT_Y + g.DISC_R, g.TROUGH_L)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
                async(io) { r.tileMat = pbr(FlipColors.Tile, roughness = 0.75f).keep() },
                async(io) { r.black = pbr(FlipColors.DiscBlack, roughness = 0.5f, glow = 0.02f).keep() },
                async(io) { r.ivory = pbr(FlipColors.DiscWhite, roughness = 0.5f).keep() },
                async(io) { r.legalMat = unlit(FlipColors.LegalRing).keep() },
                async(io) { r.snapMat = unlit(FlipColors.SnapRing).keep() },
                async(io) { r.dotMat = unlit(FlipColors.LastDot).keep() },
            ).awaitAll()
            r.feltMat = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.8f); setMetallic(0f); setBaseColorTexture(feltTex.await())
            }.keep()
            r.walnut = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.6f); setMetallic(0f); setBaseColorTexture(walnutTex.await())
            }.keep()
            r.walnutTrough = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.6f); setMetallic(0f); setBaseColorTexture(troughTex.await())
            }.keep()
            r.hintSure = maskedUnlit(sureTex.await()).keep()
            r.hintUnsure = maskedUnlit(unsureTex.await()).keep()
            r.hintLost = maskedUnlit(lostTex.await()).keep()
            r.illegalMat = maskedUnlit(frameTex.await()).keep()
            r
        }

    }
}
