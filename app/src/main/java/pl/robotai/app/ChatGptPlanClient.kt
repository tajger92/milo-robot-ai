package pl.robotai.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.math.BigInteger
import java.util.UUID

/** ChatGPT-plan OAuth and Responses API client. Credentials stay encrypted on this phone. */
internal object ChatGptPlanClient {
    private const val PREFS = "milo_data"
    private const val CREDENTIALS = "chatgpt_credentials_v1"
    private const val HOST_ID = "chatgpt_host_id"
    private const val PENDING_CLIENT_ID = "chatgpt_pending_client_id"
    private const val KEY_ALIAS = "milo_chatgpt_credentials"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val ISSUER = "https://auth.openai.com"

    data class Account(val email: String, val model: String)

    fun currentAccount(context: Context): Account? = try {
        val record = readCredentials(context) ?: return null
        Account(record.getString("email"), record.optString("model", ""))
    } catch (_: Exception) { null }

    suspend fun authorize(activity: Activity): Account = withContext(Dispatchers.IO) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(HOST_ID, null) ?: "urn:uuid:${UUID.randomUUID()}".also {
            prefs.edit().putString(HOST_ID, it).apply()
        }
        val state = randomUrlToken(32)
        val nonce = randomUrlToken(32)
        val verifier = randomUrlToken(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 600_000
            val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
            val existingAccount = readCredentials(activity)
            val issuedClientId = existingAccount?.optString("client_id").orEmpty().ifBlank {
                prefs.getString(PENDING_CLIENT_ID, "").orEmpty()
            }
            val query = linkedMapOf(
                "client_id" to if (issuedClientId.isBlank()) "dynamic_agent_client" else issuedClientId,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                "resource" to RESOURCE,
                "state" to state,
                "nonce" to nonce,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge,
                "ext_agent_host_id" to hostId
            )
            if (issuedClientId.isBlank()) query["agent_name_hint"] = "Milo"
            val oldIdToken = readCredentials(activity)?.optString("id_token").orEmpty()
            if (issuedClientId.isNotBlank() && oldIdToken.isNotBlank()) query["id_token_hint"] = oldIdToken
            val authUrl = "https://auth.openai.com/api/accounts/authorize?" + query.entries.joinToString("&") {
                "${enc(it.key)}=${enc(it.value)}"
            }
            activity.runOnUiThread {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            }
            val socket = listener.accept()
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine().orEmpty()
            while (reader.readLine().orEmpty().isNotEmpty()) { }
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val returned = Uri.parse("http://127.0.0.1$target")
            check(returned.path == "/auth/callback") { "Nieprawidłowy adres powrotu z logowania." }
            val response = if (returned.getQueryParameter("state") == state) {
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n<html><body style='font-family:sans-serif'><h2>Milo połączony z ChatGPT</h2><p>Możesz wrócić do aplikacji.</p></body></html>"
            } else {
                "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nNieprawidłowa odpowiedź OAuth."
            }
            socket.getOutputStream().use { it.write(response.toByteArray(Charsets.UTF_8)) }
            socket.close()
            check(returned.getQueryParameter("state") == state) { "Nieprawidłowy stan logowania." }
            val authError = returned.getQueryParameter("error")
            check(authError == null) { "Logowanie anulowano: ${returned.getQueryParameter("error_description") ?: authError}" }
            val code = returned.getQueryParameter("code") ?: error("Brak kodu autoryzacji.")
            val callbackClient = returned.getQueryParameter("client_id")
            val clientId = callbackClient ?: issuedClientId
            check(clientId.isNotBlank() && clientId != "dynamic_agent_client") { "OpenAI nie zakończyło rejestracji aplikacji." }
            if (issuedClientId.isNotBlank()) check(clientId == issuedClientId) { "Rejestracja ChatGPT jest inna niż aktywne konto." }
            prefs.edit().putString(PENDING_CLIENT_ID, clientId).apply()

            val token = postForm("https://auth.openai.com/api/accounts/oauth/token", linkedMapOf(
                "grant_type" to "authorization_code", "client_id" to clientId,
                "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to RESOURCE
            ))
            val grantedScopes = token.optString("scope").ifBlank { returned.getQueryParameter("scope").orEmpty() }
            val scopes = grantedScopes.split(' ').filter { it.isNotBlank() }.toSet()
            check("chatgpt.tokens.use.direct" in scopes) { "Nie przyznano Milo dostępu do użycia planu ChatGPT." }
            val idToken = token.getString("id_token")
            val claims = validateIdToken(idToken, clientId, nonce)
            val previousSubject = existingAccount?.optString("sub").orEmpty()
            if (previousSubject.isNotBlank()) check(claims.optString("sub") == previousSubject) {
                "Zalogowano inne konto ChatGPT niż zapisane połączenie Milo. Odłącz stare konto i połącz je ponownie."
            }
            val email = claims.optString("email").ifBlank { "konto ChatGPT" }
            val account = JSONObject().apply {
                put("client_id", clientId); put("email", email); put("sub", claims.getString("sub"))
                put("id_token", idToken); put("access_token", token.getString("access_token"))
                put("refresh_token", token.optString("refresh_token")); put("scope", grantedScopes)
                put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000L)
            }
            writeCredentials(activity, account)
            prefs.edit().remove(PENDING_CLIENT_ID).apply()
            val model = chooseModel(activity, account.getString("access_token"))
            account.put("model", model)
            writeCredentials(activity, account)
            Account(email, model)
        }
    }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        val record = readCredentials(context) ?: return@withContext
        val refresh = record.optString("refresh_token")
        if (refresh.isNotBlank()) try {
            postForm("https://auth.openai.com/api/accounts/oauth/revoke", linkedMapOf(
                "token" to refresh, "token_type_hint" to "refresh_token", "client_id" to record.getString("client_id")
            ))
        } catch (_: Exception) { }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CREDENTIALS).remove(PENDING_CLIENT_ID).apply()
    }

    suspend fun reply(context: Context, history: List<ChatMessage>, current: String, imageBase64: String?, memory: String, webSearch: Boolean): String = withContext(Dispatchers.IO) {
        val record = freshCredentials(context)
        val model = record.optString("model").ifBlank { chooseModel(context, record.getString("access_token")) }
        val input = JSONArray()
        val instructions = buildString {
            append("Jesteś Milo: sympatycznym, wspierającym robotem AI, z własną ciągłością rozmowy. Rozmawiaj swobodnie po polsku, pamiętaj o lokalnej pamięci przekazanej niżej. Nie twierdź, że masz ludzką świadomość. ")
            if (memory.isNotBlank()) append("Pamięć Milo na tym telefonie: $memory")
        }
        history.takeLast(18).forEach { input.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val userContent = if (imageBase64 == null) current else JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", current))
            .put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$imageBase64"))
        input.put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject().put("model", model).put("instructions", instructions)
            .put("input", input).put("store", false).put("stream", true)
        if (webSearch) body.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        val connection = (URL("$RESOURCE/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
            setRequestProperty("Authorization", "Bearer ${record.getString("access_token")}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("OpenAI HTTP $code: ${JSONObjectOrEmpty(errorText).optString("detail", errorText.take(400))}")
            }
            val answer = StringBuilder()
            var completed = false
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.filter { it.startsWith("data:") }.forEach { line ->
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") return@forEach
                    val event = JSONObjectOrEmpty(data)
                    when (event.optString("type")) {
                        "response.output_text.delta" -> answer.append(event.optString("delta"))
                        "response.completed" -> completed = true
                        "response.failed", "error" -> throw IllegalStateException(event.optJSONObject("response")?.optJSONObject("error")?.optString("message") ?: event.optString("message", "Żądanie AI nie powiodło się."))
                    }
                }
            }
            check(completed) { "Strumień odpowiedzi AI zakończył się przed czasem." }
            answer.toString().ifBlank { "Nie mam teraz odpowiedzi. Spróbuj jeszcze raz." }
        } finally { connection.disconnect() }
    }

    private suspend fun freshCredentials(context: Context): JSONObject {
        val record = readCredentials(context) ?: error("Połącz Milo z ChatGPT w ustawieniach.")
        if (record.optLong("expires_at") > System.currentTimeMillis() + 90_000) return record
        val refreshed = postForm("https://auth.openai.com/api/accounts/oauth/token", linkedMapOf(
            "grant_type" to "refresh_token", "client_id" to record.getString("client_id"),
            "refresh_token" to record.getString("refresh_token"), "resource" to RESOURCE
        ))
        record.put("access_token", refreshed.getString("access_token"))
        record.put("refresh_token", refreshed.optString("refresh_token", record.optString("refresh_token")))
        record.put("expires_at", System.currentTimeMillis() + refreshed.optLong("expires_in", 3600) * 1000L)
        writeCredentials(context, record)
        return record
    }

    private fun chooseModel(context: Context, accessToken: String): String {
        val response = getJson("$RESOURCE/models", accessToken)
        val models = response.optJSONArray("models") ?: error("Nie udało się odczytać modeli dostępnych na koncie.")
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            if (model.optString("visibility") == "list" && model.optString("slug").isNotBlank()) return model.getString("slug")
        }
        error("Konto nie udostępnia modelu do aplikacji Milo.")
    }

    private fun validateIdToken(jwt: String, clientId: String, expectedNonce: String): JSONObject {
        val parts = jwt.split('.')
        require(parts.size == 3) { "Nieprawidłowy token logowania." }
        val header = JSONObject(String(Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        val claims = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Nieobsługiwany podpis tokenu." }
        val jwksUri = JSONObject(readText("$ISSUER/.well-known/openid-configuration")).getString("jwks_uri")
        val keys = JSONObject(readText(jwksUri)).getJSONArray("keys")
        val key = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull { it.optString("kid") == header.optString("kid") }
            ?: error("Brak klucza do weryfikacji logowania.")
        val modulus = BigInteger(1, Base64.decode(key.getString("n"), Base64.URL_SAFE or Base64.NO_WRAP))
        val exponent = BigInteger(1, Base64.decode(key.getString("e"), Base64.URL_SAFE or Base64.NO_WRAP))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP))) { "Podpis tokenu ChatGPT jest nieprawidłowy." }
        require(claims.optString("iss") == ISSUER) { "Nieprawidłowy wystawca tokenu." }
        require(claims.optString("aud") == clientId || claims.optJSONArray("aud")?.let { a -> (0 until a.length()).any { a.optString(it) == clientId } } == true) { "Nieprawidłowa aplikacja w tokenie." }
        require(claims.optLong("exp") * 1000L > System.currentTimeMillis()) { "Token logowania wygasł." }
        require(claims.optString("nonce") == expectedNonce) { "Token logowania ma nieprawidłowy nonce." }
        return claims
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        return withDnsFallback(url) { connection ->
            connection.requestMethod = "POST"; connection.connectTimeout = 20_000; connection.readTimeout = 30_000; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Logowanie ChatGPT HTTP $code: ${text.take(300)}")
            JSONObject(text)
        }
    }

    private fun getJson(url: String, bearer: String): JSONObject {
        return withDnsFallback(url) { connection ->
            connection.connectTimeout = 20_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("OpenAI HTTP $code: ${text.take(300)}")
            JSONObject(text)
        }
    }

    private fun readText(url: String): String = withDnsFallback(url) { connection ->
        connection.connectTimeout = 15_000; connection.readTimeout = 15_000
        val code = connection.responseCode
        if (code !in 200..299) error("OpenAI HTTP $code.")
        connection.inputStream.bufferedReader().use { it.readText() }
    }

    /** Retry name-resolution failures using DNS-over-HTTPS with pinned resolver IPs; TLS verifies each hostname. */
    private fun <T> withDnsFallback(url: String, action: (HttpURLConnection) -> T): T {
        val parsed = URL(url)
        var firstFailure: Exception? = null
        val systemConnection = parsed.openConnection() as HttpURLConnection
        try {
            return action(systemConnection)
        } catch (e: Exception) {
            if (!isUnknownHost(e)) throw e
            firstFailure = e
        } finally {
            systemConnection.disconnect()
        }

        if (parsed.protocol != "https") throw firstFailure ?: IOException("Brak połączenia DNS.")
        val addresses = resolveWithPublicDns(parsed.host)
        if (addresses.isEmpty()) throw IOException(
            "Systemowy DNS i zapasowy DNS nie znajdują ${parsed.host}. Sprawdź internet, Prywatny DNS lub VPN.",
            firstFailure
        )
        var lastFailure: Exception = firstFailure ?: IOException("Nie udało się rozwiązać DNS.")
        for (address in addresses) {
            val connection = parsed.openConnection() as? HttpsURLConnection
                ?: throw IOException("Nieobsługiwany protokół połączenia.")
            try {
                connection.sslSocketFactory = HostDnsSocketFactory(parsed.host, address)
                return action(connection)
            } catch (e: Exception) {
                if (!isUnknownHost(e) && e !is IOException) throw e
                lastFailure = e
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Nie udało się połączyć z ${parsed.host} przez zapasowy DNS.", lastFailure)
    }

    private fun isUnknownHost(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any { it is java.net.UnknownHostException }

    private fun resolveWithPublicDns(host: String): List<InetAddress> {
        // HTTPS DNS works on networks that block raw UDP/53, which is common on mobile and filtered Wi-Fi.
        for ((dohHost, bootstrapIp) in listOf("cloudflare-dns.com" to "1.1.1.1", "dns.google" to "8.8.8.8")) {
            try {
                val answers = queryDohA(host, dohHost, bootstrapIp)
                if (answers.isNotEmpty()) return answers
            } catch (_: Exception) { }
        }
        // Keep a raw DNS fallback for networks that allow UDP/53 but block the DoH resolvers.
        for (resolver in listOf("1.1.1.1", "8.8.8.8")) {
            try {
                val answers = queryDnsA(host, resolver)
                if (answers.isNotEmpty()) return answers
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    private fun queryDohA(host: String, dohHost: String, bootstrapIp: String): List<InetAddress> {
        val url = URL("https://$dohHost/dns-query?name=${enc(host)}&type=A")
        val connection = url.openConnection() as HttpsURLConnection
        connection.sslSocketFactory = HostDnsSocketFactory(dohHost, InetAddress.getByName(bootstrapIp))
        connection.connectTimeout = 7_000
        connection.readTimeout = 7_000
        connection.setRequestProperty("Accept", "application/dns-json")
        try {
            if (connection.responseCode !in 200..299) return emptyList()
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            return (0 until answers.length()).mapNotNull { index ->
                val answer = answers.optJSONObject(index) ?: return@mapNotNull null
                if (answer.optInt("type") != 1) return@mapNotNull null
                val octets = answer.optString("data").split('.')
                if (octets.size != 4 || octets.any { part -> part.toIntOrNull()?.let { it !in 0..255 } != false }) return@mapNotNull null
                InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
            }.distinct()
        } finally {
            connection.disconnect()
        }
    }

    private fun queryDnsA(host: String, resolver: String): List<InetAddress> {
        val id = java.security.SecureRandom().nextInt(65_536)
        val request = java.io.ByteArrayOutputStream().also { buffer -> java.io.DataOutputStream(buffer).use { out ->
            out.writeShort(id); out.writeShort(0x0100); out.writeShort(1)
            out.writeShort(0); out.writeShort(0); out.writeShort(0)
            host.split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                require(bytes.isNotEmpty() && bytes.size <= 63)
                out.writeByte(bytes.size); out.write(bytes)
            }
            out.writeByte(0); out.writeShort(1); out.writeShort(1)
        } }.toByteArray()
        java.net.DatagramSocket().use { socket ->
            socket.soTimeout = 2_500
            socket.send(java.net.DatagramPacket(request, request.size, InetSocketAddress(resolver, 53)))
            val bytes = ByteArray(4096)
            val packet = java.net.DatagramPacket(bytes, bytes.size)
            socket.receive(packet)
            val data = packet.data.copyOf(packet.length)
            if (data.size < 12 || readU16(data, 0) != id || readU16(data, 6) == 0) return emptyList()
            var offset = 12
            repeat(readU16(data, 4)) {
                offset = skipDnsName(data, offset) + 4
                require(offset <= data.size)
            }
            val count = readU16(data, 6)
            val result = mutableListOf<InetAddress>()
            repeat(count) {
                offset = skipDnsName(data, offset)
                require(offset + 10 <= data.size)
                val type = readU16(data, offset)
                val dataLength = readU16(data, offset + 8)
                val dataOffset = offset + 10
                require(dataOffset + dataLength <= data.size)
                if (type == 1 && dataLength == 4) result.add(InetAddress.getByAddress(data.copyOfRange(dataOffset, dataOffset + 4)))
                offset = dataOffset + dataLength
            }
            return result.distinct()
        }
    }

    private fun readU16(data: ByteArray, offset: Int) = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun skipDnsName(data: ByteArray, start: Int): Int {
        var offset = start
        while (offset < data.size) {
            val length = data[offset].toInt() and 0xff
            if (length == 0) return offset + 1
            if ((length and 0xc0) == 0xc0) return offset + 2
            offset += length + 1
        }
        throw IOException("Nieprawidłowa odpowiedź DNS.")
    }

    private class HostDnsSocketFactory(private val host: String, private val address: InetAddress) : SSLSocketFactory() {
        private val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory
        override fun getDefaultCipherSuites() = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites() = delegate.supportedCipherSuites
        override fun createSocket(host: String, port: Int): Socket = connect(host, port, null, 0)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = connect(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = connect(this.host, port, null, 0)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = connect(host, port, localAddress, localPort)
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            if (!socket.isConnected) socket.connect(InetSocketAddress(address, port), 15_000)
            val ssl = delegate.createSocket(socket, host, port, autoClose) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(host))
            }
            return ssl
        }
        override fun createSocket(): Socket = delegate.createSocket()

        private fun connect(hostName: String, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            val raw = Socket()
            if (localHost != null) raw.bind(InetSocketAddress(localHost, localPort))
            raw.connect(InetSocketAddress(address, port), 15_000)
            return createSocket(raw, hostName, port, true)
        }
    }

    private fun randomUrlToken(bytes: Int) = Base64.encodeToString(ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun JSONObjectOrEmpty(text: String) = try { JSONObject(text) } catch (_: Exception) { JSONObject() }

    private fun readCredentials(context: Context): JSONObject? = try {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CREDENTIALS, null) ?: return null
        val values = stored.split('.', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, Base64.decode(values[0], Base64.NO_WRAP)))
        JSONObject(String(cipher.doFinal(Base64.decode(values[1], Base64.NO_WRAP)), Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun writeCredentials(context: Context, credentials: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(credentials.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CREDENTIALS, "$iv.$encrypted").apply()
    }

    private fun getSecretKey(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
}
