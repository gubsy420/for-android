package chat.stoat.c2dm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import chat.stoat.api.routes.push.subscribePush
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import kotlin.math.abs

private val LETTER_ICON_COLORS = intArrayOf(
    0xFF1565C0.toInt(),
    0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(),
    0xFFC62828.toInt(),
    0xFF00838F.toInt(),
    0xFFE65100.toInt(),
    0xFF4527A0.toInt(),
    0xFF283593.toInt(),
)

// Fork change (UnifiedPush support): internal so PushMessageRenderer can reuse it
internal fun generateLetterBitmap(name: String, sizePx: Int = 256): Bitmap {
    val letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val color = LETTER_ICON_COLORS[abs(name.hashCode()) % LETTER_ICON_COLORS.size]

    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = color
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    paint.color = Color.WHITE
    paint.textSize = sizePx * 0.45f
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    val bounds = Rect()
    paint.getTextBounds(letter, 0, 1, bounds)
    canvas.drawText(letter, sizePx / 2f, sizePx / 2f + bounds.height() / 2f - bounds.bottom, paint)

    return bitmap
}

object NotificationID {
    const val NEW_MESSAGE = 0
}

class HandlerService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        runBlocking {
            subscribePush(auth = token)
        }
    }

    override fun onMessageReceived(fcmMessage: RemoteMessage) {
        val data = fcmMessage.data

        val type = data["type"]
        if (type != "push.message") {
            logcat(LogPriority.ERROR) { "Unknown message type: $type, abort" }
            return
        }

        val authorId = data["author"] ?: run {
            logcat(LogPriority.ERROR) { "No author in message, abort" }
            return
        }

        val body = data["body"] ?: run {
            logcat(LogPriority.ERROR) { "No body in message, abort" }
            return
        }

        val image = data["image"] ?: run {
            logcat(LogPriority.WARN) { "No image in message, abort" }
            return
        }

        val authorName = data["author_name"] ?: run {
            logcat(LogPriority.ERROR) { "No author name in message, abort" }
            return
        }

        val channelId = data["channel"] ?: run {
            logcat(LogPriority.ERROR) { "No channel in message, abort" }
            return
        }

        val messageId = data["message"] ?: run {
            logcat(LogPriority.ERROR) { "No message ID in message, abort" }
            return
        }

        // Fork change (UnifiedPush support): the notification rendering logic was
        // extracted verbatim to PushMessageRenderer so the UnifiedPush delivery
        // path can share it.
        PushMessageRenderer.render(
            context = this,
            authorId = authorId,
            authorName = authorName,
            avatarUrl = image,
            body = body,
            channelId = channelId,
            messageId = messageId
        )
    }
}
