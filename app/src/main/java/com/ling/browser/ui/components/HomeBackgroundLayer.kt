package com.ling.browser.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.ling.browser.data.prefs.HomeBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置主页的原生背景层。
 *
 * 移植自 OnyxBrowser 起始页的"背景层 + 前景层"分层思路，但落点不同：
 * Onyx 的起始页整页都是原生 Compose，前景（图标/搜索框/书签）是另一层；
 * 「翎」的主页是 WebView 渲染的 HTML，因此这里只提供**背景层**，画在 WebView
 * **之下**，并让主页 HTML 背景透明（见 [com.ling.browser.web.HomePage.TRANSPARENT_BG]）来透出它。
 *
 * 只服务于 [HomeBackground.CUSTOM]：按用户选择的 `content://` URI 解码图片铺满。
 * [HomeBackground.SOLID] 不画背景（调用方不会挂载本组件），主页 HTML 自绘主题色。
 *
 * 顶部遮罩：顶部最暗、到屏幕中线渐隐 —— 保证状态栏/地址栏文字在浅色背景
 * （亮照片）上也清晰可读，与 Onyx 起始页一致。
 */
@Composable
fun HomeBackgroundLayer(
    mode: HomeBackground,
    imageUri: String,
    /**
     * 是否真的需要绘制。
     *
     * 调用方**始终**调用本组件（而不是写成 `if (visible) { HomeBackgroundLayer(…) }`），
     * 不需要时传 false：条件式挂载会改变同一 Box 里后续子项的槽位，后面的
     * AndroidView 因此被重建，而重建后的 WebView 会带回一层默认白底，把背景层
     * 整个盖住 —— 表现为"改了背景模式要手动按一下主页按钮才生效"。
     */
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    // 不绘制时直接返回：连打底与遮罩都不画，否则纯色主页上会突兀地压一条黑边。
    if (!visible || mode == HomeBackground.SOLID) return
    val context = LocalContext.current
    // 解码放到 IO 线程：大图解码可能耗时几十毫秒，放主线程会造成切到主页时卡一下。
    // 用 (mode, imageUri) 作为 key：换了图片或模式会重新解码，其它重组不会重复解码。
    var image by remember(mode, imageUri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mode, imageUri) {
        image = withContext(Dispatchers.IO) { loadBackgroundImage(context, mode, imageUri) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 打底：图片尚未解码完或解码失败时，先给一块主题背景色，避免出现"一片黑"。
        // 纯色模式下本组件不会被挂载，这里的打底只服务于自定义图片这一档。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Crop：让图片铺满整屏、不走样。宁可裁掉边角，也不留主题色边框。
                contentScale = ContentScale.Crop,
            )
        }
        // 顶部遮罩（Onyx 起始页同款）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                ),
        )
    }
}

/**
 * 背景图解码的边长上限（像素）。
 *
 * 为什么不按原图尺寸解码：手机相册里的图片常见 2000~4000 px 宽，
 * 全尺寸 ARGB_8888 位图能到几十 MB，一次 OOM 就是崩溃。铺满一块手机屏
 * 用不到那么高的分辨率，降到 2048 后肉眼几乎无差别，内存却降一个量级。
 */
private const val MAX_BACKGROUND_DIM = 2048

/** 按当前模式取背景位图；任何失败都返回 null（由上层退回主题色打底）。 */
private fun loadBackgroundImage(
    context: Context,
    mode: HomeBackground,
    imageUri: String,
): ImageBitmap? = when (mode) {
    // 纯色模式不需要位图（本组件也不会被挂载，这里只是补齐 when 分支）
    HomeBackground.SOLID -> null
    HomeBackground.CUSTOM -> loadFromUri(context, imageUri)?.asImageBitmap()
}

/** 按 URI 解码用户选择的图片，先读尺寸算降采样率再真正解码。 */
private fun loadFromUri(context: Context, imageUri: String): Bitmap? {
    val trimmed = imageUri.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null

    // 第一遍只读尺寸（inJustDecodeBounds），不解码像素，代价极小。
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // 只读尺寸时 decodeStream 返回 null 属正常，真正的判据是尺寸有没有被填上。
    runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > MAX_BACKGROUND_DIM ||
        bounds.outHeight / sample > MAX_BACKGROUND_DIM
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()
}
