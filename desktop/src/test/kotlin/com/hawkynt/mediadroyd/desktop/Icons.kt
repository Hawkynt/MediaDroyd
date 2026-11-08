package com.hawkynt.mediadroyd.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import com.hawkynt.mediadroyd.ui.theme.MediaDroydColors
import org.jetbrains.skia.EncodedImageFormat
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The desktop icons drawn from the Android launcher icon's own layers (app/src/main/res/drawable): a 256-pixel PNG
 * for Linux and an ICO holding it for Windows, written to desktop/icons — and the store art beside the store texts in
 * fastlane/metadata/android/en-US/images (Google Play and F-Droid read it there): the 512-pixel icon (a full square,
 * the stores round it themselves), the 1024 × 500 feature graphic, and the README screenshots as the phone
 * screenshots. `./gradlew :desktop:icons` after the launcher icon or the screenshots change.
 */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".")
    fun layer(name: String) = File(root, "app/src/main/res/drawable/$name.xml").inputStream()
        .use { loadXmlImageVector(InputSource(it), Density(1f)) }
    val background = layer("ic_launcher_background")
    val foreground = layer("ic_launcher_foreground")
    val size = 256
    val png = ImageComposeScene(size, size).use { scene ->
        scene.setContent {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(percent = 22))) {
                Image(rememberVectorPainter(background), null, Modifier.fillMaxSize())
                Image(rememberVectorPainter(foreground), null, Modifier.fillMaxSize())
            }
        }
        scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
    val out = File(root, "desktop/icons").apply { mkdirs() }
    File(out, "mediadroyd.png").writeBytes(png)
    // An ICO of one 256-pixel PNG entry (width and height 0 mean 256), as Windows reads since Vista.
    val ico = ByteArrayOutputStream()
    DataOutputStream(ico).apply {
        fun le16(v: Int) { write(v and 0xFF); write(v shr 8 and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16(v ushr 16) }
        le16(0); le16(1); le16(1)
        write(0); write(0); write(0); write(0); le16(1); le16(32); le32(png.size); le32(6 + 16)
        write(png)
    }
    File(out, "mediadroyd.ico").writeBytes(ico.toByteArray())
    println("wrote ${out.path}")
    storeArt(root, background, foreground)
}

// The store's icon, feature graphic and phone screenshots (see main).
private fun storeArt(root: File, background: ImageVector, foreground: ImageVector) {
    val images = File(root, "fastlane/metadata/android/en-US/images").apply { mkdirs() }
    File(images, "icon.png").writeBytes(
        render(STORE_ICON, STORE_ICON) {
            Box(Modifier.fillMaxSize()) {
                Image(rememberVectorPainter(background), null, Modifier.fillMaxSize())
                Image(rememberVectorPainter(foreground), null, Modifier.fillMaxSize())
            }
        },
    )
    File(images, "featureGraphic.png").writeBytes(
        render(FEATURE_WIDTH, FEATURE_HEIGHT) {
            Row(
                Modifier.fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF2A2F5C), Color(0xFF0B0C1A))))
                    .padding(horizontal = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(300.dp).clip(RoundedCornerShape(percent = 22))) {
                    Image(rememberVectorPainter(background), null, Modifier.fillMaxSize())
                    Image(rememberVectorPainter(foreground), null, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(48.dp))
                Column {
                    Text("MediaDroyd", color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Your film library,\nstraight from the folders",
                        color = MediaDroydColors.Amber,
                        fontSize = 40.sp,
                        lineHeight = 48.sp,
                    )
                }
            }
        },
    )
    val shots = File(images, "phoneScreenshots").apply { mkdirs() }
    SCREENSHOTS.forEachIndexed { i, name ->
        File(root, "screenshots/$name.png").copyTo(File(shots, "${i + 1}_$name.png"), overwrite = true)
    }
    println("wrote ${images.path}")
}

private fun render(width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit): ByteArray =
    ImageComposeScene(width, height).use { scene ->
        scene.setContent(content)
        scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

// The stores' sizes: Google Play's icon and feature graphic.
private const val STORE_ICON = 512
private const val FEATURE_WIDTH = 1024
private const val FEATURE_HEIGHT = 500

// The README screenshots shown in the stores, in this order.
private val SCREENSHOTS = listOf("main", "detail", "player", "filters", "files")
