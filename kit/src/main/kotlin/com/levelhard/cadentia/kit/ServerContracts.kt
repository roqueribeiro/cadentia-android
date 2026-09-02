package com.levelhard.cadentia.kit

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Os contratos do roqueos-server e das sessões — ports 1:1 de
 * `NetworkStorageContract`, `ServerFilesystemContract`,
 * `ServerRequestHeaders`, `SessionDurability`, `DownloadIntegrity` e
 * `ServerSelection` do CadentiaKit. Cada um existe por causa de um bug real
 * documentado no iOS; os comentários de lá valem aqui.
 */

private val contractJson = Json { ignoreUnknownKeys = true }

/**
 * Discos mapeados: ambos os endpoints respondem ARRAY NA RAIZ, nunca
 * objeto. Supor `{"entries": [...]}` foi um bug real — 200 na requisição,
 * falha só na decodificação, tela dizendo "não deu para abrir esta pasta".
 */
object NetworkStorageContract {
    /** Campos extras do servidor (`isFile`, `permissions`) são ignorados. */
    @Serializable
    data class Entry(
        val name: String,
        /** Caminho já pronto, relativo à raiz do disco — nunca montar à mão. */
        val path: String,
        val isDirectory: Boolean,
        val size: Long? = null,
        val modified: String? = null,
    )

    @Serializable
    data class Disk(val id: String, val name: String? = null)

    fun entries(json: String): List<Entry> =
        contractJson.decodeFromString(ListSerializer(Entry.serializer()), json)

    fun disks(json: String): List<Disk> =
        contractJson.decodeFromString(ListSerializer(Disk.serializer()), json)
}

/**
 * A pasta `/shared`: NÃO é o mesmo formato dos discos mapeados, e essa é a
 * razão deste objeto existir separado. `/fs/list` responde um OBJETO
 * `{"files": [...]}`; array na raiz aqui é erro.
 */
object ServerFilesystemContract {
    @Serializable
    data class Entry(
        val name: String,
        /** Caminho absoluto dentro do servidor, já pronto (ex.: `/shared/Music`). */
        val path: String,
        val isDirectory: Boolean,
        val size: Long? = null,
        val modified: String? = null,
    )

    @Serializable
    data class Listing(val files: List<Entry>)

    fun entries(json: String): List<Entry> =
        contractJson.decodeFromString(Listing.serializer(), json).files
}

/**
 * Os cabeçalhos de toda requisição ao roqueos-server. Faltar o
 * `X-Firebase-Token` NÃO dá erro: o servidor responde 200 com lista vazia —
 * duas rodadas de diagnóstico foram embora nisso.
 */
object ServerRequestHeaders {
    const val API_KEY = "X-API-Key"
    const val API_SECRET = "X-API-Secret"

    /** Identidade VERIFICADA: o servidor confere o token e age como o uid provado. */
    const val IDENTITY = "X-Firebase-Token"

    fun build(apiKey: String, apiSecret: String, identity: String?): Map<String, String> {
        val headers = mutableMapOf(API_KEY to apiKey, API_SECRET to apiSecret)
        if (!identity.isNullOrEmpty()) headers[IDENTITY] = identity
        return headers
    }

    /** Credencial nunca vai em URL: entra em log de proxy, histórico e Referer. */
    fun leaksCredentials(url: String): Boolean {
        val lower = url.lowercase()
        return listOf("apikey=", "apisecret=", "api_key=", "api_secret=", "x-api-key=")
            .any { lower.contains(it) }
    }
}

/**
 * Decide se uma falha ao renovar a sessão significa "o usuário foi
 * desligado" ou apenas "a rede piscou". Conservador de propósito: só derruba
 * quando o Firebase diz, em texto, que a credencial morreu.
 */
object SessionDurability {
    /** Fonte: erros do endpoint `securetoken.googleapis.com/v1/token`. */
    val revokedReasons = listOf(
        "TOKEN_EXPIRED",
        "USER_DISABLED",
        "USER_NOT_FOUND",
        "INVALID_REFRESH_TOKEN",
        "INVALID_GRANT_TYPE",
        "MISSING_REFRESH_TOKEN",
    )

    /** true só quando a credencial morreu de verdade. */
    fun isRevoked(reason: String): Boolean {
        val upper = reason.uppercase()
        return revokedReasons.any { upper.contains(it) }
    }
}

/**
 * Decide se um download chegou inteiro. Download parcial é o pior tipo de
 * falha: produz um arquivo VÁLIDO, só curto — a música separava, tocava, e
 * trazia poucos segundos.
 */
object DownloadIntegrity {
    /**
     * Quantos bytes o arquivo deveria ter. null quando não há como saber — e
     * nesse caso não se acusa nada. Num 206, o `Content-Length` mede só o
     * pedaço; o total confiável é o `Content-Range` (depois da barra).
     */
    fun expectedBytes(
        status: Int,
        contentLength: String?,
        contentRange: String?,
        listedSize: Long?,
    ): Long? {
        totalFromContentRange(contentRange)?.let { return it }
        if (listedSize != null && listedSize > 0) return listedSize
        if (status == 206) return null
        contentLength?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        return null
    }

    /** O total declarado em `bytes <inicio>-<fim>/<total>`; `*` = desconhecido. */
    fun totalFromContentRange(header: String?): Long? {
        if (header == null) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0) return null
        val total = header.substring(slash + 1).trim()
        if (total == "*") return null
        return total.toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * Tolera uma diferença mínima (containers contam cabeçalho diferente);
     * um pedaço de 1 MB numa música de 5 MB não escapa dessa folga.
     */
    fun isTruncated(got: Long, expected: Long, tolerance: Long = 4096): Boolean =
        got + tolerance < expected
}

/**
 * Escolhe o servidor RoqueOS ativo e AS CREDENCIAIS DELE. Errar o pareamento
 * dá 401 sem pista: URL de um servidor com a chave de outro. A regra vem do
 * `backendSettings.js`: manda o `activeServiceId`, e a credencial
 * autoritativa é `credentialsByServiceId[activeServiceId]`; os campos de
 * topo são só compatibilidade e valem apenas para o serviço a que pertencem.
 */
object ServerSelection {
    data class Service(val id: String, val url: String)

    data class Credentials(val apiKey: String, val apiSecret: String) {
        val isUsable: Boolean get() = apiKey.isNotEmpty() && apiSecret.isNotEmpty()
    }

    data class Resolved(val service: Service, val credentials: Credentials)

    sealed class Problem : Exception() {
        object NoServerConfigured : Problem() {
            private fun readResolve(): Any = NoServerConfigured
        }

        /** Existe servidor, mas nenhuma credencial serve para ELE. */
        data class NoCredentialsForActiveServer(val serviceID: String) : Problem()
    }

    fun resolve(
        services: List<Service>,
        activeServiceID: String?,
        perService: Map<String, Credentials>,
        fallback: Credentials?,
        fallbackServiceID: String?,
    ): Resolved {
        val service = pick(services, activeServiceID) ?: throw Problem.NoServerConfigured

        perService[service.id]?.takeIf { it.isUsable }?.let {
            return Resolved(service, it)
        }
        // O fallback só entra quando pertence comprovadamente a este serviço.
        if (fallback != null && fallback.isUsable && fallbackServiceID == service.id) {
            return Resolved(service, fallback)
        }
        // Um único servidor e um único conjunto de credenciais: não há como
        // parear errado.
        if (fallback != null && fallback.isUsable && services.size == 1 && perService.isEmpty()) {
            return Resolved(service, fallback)
        }
        throw Problem.NoCredentialsForActiveServer(service.id)
    }

    /**
     * TODOS os servidores utilizáveis, não só o ativo (o ativo vem primeiro).
     * Servidor sem credencial utilizável é omitido em silêncio, não incluído
     * para dar 401 na cara do usuário.
     */
    fun resolveAll(
        services: List<Service>,
        activeServiceID: String?,
        perService: Map<String, Credentials>,
        fallback: Credentials?,
        fallbackServiceID: String?,
    ): List<Resolved> {
        val usable = services.filter { it.url.isNotEmpty() }
        val resolved = mutableListOf<Resolved>()
        for (service in usable) {
            val exact = perService[service.id]?.takeIf { it.isUsable }
            if (exact != null) {
                resolved.add(Resolved(service, exact))
                continue
            }
            val belongs = fallbackServiceID == service.id
            val unambiguous = usable.size == 1 && perService.isEmpty()
            if (fallback != null && fallback.isUsable && (belongs || unambiguous)) {
                resolved.add(Resolved(service, fallback))
            }
        }
        return resolved.sortedByDescending { it.service.id == activeServiceID }
    }

    private fun pick(services: List<Service>, activeID: String?): Service? {
        val usable = services.filter { it.url.isNotEmpty() }
        if (activeID != null) {
            usable.firstOrNull { it.id == activeID }?.let { return it }
        }
        // Sem ativo que case, um único servidor é escolha óbvia; com vários,
        // escolher no chute daria 401 confuso — melhor dizer que falta config.
        return if (usable.size == 1) usable[0] else null
    }
}
