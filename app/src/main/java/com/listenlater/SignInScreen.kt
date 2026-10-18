package com.listenlater

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun normalizeServer(input: String): String {
    var s = input.trim().trimEnd('/')
    if (s.startsWith("http://")) return "http://"
    if (s.isNotEmpty() && !s.contains("://")) s = "https://$s"
    return s
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = Muted, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Surface(color = Card2, shape = RoundedCornerShape(14.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = TextC, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(hint, color = Muted, fontSize = 15.sp)
                    inner()
                }
            )
        }
    }
}

@Composable
fun SignInScreen(store: CredStore, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(store.server ?: "") }
    var username by remember { mutableStateOf(store.username ?: "") }
    var password by remember { mutableStateOf("") }
    var captchaCode by remember { mutableStateOf("") }
    var captchaId by remember { mutableStateOf<String?>(null) }
    var captchaImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captchaOn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refreshCaptcha(serverUrl: String) {
        scope.launch {
            try {
                val api = Api(serverUrl, null)
                val enabled = withContext(Dispatchers.IO) { api.captchaEnabled() }
                captchaOn = enabled
                if (enabled) {
                    val (id, imageData) = withContext(Dispatchers.IO) { api.captcha() }
                    val bytes = Base64.decode(imageData.substringAfter("base64,"), Base64.DEFAULT)
                    captchaId = id
                    captchaImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    captchaCode = ""
                } else {
                    captchaId = null
                    captchaImage = null
                }
            } catch (e: Exception) {
                captchaOn = false
                captchaId = null
                captchaImage = null
            }
        }
    }

    // Captcha comes from whatever server is typed, after a short pause in typing.
    LaunchedEffect(server) {
        val normalized = normalizeServer(server)
        if (normalized.length > 8 && normalized != "http://") {
            delay(800)
            refreshCaptcha(normalized)
        } else {
            captchaOn = false
            captchaImage = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Card),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Sign in", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextC)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sign-in lasts about a month. When it runs out you'll land back here with a new picture.",
                    fontSize = 13.sp, color = Muted
                )
                Spacer(Modifier.height(18.dp))
                Field("SERVER", server, { server = it }, "https://your-server.example",
                    keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(12.dp))
                Field("USERNAME", username, { username = it }, "username")
                Spacer(Modifier.height(12.dp))
                Field("PASSWORD", password, { password = it }, "password", secret = true,
                    keyboardType = KeyboardType.Password)
                if (captchaOn && captchaImage != null) {
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Surface(
                            color = Card2,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.width(150.dp).height(56.dp)
                        ) {
                            Image(
                                bitmap = captchaImage!!.asImageBitmap(),
                                contentDescription = "captcha",
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Field(
                            "TYPE THE LETTERS", captchaCode, { captchaCode = it }, "",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Yellow, fontSize = 13.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val normalized = normalizeServer(server)
                        when {
                            normalized == "http://" -> error = "HTTPS only - use an https:// address."
                            normalized.length <= 8 -> error = "Enter your server address."
                            username.isBlank() -> error = "Enter your username."
                            password.isEmpty() -> error = "Enter your password."
                            captchaOn && captchaCode.isBlank() -> error = "Type the letters in the picture."
                            else -> {
                                loading = true
                                error = null
                                scope.launch {
                                    try {
                                        val api = Api(normalized, null)
                                        val token = withContext(Dispatchers.IO) {
                                            api.login(username.trim(), password, captchaId, captchaCode.trim())
                                        }
                                        store.markLoggedIn(normalized, username.trim(), token)
                                        loading = false
                                        onLoggedIn()
                                    } catch (e: Exception) {
                                        loading = false
                                        error = e.message ?: "Login failed."
                                        refreshCaptcha(normalized)
                                    }
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Sage, contentColor = androidx.compose.ui.graphics.Color.White),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "Signing in..." else "Log in", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
