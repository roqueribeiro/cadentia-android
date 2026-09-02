package com.levelhard.cadentia.features.library

import com.levelhard.cadentia.kit.DownloadIntegrity
import com.levelhard.cadentia.kit.NetworkStorageContract
import com.levelhard.cadentia.kit.RecentSong
import com.levelhard.cadentia.kit.ServerFilesystemContract
import com.levelhard.cadentia.kit.ServerRequestHeaders
import com.levelhard.cadentia.kit.ServerSelection
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lê a biblioteca do RoqueOS — port do `RoqueOSLibrary.swift`: os arquivos
 * do Firebase, os discos mapeados, a pasta /shared e o Google Drive. São
 * fontes com autenticações diferentes, e é por isso que existe uma camada
 * só para elas: o Firebase responde ao ID token; os discos ficam atrás do
 * roqueos-server (X-API-Key/Secret + X-Firebase-Token — sem o token o
 * servidor responde 200 com lista VAZIA); o Drive usa Bearer do Google
 * vindo de uma Cloud Function.
 */
class RoqueOSLibrary(private val account: RoqueOSAccount) {
    /** Um item navegável: pasta ou música. */
    data class Item(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val path: String,
        val source: Source,
        val sizeBytes: Long?,
        val directURL: String?,
    ) {
        sealed class Source {
            data object Firebase : Source()
            data class Storage(val server: String, val disk: String) : Source()
            data class ServerShared(val server: String) : Source()
            data object GoogleDrive : Source()
        }

        /** A Source do histórico correspondente, para as Recentes. */
        fun recentSource(): RecentSong.Source = when (source) {
            is Source.Firebase -> RecentSong.Source.RoqueOSFile(path, directURL.orEmpty())
            is Source.Storage -> RecentSong.Source.MappedDisk(source.server, source.disk, path)
            is Source.ServerShared -> RecentSong.Source.ServerShared(source.server, path)
            is Source.GoogleDrive -> RecentSong.Source.GoogleDrive(path, name)
        }
    }

    data class ServerConfig(
        val id: String,
        val name: String,
        val baseURL: String,
        val apiKey: String,
        val apiSecret: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var servers: List<ServerConfig>? = null

    private val config get() = account.config

    companion object {
        const val SHARED_ROOT = "/shared"

        /** O `/fs` serve TAMBÉM a pasta privada; só o path separa as duas. */
        fun isInsideShared(path: String): Boolean =
            path == SHARED_ROOT || path.startsWith("$SHARED_ROOT/")

        fun isAudio(name: String): Boolean =
            listOf("mp3", "m4a", "wav", "aac", "aiff", "aif", "flac", "caf", "mp4")
                .contains(name.substringAfterLast('.', "").lowercase())

        /** `alt=media` baixa o CONTEÚDO; sem ele vem JSON de metadados. */
        fun driveDownloadURL(fileID: String): String =
            "https://www.googleapis.com/drive/v3/files/$fileID?alt=media&supportsAllDrives=true"
    }

    // ---- arquivos do Firebase ----

    /** Lista uma pasta de `roqueos-files/{uid}/files` filtrando por `path`. */
    suspend fun firebaseItems(folder: String): List<Item> = withContext(Dispatchers.IO) {
        val token = account.ensureToken()
        val uid = account.userID ?: throw RoqueOSException.notConnected()

        val body = buildJsonObject {
            putJsonObject("structuredQuery") {
                putJsonArray("from") { add(buildJsonObject { put("collectionId", "files") }) }
                putJsonObject("where") {
                    putJsonObject("fieldFilter") {
                        putJsonObject("field") { put("fieldPath", "path") }
                        put("op", "EQUAL")
                        putJsonObject("value") { put("stringValue", folder) }
                    }
                }
                put("limit", 500)
            }
        }
        val rows = httpJsonArray(
            "${config.firestoreBaseURL}/roqueos-files/$uid:runQuery",
            method = "POST",
            body = body.toString(),
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
        )
        rows.mapNotNull { row ->
            val document = row.jsonObject["document"]?.jsonObject ?: return@mapNotNull null
            val name = document.firestoreString("name") ?: ""
            val isFolder = document.firestoreString("type") == "folder"
            if (!isFolder && !isAudio(name)) return@mapNotNull null
            val childPath = if (folder == "/") "/$name" else "$folder/$name"
            Item(
                id = document.text("name") ?: name,
                name = name,
                isFolder = isFolder,
                path = if (isFolder) childPath else (document.firestoreString("fullPath") ?: name),
                source = Item.Source.Firebase,
                sizeBytes = document.firestoreInteger("size"),
                directURL = document.firestoreString("downloadURL"),
            )
        }.sortedWith(itemOrder)
    }

    // ---- discos mapeados ----

    /**
     * Servidores que o usuário já configurou no RoqueOS, com a credencial
     * DE CADA UM (ServerSelection.resolveAll — a Cadentia nunca pede
     * credencial: lê o que o usuário salvou).
     */
    suspend fun loadServers(): List<ServerConfig> = withContext(Dispatchers.IO) {
        servers?.let { return@withContext it }
        val token = account.ensureToken()
        val uid = account.userID ?: throw RoqueOSException.notConnected()

        val backend = firestoreDocument("users/$uid/roqueos/backend", token)
        val credentials = firestoreDocument("users/$uid/roqueos/credentials", token)
        if (backend == null || credentials == null) {
            servers = emptyList()
            return@withContext emptyList()
        }

        val fallback = credentials.firestoreString("apiKey")?.let { key ->
            credentials.firestoreString("apiSecret")?.let { secret ->
                ServerSelection.Credentials(key, secret)
            }
        }
        val resolved = ServerSelection.resolveAll(
            services = backend.firestoreServices().map { ServerSelection.Service(it.first, it.second) },
            activeServiceID = backend.firestoreString("activeServiceId"),
            perService = credentials.firestoreCredentialsByService(),
            fallback = fallback,
            fallbackServiceID = credentials.firestoreString("activeServiceId"),
        )
        val names = backend.firestoreServiceNames()
        val found = resolved.map { item ->
            ServerConfig(
                id = item.service.id,
                name = names[item.service.id] ?: item.service.id,
                baseURL = item.service.url.trimEnd('/'),
                apiKey = item.credentials.apiKey,
                apiSecret = item.credentials.apiSecret,
            )
        }
        servers = found
        found
    }

    /** Discos de TODOS os servidores; um fora do ar não esconde os outros. */
    suspend fun storages(): List<Item> = withContext(Dispatchers.IO) {
        val servers = loadServers()
        if (servers.isEmpty()) throw RoqueOSException(0, "servers", "nenhum servidor com credencial no RoqueOS")

        val items = mutableListOf<Item>()
        val failures = mutableListOf<String>()
        for (server in servers) {
            try {
                val disks = NetworkStorageContract.disks(serverGet("/network-storage", server))
                items += disks.map { disk ->
                    val label = disk.name ?: disk.id
                    Item(
                        id = "${server.id}:${disk.id}",
                        name = if (servers.size > 1) "$label (${server.name})" else label,
                        isFolder = true,
                        path = "/",
                        source = Item.Source.Storage(server.id, disk.id),
                        sizeBytes = null,
                        directURL = null,
                    )
                }
            } catch (_: Exception) {
                failures.add(server.name)
            }
        }
        if (items.isEmpty() && failures.isNotEmpty()) {
            throw RoqueOSException(0, "servers", "servidor fora do ar: ${failures.joinToString(", ")}")
        }
        items
    }

    suspend fun storageItems(serverID: String, diskID: String, folder: String): List<Item> =
        withContext(Dispatchers.IO) {
            val server = loadServers().firstOrNull { it.id == serverID }
                ?: throw RoqueOSException(0, "server", "servidor $serverID nao esta na config")
            // ARRAY na raiz — a lição do NetworkStorageContract.
            val entries = NetworkStorageContract.entries(
                serverGet("/network-storage/$diskID/list", server, mapOf("path" to folder)),
            )
            entries
                .filter { it.isDirectory || isAudio(it.name) }
                .map { entry ->
                    // `path` vem PRONTO do servidor; montar à mão errava em subpasta.
                    val childPath = entry.path.ifEmpty {
                        if (folder == "/") "/${entry.name}" else "$folder/${entry.name}"
                    }
                    Item(
                        id = "$serverID:$diskID:$childPath",
                        name = entry.name,
                        isFolder = entry.isDirectory,
                        path = childPath,
                        source = Item.Source.Storage(serverID, diskID),
                        sizeBytes = entry.size,
                        directURL = if (entry.isDirectory) null else streamURL(diskID, childPath, server, complete = true),
                    )
                }
                .sortedWith(itemOrder)
        }

    // ---- /shared ----

    /** Uma raiz por servidor: /shared é do HOST, global, onde o RoqueOS grava o que baixa. */
    suspend fun sharedRoots(): List<Item> = withContext(Dispatchers.IO) {
        val servers = loadServers()
        if (servers.isEmpty()) throw RoqueOSException(0, "servers", "nenhum servidor com credencial no RoqueOS")
        servers.map { server ->
            Item(
                id = "shared:${server.id}",
                name = if (servers.size > 1) server.name else "shared",
                isFolder = true,
                path = SHARED_ROOT,
                source = Item.Source.ServerShared(server.id),
                sizeBytes = null,
                directURL = null,
            )
        }
    }

    suspend fun sharedItems(serverID: String, folder: String): List<Item> = withContext(Dispatchers.IO) {
        val server = loadServers().firstOrNull { it.id == serverID }
            ?: throw RoqueOSException(0, "server", "servidor $serverID nao esta na config")
        // OBJETO {"files": [...]} — a lição do ServerFilesystemContract.
        val entries = ServerFilesystemContract.entries(
            serverGet("/fs/list", server, mapOf("path" to folder)),
        )
        entries
            .filter { it.isDirectory || isAudio(it.name) }
            // Nunca sair de /shared: o mesmo endpoint serve a pasta privada.
            .filter { isInsideShared(it.path) }
            .map { entry ->
                Item(
                    id = "shared:$serverID:${entry.path}",
                    name = entry.name,
                    isFolder = entry.isDirectory,
                    path = entry.path,
                    source = Item.Source.ServerShared(serverID),
                    sizeBytes = entry.size,
                    directURL = if (entry.isDirectory) null else sharedStreamURL(entry.path, server),
                )
            }
            .sortedWith(itemOrder)
    }

    // ---- Google Drive ----

    /** O token vem da conexão que o usuário JÁ FEZ no RoqueOS; sem OAuth aqui. */
    suspend fun driveItems(folderID: String): List<Item> = withContext(Dispatchers.IO) {
        val token = driveAccessToken()
        val query = URLEncoder.encode("'$folderID' in parents and trashed = false", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$query" +
            "&fields=${URLEncoder.encode("files(id,name,mimeType,size)", "UTF-8")}" +
            "&pageSize=200&supportsAllDrives=true&includeItemsFromAllDrives=true"
        val listing = httpJson(url, headers = mapOf("Authorization" to "Bearer $token"))
        (listing["files"]?.jsonArray ?: return@withContext emptyList())
            .mapNotNull { element ->
                val file = element.jsonObject
                val id = file.text("id") ?: return@mapNotNull null
                val name = file.text("name") ?: return@mapNotNull null
                val isFolder = file.text("mimeType") == "application/vnd.google-apps.folder"
                if (!isFolder && !isAudio(name)) return@mapNotNull null
                Item(
                    id = "drive:$id",
                    name = name,
                    isFolder = isFolder,
                    path = id,
                    source = Item.Source.GoogleDrive,
                    // A API manda o tamanho como STRING.
                    sizeBytes = file.text("size")?.toLongOrNull(),
                    directURL = if (isFolder) null else driveDownloadURL(id),
                )
            }
            .sortedWith(itemOrder)
    }

    suspend fun driveAccessToken(): String = withContext(Dispatchers.IO) {
        val idToken = account.ensureToken()
        val connection = URL("${config.functionsBaseURL}/getDriveAccessToken")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write("{}".toByteArray()) }
            val status = connection.responseCode
            // 404 = nunca conectou o Drive; 410 = revogou. Ação do usuário, lá.
            if (status == 404 || status == 410) {
                throw RoqueOSException(status, "drive", "Drive nao conectado no RoqueOS")
            }
            if (status !in 200..299) throw RoqueOSException(status, "drive-token", "")
            val body = connection.inputStream.bufferedReader().readText()
            json.parseToJsonElement(body).jsonObject.text("accessToken")
                ?: throw RoqueOSException(status, "drive-token", "resposta inesperada")
        } finally {
            connection.disconnect()
        }
    }

    // ---- download ----

    /**
     * Traz a faixa para um arquivo local — as autenticações terminam aqui.
     * Integridade conferida contra Content-Range/tamanho listado: download
     * parcial gera arquivo VÁLIDO, só curto, e isso vira erro visível.
     */
    suspend fun download(item: Item, destination: File): File = withContext(Dispatchers.IO) {
        val source = item.directURL ?: throw RoqueOSException(0, "download", "sem URL")
        val connection = URL(source).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            when (val origin = item.source) {
                is Item.Source.Storage, is Item.Source.ServerShared -> {
                    val serverID = when (origin) {
                        is Item.Source.Storage -> origin.server
                        is Item.Source.ServerShared -> origin.server
                        else -> null
                    }
                    val server = serverID?.let { id -> loadServers().firstOrNull { it.id == id } }
                    if (server != null) {
                        val identity = runCatching { account.ensureToken() }.getOrNull()
                        for ((name, value) in ServerRequestHeaders.build(server.apiKey, server.apiSecret, identity)) {
                            connection.setRequestProperty(name, value)
                        }
                    }
                }
                is Item.Source.GoogleDrive ->
                    connection.setRequestProperty("Authorization", "Bearer ${driveAccessToken()}")
                is Item.Source.Firebase -> Unit // URL já assinada.
            }

            val status = connection.responseCode
            if (status !in 200..299) throw RoqueOSException(status, "download", "")
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }

            val got = destination.length()
            val expected = DownloadIntegrity.expectedBytes(
                status = status,
                contentLength = connection.getHeaderField("Content-Length"),
                contentRange = connection.getHeaderField("Content-Range"),
                listedSize = item.sizeBytes,
            )
            if (expected != null && DownloadIntegrity.isTruncated(got, expected)) {
                destination.delete()
                throw RoqueOSException(
                    status, "download",
                    "download incompleto: ${got / 1024} de ${expected / 1024} KB",
                )
            }
            destination
        } finally {
            connection.disconnect()
        }
    }

    /** Rebaixa uma música do histórico, resolvendo cada origem do seu jeito. */
    suspend fun refetch(song: RecentSong, destination: File): File {
        val item = when (val source = song.source) {
            is RecentSong.Source.RoqueOSFile -> Item(
                id = song.id, name = source.path.substringAfterLast('/'), isFolder = false,
                path = source.path, source = Item.Source.Firebase, sizeBytes = null,
                directURL = source.downloadURL,
            )
            is RecentSong.Source.MappedDisk -> {
                val server = loadServers().firstOrNull { it.id == source.serverID }
                    ?: throw RoqueOSException(0, "server", "servidor ${source.serverID} nao esta na config")
                Item(
                    id = song.id, name = source.path.substringAfterLast('/'), isFolder = false,
                    path = source.path, source = Item.Source.Storage(source.serverID, source.diskID),
                    sizeBytes = null,
                    directURL = streamURL(source.diskID, source.path, server, complete = true),
                )
            }
            is RecentSong.Source.ServerShared -> {
                val server = loadServers().firstOrNull { it.id == source.serverID }
                    ?: throw RoqueOSException(0, "server", "servidor ${source.serverID} nao esta na config")
                Item(
                    id = song.id, name = source.path.substringAfterLast('/'), isFolder = false,
                    path = source.path, source = Item.Source.ServerShared(source.serverID),
                    sizeBytes = null,
                    directURL = sharedStreamURL(source.path, server),
                )
            }
            is RecentSong.Source.GoogleDrive -> Item(
                id = song.id, name = source.name, isFolder = false, path = source.fileID,
                source = Item.Source.GoogleDrive, sizeBytes = null,
                directURL = driveDownloadURL(source.fileID),
            )
            is RecentSong.Source.Device ->
                throw RoqueOSException(0, "refetch", "arquivo local nao rebaixa por aqui")
        }
        return download(item, destination)
    }

    // ---- URLs de stream ----

    /**
     * Sempre `/stream`, nunca `/download`; e `dl=1` é OBRIGATÓRIO para
     * baixar: sem a flag o servidor responde 206 com o primeiro ~1 MB.
     */
    private fun streamURL(storageID: String, path: String, server: ServerConfig, complete: Boolean): String {
        val encoded = URLEncoder.encode(path, "UTF-8")
        val flag = if (complete) "&dl=1" else ""
        return "${server.baseURL}/network-storage/$storageID/stream?path=$encoded$flag"
    }

    private fun sharedStreamURL(path: String, server: ServerConfig): String =
        "${server.baseURL}/fs/stream?path=${URLEncoder.encode(path, "UTF-8")}&dl=1"

    // ---- HTTP ----

    private suspend fun serverGet(path: String, server: ServerConfig, query: Map<String, String> = emptyMap()): String {
        val identity = runCatching { account.ensureToken() }.getOrNull()
        val trimmed = path.trim('/')
        val suffix = if (query.isEmpty()) {
            ""
        } else {
            "?" + query.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
        }
        val url = "${server.baseURL}/$trimmed$suffix"
        check(!ServerRequestHeaders.leaksCredentials(url)) { "credencial em URL" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            // Credencial em CABEÇALHO, nunca em URL; identidade verificada junto.
            for ((name, value) in ServerRequestHeaders.build(server.apiKey, server.apiSecret, identity)) {
                connection.setRequestProperty(name, value)
            }
            val status = connection.responseCode
            if (status !in 200..299) throw RoqueOSException(status, trimmed, "")
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun httpJson(url: String, headers: Map<String, String>): JsonObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            val status = connection.responseCode
            if (status !in 200..299) throw RoqueOSException(status, "req", "")
            return json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun httpJsonArray(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): List<kotlinx.serialization.json.JsonElement> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw RoqueOSException(status, "req", "")
            val text = connection.inputStream.bufferedReader().readText()
            return json.parseToJsonElement(text).jsonArray.toList()
        } finally {
            connection.disconnect()
        }
    }

    private fun firestoreDocument(path: String, token: String): JsonObject? = runCatching {
        httpJson("${config.firestoreBaseURL}/$path", mapOf("Authorization" to "Bearer $token"))
    }.getOrNull()

    private val itemOrder = compareBy<Item>({ if (it.isFolder) 0 else 1 }, { it.name.lowercase() })
}

// ---- leitura do Firestore dinâmico ----

internal fun JsonObject.firestoreInteger(key: String): Long? =
    this["fields"]?.jsonObject?.get(key)?.jsonObject?.text("integerValue")?.toLongOrNull()

/** `services` do documento backend: pares (id, url). */
internal fun JsonObject.firestoreServices(): List<Pair<String, String>> =
    this["fields"]?.jsonObject?.get("services")?.jsonObject?.get("arrayValue")?.jsonObject
        ?.get("values")?.jsonArray?.mapNotNull { value ->
            val map = value.jsonObject["mapValue"]?.jsonObject?.get("fields")?.jsonObject
                ?: return@mapNotNull null
            val id = map["id"]?.jsonObject?.text("stringValue") ?: return@mapNotNull null
            val url = map["url"]?.jsonObject?.text("stringValue") ?: return@mapNotNull null
            id to url
        } ?: emptyList()

internal fun JsonObject.firestoreServiceNames(): Map<String, String> =
    this["fields"]?.jsonObject?.get("services")?.jsonObject?.get("arrayValue")?.jsonObject
        ?.get("values")?.jsonArray?.mapNotNull { value ->
            val map = value.jsonObject["mapValue"]?.jsonObject?.get("fields")?.jsonObject
                ?: return@mapNotNull null
            val id = map["id"]?.jsonObject?.text("stringValue") ?: return@mapNotNull null
            id to (map["name"]?.jsonObject?.text("stringValue") ?: id)
        }?.toMap() ?: emptyMap()

/** `credentialsByServiceId`: a fonte AUTORITATIVA de credencial por servidor. */
internal fun JsonObject.firestoreCredentialsByService(): Map<String, ServerSelection.Credentials> =
    this["fields"]?.jsonObject?.get("credentialsByServiceId")?.jsonObject?.get("mapValue")
        ?.jsonObject?.get("fields")?.jsonObject?.mapNotNull { (serviceID, value) ->
            val inner = value.jsonObject["mapValue"]?.jsonObject?.get("fields")?.jsonObject
                ?: return@mapNotNull null
            val key = inner["apiKey"]?.jsonObject?.text("stringValue") ?: return@mapNotNull null
            val secret = inner["apiSecret"]?.jsonObject?.text("stringValue") ?: return@mapNotNull null
            serviceID to ServerSelection.Credentials(key, secret)
        }?.toMap() ?: emptyMap()
