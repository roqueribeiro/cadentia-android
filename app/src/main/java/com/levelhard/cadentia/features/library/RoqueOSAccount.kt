package com.levelhard.cadentia.features.library

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.levelhard.cadentia.kit.SessionDurability
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Conecta a Cadentia à conta RoqueOS do usuário — port do
 * `RoqueOSAccount.swift`. Ninguém digita senha: o app mostra um código
 * curto, o usuário aprova num aparelho onde já está logado, e só então o
 * app recebe um token.
 *
 *     initTvLogin  → código curto + tvSecret (o tvSecret fica só aqui)
 *     usuário aprova em roqueos.com.br/link/<id>
 *     claimTvLogin → custom token
 *     signInWithCustomToken → idToken + refreshToken
 *
 * O refresh token vai cifrado pelo ANDROID KEYSTORE (AES/GCM) para o
 * SharedPreferences — o papel do Keychain: ele vale até o usuário revogar,
 * e prefs em texto claro saem em backup. Uma sessão por app (singleton),
 * porque duas instâncias renovando o mesmo refresh token disputam entre si
 * — a lição do "a conexão fica se perdendo" do iOS.
 */
class RoqueOSAccount private constructor(context: Context) {
    companion object {
        @Volatile private var instance: RoqueOSAccount? = null

        fun shared(context: Context): RoqueOSAccount =
            instance ?: synchronized(this) {
                instance ?: RoqueOSAccount(context.applicationContext).also { instance = it }
            }

        private const val PREFS = "cadentia.roqueos"
        private const val KEY_REFRESH = "roqueos.refreshToken"
        private const val KEY_USER = "roqueos.userID"
    }

    sealed class Phase {
        data object Disconnected : Phase()
        data object Starting : Phase()
        data class Waiting(val code: String, val link: String) : Phase()
        data class Connected(val email: String?) : Phase()
        data class Failed(val reason: String) : Phase()
    }

    val config = RoqueOSConfig.load(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val vault = KeystoreVault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }

    private val phaseFlow = MutableStateFlow<Phase>(Phase.Disconnected)
    val phase: StateFlow<Phase> = phaseFlow

    @Volatile var userID: String? = null
        private set

    @Volatile private var idToken: String? = null
    @Volatile private var idTokenExpiryMillis = 0L
    private var pollJob: Job? = null
    /** Renovações concorrentes se coalescem aqui. */
    @Volatile private var refreshInFlight: Deferred<String>? = null

    init {
        // O uid não é segredo (aparece em todo caminho do Firestore): prefs.
        userID = prefs.getString(KEY_USER, null)
        if (readRefreshToken() != null) {
            phaseFlow.value = Phase.Connected(email = null)
        }
    }

    val isConnected: Boolean get() = phaseFlow.value is Phase.Connected

    // ---- pareamento ----

    fun connect() {
        if (!config.isConfigured) {
            phaseFlow.value = Phase.Failed("nao configurado")
            return
        }
        pollJob?.cancel()
        phaseFlow.value = Phase.Starting

        pollJob = scope.launch {
            try {
                val start = withContext(Dispatchers.IO) {
                    postJson(
                        "${config.functionsBaseURL}/initTvLogin",
                        // `client` diz QUEM pediu (allowlist do lado de lá).
                        buildJsonObject {
                            put("purpose", "login")
                            put("client", "cadentia")
                        },
                    )
                }
                val pairingId = start.text("pairingId") ?: error("sem pairingId")
                val shortCode = start.text("shortCode") ?: error("sem shortCode")
                val tvSecret = start.text("tvSecret") ?: error("sem tvSecret")
                val expiresInMs = start.text("expiresInMs")?.toLongOrNull() ?: 300_000L
                phaseFlow.value = Phase.Waiting(
                    code = shortCode,
                    link = config.approvalURL(pairingId),
                )
                waitForApproval(pairingId, tvSecret, expiresInMs)
            } catch (_: CancellationException) {
                phaseFlow.value = Phase.Disconnected
            } catch (error: RoqueOSException) {
                phaseFlow.value = Phase.Failed(error.display)
            } catch (error: Exception) {
                phaseFlow.value = Phase.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun cancelConnecting() {
        pollJob?.cancel()
        pollJob = null
        if (phaseFlow.value !is Phase.Connected) phaseFlow.value = Phase.Disconnected
    }

    /** O único caminho de saída. Nada mais desconecta sozinho. */
    fun disconnect() {
        pollJob?.cancel()
        refreshInFlight?.cancel()
        refreshInFlight = null
        forgetSession()
    }

    /** O Firestore não tem tempo real por REST: consulta de segundo em segundo. */
    private suspend fun waitForApproval(pairingId: String, tvSecret: String, expiresInMs: Long) {
        val deadline = System.currentTimeMillis() + expiresInMs
        while (System.currentTimeMillis() < deadline) {
            delay(1000)
            val document = withContext(Dispatchers.IO) {
                runCatching { getJson("${config.firestoreBaseURL}/devicePairings/$pairingId") }
                    .getOrNull()
            } ?: continue
            when (document.firestoreString("status") ?: "?") {
                "approved" -> {
                    claim(pairingId, tvSecret)
                    return
                }
                "denied" -> {
                    phaseFlow.value = Phase.Failed("recusado")
                    return
                }
                "expired" -> {
                    phaseFlow.value = Phase.Failed("expirou")
                    return
                }
                else -> continue
            }
        }
        phaseFlow.value = Phase.Failed("expirou")
    }

    private suspend fun claim(pairingId: String, tvSecret: String) {
        // 425 = "aprovado mas ainda não gravado". O claim é idempotente até
        // ser consumido; repetir é seguro e resolve a corrida.
        var claimed: JsonObject? = null
        for (attempt in 0 until 5) {
            try {
                claimed = withContext(Dispatchers.IO) {
                    postJson(
                        "${config.functionsBaseURL}/claimTvLogin",
                        buildJsonObject {
                            put("pairingId", pairingId)
                            put("tvSecret", tvSecret)
                        },
                    )
                }
                break
            } catch (error: RoqueOSException) {
                if (error.status == 425) delay(600) else throw error
            }
        }
        val result = claimed ?: throw RoqueOSException(425, "claim", "sem token")
        val custom = result.text("token") ?: throw RoqueOSException(0, "claim", "sem token")
        // O uid vem do CLAIM, não do signin: signInWithCustomToken responde
        // só idToken/refreshToken/expiresIn (a lição do bug do localId).
        val uid = result.text("uid") ?: throw RoqueOSException(0, "claim", "sem uid")

        val session = withContext(Dispatchers.IO) {
            postJson(
                "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=${config.apiKey.orEmpty()}",
                buildJsonObject {
                    put("token", custom)
                    put("returnSecureToken", true)
                },
            )
        }
        apply(
            idToken = session.text("idToken") ?: throw RoqueOSException(0, "signin", "sem idToken"),
            refreshToken = session.text("refreshToken") ?: throw RoqueOSException(0, "signin", "sem refresh"),
            expiresInSeconds = session.text("expiresIn")?.toLongOrNull() ?: 3600,
        )
        setUserID(uid)
        phaseFlow.value = Phase.Connected(email = null)
    }

    // ---- token ----

    /**
     * Devolve um ID token válido, renovando quando falta menos de cinco
     * minutos. NUNCA mexe na phase por falha de rede: a sessão só cai quando
     * o Firebase diz, em texto, que o refresh token não vale mais
     * (SessionDurability decide).
     */
    suspend fun ensureToken(): String {
        idToken?.let { held ->
            if (idTokenExpiryMillis > System.currentTimeMillis() + 300_000) return held
        }
        refreshInFlight?.let { return it.await() }

        val refresh = readRefreshToken() ?: throw RoqueOSException.notConnected()

        val task = scope.async(Dispatchers.IO) {
            try {
                val renewed = postForm(
                    "https://securetoken.googleapis.com/v1/token?key=${config.apiKey.orEmpty()}",
                    mapOf("grant_type" to "refresh_token", "refresh_token" to refresh),
                )
                apply(
                    idToken = renewed.text("id_token") ?: throw RoqueOSException(0, "refresh", "sem id_token"),
                    refreshToken = renewed.text("refresh_token") ?: refresh,
                    expiresInSeconds = renewed.text("expires_in")?.toLongOrNull() ?: 3600,
                )
                renewed.text("user_id")?.let { setUserID(it) }
                if (phaseFlow.value !is Phase.Connected) phaseFlow.value = Phase.Connected(email = null)
                idToken!!
            } catch (error: RoqueOSException) {
                if (SessionDurability.isRevoked(error.reason)) {
                    // O Firebase disse que a credencial morreu. Insistir seria
                    // um limbo "conectado" que nunca funciona.
                    forgetSession()
                    throw RoqueOSException.notConnected()
                }
                throw error
            } finally {
                refreshInFlight = null
            }
        }
        refreshInFlight = task
        return task.await()
    }

    private fun setUserID(uid: String) {
        userID = uid
        prefs.edit().putString(KEY_USER, uid).apply()
    }

    private fun forgetSession() {
        prefs.edit().remove(KEY_REFRESH).remove(KEY_USER).apply()
        idToken = null
        idTokenExpiryMillis = 0
        userID = null
        phaseFlow.value = Phase.Disconnected
    }

    private fun apply(idToken: String, refreshToken: String, expiresInSeconds: Long) {
        this.idToken = idToken
        idTokenExpiryMillis = System.currentTimeMillis() + expiresInSeconds * 1000
        val stored = vault.encrypt(refreshToken)?.also {
            prefs.edit().putString(KEY_REFRESH, it).apply()
        }
        if (stored == null) {
            // Sem o refresh token persistido a sessão morre no próximo
            // arranque. Gritar no log é melhor que descobrir depois.
            android.util.Log.e("RoqueOS", "ATENCAO: refresh token nao persistiu")
        }
    }

    private fun readRefreshToken(): String? =
        prefs.getString(KEY_REFRESH, null)?.let { vault.decrypt(it) }

    // ---- HTTP (com o retry único de transporte do iOS) ----

    internal fun postJson(url: String, body: JsonObject): JsonObject =
        send(url) { connection ->
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
        }

    internal fun postForm(url: String, fields: Map<String, String>): JsonObject =
        send(url) { connection ->
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            val encoded = fields.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            connection.outputStream.use { it.write(encoded.toByteArray()) }
        }

    internal fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject =
        send(url) { connection ->
            for ((name, value) in headers) connection.setRequestProperty(name, value)
        }

    private fun send(url: String, configure: (HttpURLConnection) -> Unit): JsonObject {
        // Uma única repetição para falha de TRANSPORTE, de propósito: se a
        // segunda também cair, a rede está fora de verdade.
        var lastTransport: IOException? = null
        for (attempt in 0 until 2) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            try {
                configure(connection)
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (status !in 200..299) {
                    throw RoqueOSException(status, url.substringAfterLast('/').substringBefore('?'), shortReason(body))
                }
                return runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse {
                        throw RoqueOSException(status, url.substringAfterLast('/'), "resposta inesperada")
                    }
            } catch (transport: SocketTimeoutException) {
                lastTransport = transport
            } catch (transport: java.net.ConnectException) {
                lastTransport = transport
            } finally {
                connection.disconnect()
            }
        }
        throw lastTransport ?: IOException("transporte")
    }

    /** O motivo curto do corpo de erro (`{"error":"..."}` ou aninhado do Google). */
    private fun shortReason(body: String): String = runCatching {
        val parsed = json.parseToJsonElement(body).jsonObject
        parsed["error"]?.let { error ->
            if (error is JsonObject) {
                error["message"]?.jsonPrimitive?.content
            } else {
                error.jsonPrimitive.content
            }
        } ?: ""
    }.getOrDefault("")
}

/** O erro com diagnóstico em uma linha ("claim 409 already_used"). */
class RoqueOSException(
    val status: Int,
    val step: String,
    val reason: String,
) : Exception("$step $status $reason") {
    companion object {
        fun notConnected() = RoqueOSException(0, "sessao", "sem sessao")
    }

    val display: String
        get() = if (reason.isEmpty()) "$step $status" else "$step $status $reason"
}

/** Helpers de leitura sobre o JSON dinâmico. */
internal fun JsonObject.text(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

/** Campo de documento do Firestore (`fields.X.stringValue`). */
internal fun JsonObject.firestoreString(key: String): String? =
    this["fields"]?.jsonObject?.get(key)?.jsonObject?.text("stringValue")

/**
 * Cifra pequenos segredos com uma chave presa no Android Keystore (AES/GCM)
 * — o papel do Keychain do iOS. A chave nunca sai do hardware; o blob
 * cifrado (iv + texto) vai em base64 para o SharedPreferences.
 */
private class KeystoreVault {
    private companion object {
        const val ALIAS = "cadentia.roqueos.vault"
        const val STORE = "AndroidKeyStore"
    }

    private fun key(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generator.generateKey()
        }
    }.getOrNull()

    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: return null)
        val sealed = cipher.doFinal(plain.toByteArray())
        Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val sealed = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key() ?: return null, GCMParameterSpec(128, iv))
        String(cipher.doFinal(sealed))
    }.getOrNull()
}
