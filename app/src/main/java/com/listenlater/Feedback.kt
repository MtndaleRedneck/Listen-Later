package com.listenlater

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val REPO_ISSUES = "https://github.com/MtndaleRedneck/Listen-Later/issues"
/** A plain Google Form, like BingeMote's: the app opens it pre-filled, he reviews and submits in the browser. */
const val BUG_FORM_URL = "https://docs.google.com/forms/d/e/1FAIpQLSc4sHZF51zAkdy7GyFJ63UHBrPybkpXcjqAp1QJDVbvxoRGhA/viewform"
private const val MAX_TEXT = 3000
private const val MAX_ENCODED = 4500

/** The only things a feedback report carries besides what he typed. No server address or sign-in details. */
data class FeedbackContext(val appVersion: String, val androidVersion: String, val phoneModel: String)

object Feedback {
    /** Trims the text so the encoded link stays well under the roughly 8 KB web servers accept. The preview shows this same trimmed text. */
    fun clean(text: String): String {
        var t = text.trim().take(MAX_TEXT)
        while (t.isNotEmpty() && enc(t).length > MAX_ENCODED) t = t.dropLast(50).trimEnd()
        return t
    }

    /** Exactly what is sent, shown before he taps. */
    fun preview(text: String, c: FeedbackContext): String =
        "Message:\n${clean(text).ifEmpty { "(nothing written yet)" }}\n\nApp version: ${c.appVersion}\nAndroid version: ${c.androidVersion}\nPhone model: ${c.phoneModel}"

    fun githubUrl(text: String, c: FeedbackContext): String {
        val t = clean(text)
        val title = "Feedback: " + t.lineSequence().firstOrNull().orEmpty().take(60)
        val body = "$t\n\n---\nApp version: ${c.appVersion}\nAndroid version: ${c.androidVersion}\nPhone model: ${c.phoneModel}"
        return "$REPO_ISSUES/new?title=${enc(title)}&body=${enc(body)}"
    }

    fun formUrl(text: String, c: FeedbackContext): String =
        "$BUG_FORM_URL?usp=pp_url&entry.2097207885=${enc(clean(text))}&entry.1328057796=${enc(c.phoneModel)}" +
            "&entry.166345919=${enc(c.androidVersion)}&entry.2138889044=${enc(c.appVersion)}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun context() = FeedbackContext(
        BuildConfig.VERSION_NAME,
        "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
    )
}

@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val c = remember { Feedback.context() }
    fun open(url: String) = ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Feedback", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        BackHandler { onBack() }

        FeedbackCard {
            Text("Tell us what is on your mind: a bug, an idea, or general feedback. Reports may become public issues on GitHub, so leave out personal details.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                text, { text = it.take(MAX_TEXT) },
                label = { Text("Your message") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextC, unfocusedTextColor = TextC,
                    focusedBorderColor = Sage, unfocusedBorderColor = Card2,
                    cursorColor = Sage,
                ),
            )
        }
        Spacer(Modifier.height(14.dp))

        FeedbackCard("What will be sent") {
            Text(Feedback.preview(text, c), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            Text("No server address or sign-in details are included. Nothing is sent until you tap a button below, and then it opens in your browser for you to review and submit.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(14.dp))

        FeedbackCard("GitHub issue") {
            Text("Public and easiest to follow up.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            FeedbackButton("Open GitHub issue") {
                if (text.isBlank()) Toast.makeText(ctx, "Write your message first.", Toast.LENGTH_SHORT).show()
                else open(Feedback.githubUrl(text, c))
            }
        }
        Spacer(Modifier.height(14.dp))

        FeedbackCard("Private form") {
            Text("No name needed. A reply email is optional.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            FeedbackButton("Open private form") {
                if (text.isBlank()) Toast.makeText(ctx, "Write your message first.", Toast.LENGTH_SHORT).show()
                else open(Feedback.formUrl(text, c))
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun FeedbackCard(title: String = "", content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
    ) {
        if (title.isNotEmpty()) {
            Text(title, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** The filled sage action button, app-wide style. */
@Composable
private fun FeedbackButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Sage, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(46.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
