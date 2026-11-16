package dev.bucket.launcherslogan

import android.content.Context
import android.os.Build
import androidx.emoji2.text.EmojiCompatInitializer
import androidx.startup.Initializer

/** Android 12+ updates its platform emoji font; older releases retain EmojiCompat. */
class PlatformEmojiInitializer : Initializer<Boolean> {
    override fun create(context: Context) = true

    override fun dependencies(): List<Class<out Initializer<*>>> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            listOf(EmojiCompatInitializer::class.java)
        } else {
            emptyList()
        }
}
