package com.levelhard.cadentia.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port 1:1 do `NetworkStorageContractTests.swift`. */
class NetworkStorageContractTest {
    /** JSON no formato exato que o servidor devolve. */
    private val listing = """
    [
      {"name":"Rock","path":"/Rock","isDirectory":true,"isFile":false,"modified":"2026-01-01T00:00:00Z"},
      {"name":"musica.mp3","path":"/Rock/musica.mp3","isDirectory":false,"isFile":true,
       "size":8123456,"modified":"2026-01-02T00:00:00Z","permissions":"rw-"},
      {"name":"sem-tamanho.wav","path":"/sem-tamanho.wav","isDirectory":false,"isFile":true}
    ]
    """.trimIndent()

    @Test fun readsTheListingAsATopLevelArray() {
        val entries = NetworkStorageContract.entries(listing)
        assertEquals(3, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("Rock", entries[0].name)
        assertEquals(8123456L, entries[1].size)
    }

    /** O caminho vem pronto do servidor; montar à mão dava caminho errado em subpasta. */
    @Test fun usesThePathTheServerSends() {
        assertEquals("/Rock/musica.mp3", NetworkStorageContract.entries(listing)[1].path)
    }

    @Test fun missingOptionalFieldsAreFine() {
        val entries = NetworkStorageContract.entries(listing)
        assertNull(entries[2].size)
        assertNull(entries[2].modified)
    }

    /** O bug virado teste: objeto embrulhando a lista NÃO é o contrato. */
    @Test fun aWrappedObjectIsNotTheContract() {
        val wrapped = """{"entries":[{"name":"x","path":"/x","isDirectory":false,"isFile":true}]}"""
        assertThrows(Exception::class.java) { NetworkStorageContract.entries(wrapped) }
    }

    @Test fun readsTheDiskList() {
        val json = """
        [
          {"id":"st-1","userId":"u1","name":"Disco de Musica","type":"local",
           "path":"/data/musica","readOnly":false,"autoMount":true,"icon":"folder"},
          {"id":"st-2","userId":"u1","name":"NAS","type":"smb","hostname":"nas.local",
           "port":445,"path":"/share","password":"********","readOnly":true,
           "autoMount":false,"icon":"externaldrive"}
        ]
        """.trimIndent()
        val disks = NetworkStorageContract.disks(json)
        assertEquals(2, disks.size)
        assertEquals("Disco de Musica", disks[0].name)
        assertEquals("st-2", disks[1].id)
    }

    /** Um campo novo no servidor não pode derrubar o app. */
    @Test fun unknownFieldsAreIgnored() {
        val json = """[{"id":"a","name":"X","campoNovoDoServidor":123,"outro":{"a":1}}]"""
        assertEquals("a", NetworkStorageContract.disks(json).first().id)
    }
}

/** Port 1:1 do `ServerFilesystemContractTests.swift`. */
class ServerFilesystemContractTest {
    /** O formato exato do `return { files }` do filesystem.controller.ts. */
    private val listing = """
    {
      "files": [
        {"name":"Music","path":"/shared/Music","isDirectory":true,"isFile":false,
         "modified":"2026-01-01T00:00:00Z"},
        {"name":"faixa.mp3","path":"/shared/Music/faixa.mp3","isDirectory":false,"isFile":true,
         "size":8123456,"modified":"2026-01-02T00:00:00Z","permissions":"rw-rw-rw-"},
        {"name":"sem-tamanho.wav","path":"/shared/sem-tamanho.wav","isDirectory":false,"isFile":true}
      ]
    }
    """.trimIndent()

    @Test fun readsTheListingFromTheFilesEnvelope() {
        val entries = ServerFilesystemContract.entries(listing)
        assertEquals(3, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("Music", entries[0].name)
        assertEquals("/shared/Music", entries[0].path)
        assertEquals(8123456L, entries[1].size)
        assertNull(entries[2].size)
    }

    @Test fun campoNovoNoServidorNaoQuebraOApp() {
        val comExtras = """
        {"files":[{"name":"a.mp3","path":"/shared/a.mp3","isDirectory":false,"isFile":true,
                   "size":1,"owner":"roqueos","inode":42}]}
        """.trimIndent()
        assertEquals(1, ServerFilesystemContract.entries(comExtras).size)
    }

    /** Se alguém "simplificar" reusando o contrato dos discos, aparece aqui. */
    @Test fun arrayNaRaizNaoEAceito() {
        val comoOsDiscosRespondem = """[{"name":"a.mp3","path":"/a.mp3","isDirectory":false,"isFile":true}]"""
        assertThrows(Exception::class.java) { ServerFilesystemContract.entries(comoOsDiscosRespondem) }
    }

    @Test fun listaVaziaEValida() {
        assertTrue(ServerFilesystemContract.entries("""{"files":[]}""").isEmpty())
    }
}

/** Port 1:1 do `ServerRequestHeadersTests.swift`. */
class ServerRequestHeadersTest {
    @Test fun carriesCredentialsAndVerifiedIdentity() {
        val headers = ServerRequestHeaders.build("k", "s", "eyJhbGciOi.token.aqui")
        assertEquals("k", headers[ServerRequestHeaders.API_KEY])
        assertEquals("s", headers[ServerRequestHeaders.API_SECRET])
        assertEquals("eyJhbGciOi.token.aqui", headers[ServerRequestHeaders.IDENTITY])
    }

    /** O nome do cabeçalho é o que o servidor lê. */
    @Test fun theIdentityHeaderHasTheNameTheServerReads() {
        assertEquals("X-Firebase-Token", ServerRequestHeaders.IDENTITY)
        assertEquals("X-API-Key", ServerRequestHeaders.API_KEY)
        assertEquals("X-API-Secret", ServerRequestHeaders.API_SECRET)
    }

    @Test fun withoutATokenOnlyCredentialsGo() {
        val headers = ServerRequestHeaders.build("k", "s", null)
        assertNull(headers[ServerRequestHeaders.IDENTITY])
        assertEquals(2, headers.size)
    }

    @Test fun anEmptyTokenIsNotAToken() {
        assertNull(ServerRequestHeaders.build("k", "s", "")[ServerRequestHeaders.IDENTITY])
    }

    /** Credencial em URL vaza para log de proxy, histórico e Referer. */
    @Test fun credentialsNeverBelongInAURL() {
        assertTrue(
            ServerRequestHeaders.leaksCredentials(
                "https://s.example/network-storage?apiKey=abc&apiSecret=def",
            ),
        )
        assertTrue(ServerRequestHeaders.leaksCredentials("https://s.example/x?api_key=abc"))
        assertFalse(
            ServerRequestHeaders.leaksCredentials(
                "https://s.example/network-storage/d1/stream?path=/Rock/musica.mp3",
            ),
        )
    }
}

/** Port 1:1 do `SessionDurabilityTests.swift`. */
class SessionDurabilityTest {
    @Test fun revokedCredentialsEndTheSession() {
        for (reason in SessionDurability.revokedReasons) {
            assertTrue("$reason tem que derrubar", SessionDurability.isRevoked(reason))
        }
    }

    /** O coração da regra: nada disso pode desconectar o usuário. */
    @Test fun transientTroubleKeepsTheSession() {
        val transient = listOf(
            "",
            "INTERNAL_ERROR",
            "QUOTA_EXCEEDED",
            "The Internet connection appears to be offline.",
            "The request timed out.",
            "502 Bad Gateway",
            "A server with the specified hostname could not be found.",
            "cancelled",
            "Too Many Requests",
        )
        for (reason in transient) {
            assertFalse("$reason NAO pode derrubar a sessao", SessionDurability.isRevoked(reason))
        }
    }

    /** Mensagem vaga nunca é revogação: na dúvida, manter conectado. */
    @Test fun vagueFailuresDefaultToKeepingTheSession() {
        assertFalse(SessionDurability.isRevoked("error"))
        assertFalse(SessionDurability.isRevoked("failed"))
        assertFalse(SessionDurability.isRevoked("unknown"))
        assertFalse(SessionDurability.isRevoked("EXPIRED"))
    }
}

/** Port 1:1 do `DownloadIntegrityTests.swift`. */
class DownloadIntegrityTest {
    /** O caso real: 206 com o primeiro ~1 MB de uma música de 5 MB. */
    @Test fun detectsThePartialChunkFromStream() {
        val expected = DownloadIntegrity.expectedBytes(
            status = 206,
            contentLength = "1048576",
            contentRange = "bytes 0-1048575/5242880",
            listedSize = null,
        )
        assertEquals(5_242_880L, expected)
        assertTrue(DownloadIntegrity.isTruncated(got = 1_048_576, expected = 5_242_880))
    }

    /** Num 206 sem Content-Range, o Content-Length mede só o pedaço. */
    @Test fun aPartialResponseNeverTrustsContentLengthAlone() {
        assertNull(
            DownloadIntegrity.expectedBytes(
                status = 206, contentLength = "1048576", contentRange = null, listedSize = null,
            ),
        )
    }

    @Test fun fallsBackToTheSizeTheListingReported() {
        assertEquals(
            5_242_880L,
            DownloadIntegrity.expectedBytes(
                status = 206, contentLength = "1048576", contentRange = null, listedSize = 5_242_880,
            ),
        )
    }

    @Test fun aCompleteDownloadPasses() {
        assertFalse(DownloadIntegrity.isTruncated(got = 5_242_880, expected = 5_242_880))
        assertEquals(
            5_242_880L,
            DownloadIntegrity.expectedBytes(
                status = 200, contentLength = "5242880", contentRange = null, listedSize = null,
            ),
        )
    }

    @Test fun toleratesATinyDifference() {
        assertFalse(DownloadIntegrity.isTruncated(got = 5_242_878, expected = 5_242_880))
    }

    /** Sem informação nenhuma não se acusa nada. */
    @Test fun withoutAnyInformationNothingIsClaimed() {
        assertNull(
            DownloadIntegrity.expectedBytes(
                status = 200, contentLength = null, contentRange = null, listedSize = null,
            ),
        )
    }

    @Test fun handlesUnknownTotalInContentRange() {
        assertNull(DownloadIntegrity.totalFromContentRange("bytes 0-1023/*"))
        assertNull(DownloadIntegrity.totalFromContentRange(null))
        assertNull(DownloadIntegrity.totalFromContentRange("lixo"))
        assertEquals(2048L, DownloadIntegrity.totalFromContentRange("bytes 0-1023/2048"))
    }
}

/** Port 1:1 do `ServerSelectionTests.swift`. */
class ServerSelectionTest {
    private val casa = ServerSelection.Service("casa", "https://casa.local")
    private val nuvem = ServerSelection.Service("nuvem", "https://nuvem.example")
    private val chaveCasa = ServerSelection.Credentials("k-casa", "s-casa")
    private val chaveNuvem = ServerSelection.Credentials("k-nuvem", "s-nuvem")

    @Test fun usesTheCredentialsOfTheActiveServer() {
        val resolved = ServerSelection.resolve(
            services = listOf(casa, nuvem),
            activeServiceID = "nuvem",
            perService = mapOf("casa" to chaveCasa, "nuvem" to chaveNuvem),
            fallback = chaveCasa,
            fallbackServiceID = "casa",
        )
        assertEquals(nuvem, resolved.service)
        assertEquals(chaveNuvem, resolved.credentials)
    }

    /** O coração do bug: nunca parear a URL de um com a chave de outro. */
    @Test fun neverPairsAServerWithAnotherServersKey() {
        val thrown = assertThrows(ServerSelection.Problem.NoCredentialsForActiveServer::class.java) {
            ServerSelection.resolve(
                services = listOf(casa, nuvem),
                activeServiceID = "nuvem",
                perService = emptyMap(),
                fallback = chaveCasa,
                fallbackServiceID = "casa",
            )
        }
        assertEquals("nuvem", thrown.serviceID)
    }

    @Test fun acceptsTheLegacyFieldsWhenTheyBelongToThisServer() {
        val resolved = ServerSelection.resolve(
            services = listOf(casa, nuvem),
            activeServiceID = "casa",
            perService = emptyMap(),
            fallback = chaveCasa,
            fallbackServiceID = "casa",
        )
        assertEquals(chaveCasa, resolved.credentials)
    }

    /** Quem nunca teve dois servidores precisa funcionar sem mapa nem id. */
    @Test fun aSingleServerWithLegacyCredentialsWorks() {
        val resolved = ServerSelection.resolve(
            services = listOf(casa),
            activeServiceID = null,
            perService = emptyMap(),
            fallback = chaveCasa,
            fallbackServiceID = null,
        )
        assertEquals(casa, resolved.service)
        assertEquals(chaveCasa, resolved.credentials)
    }

    /** Com vários servidores e nenhum ativo, escolher no chute daria 401 confuso. */
    @Test fun refusesToGuessAmongSeveralServers() {
        assertThrows(ServerSelection.Problem.NoServerConfigured::class.java) {
            ServerSelection.resolve(
                services = listOf(casa, nuvem), activeServiceID = null,
                perService = emptyMap(), fallback = chaveCasa, fallbackServiceID = null,
            )
        }
    }

    @Test fun emptyCredentialsAreNotCredentials() {
        assertThrows(ServerSelection.Problem.NoCredentialsForActiveServer::class.java) {
            ServerSelection.resolve(
                services = listOf(casa), activeServiceID = "casa",
                perService = mapOf("casa" to ServerSelection.Credentials("", "")),
                fallback = null, fallbackServiceID = null,
            )
        }
    }

    @Test fun listsEveryUsableServer() {
        val all = ServerSelection.resolveAll(
            services = listOf(casa, nuvem),
            activeServiceID = "nuvem",
            perService = mapOf("casa" to chaveCasa, "nuvem" to chaveNuvem),
            fallback = null, fallbackServiceID = null,
        )
        assertEquals(2, all.size)
        // O ativo vem primeiro: é o do dia a dia.
        assertEquals(nuvem, all.first().service)
    }

    /** Cada servidor leva a SUA credencial, mesmo listando vários juntos. */
    @Test fun eachServerKeepsItsOwnKey() {
        val all = ServerSelection.resolveAll(
            services = listOf(casa, nuvem), activeServiceID = null,
            perService = mapOf("casa" to chaveCasa, "nuvem" to chaveNuvem),
            fallback = null, fallbackServiceID = null,
        )
        val porId = all.associate { it.service.id to it.credentials }
        assertEquals(chaveCasa, porId["casa"])
        assertEquals(chaveNuvem, porId["nuvem"])
    }

    /** Servidor sem credencial utilizável é omitido em silêncio. */
    @Test fun aServerWithoutCredentialsIsLeftOut() {
        val all = ServerSelection.resolveAll(
            services = listOf(casa, nuvem), activeServiceID = "casa",
            perService = mapOf("casa" to chaveCasa),
            fallback = null, fallbackServiceID = null,
        )
        assertEquals(1, all.size)
        assertEquals(casa, all.first().service)
    }

    @Test fun nothingConfiguredGivesAnEmptyList() {
        assertTrue(
            ServerSelection.resolveAll(
                services = emptyList(), activeServiceID = null, perService = emptyMap(),
                fallback = null, fallbackServiceID = null,
            ).isEmpty(),
        )
    }

    @Test fun serverWithoutURLIsNotAServer() {
        assertThrows(ServerSelection.Problem.NoServerConfigured::class.java) {
            ServerSelection.resolve(
                services = listOf(ServerSelection.Service("vazio", "")), activeServiceID = "vazio",
                perService = mapOf("vazio" to chaveCasa), fallback = null, fallbackServiceID = null,
            )
        }
    }

    /** Ativo apontando para id apagado não vira escolha silenciosa de outro. */
    @Test fun staleActiveIDDoesNotSilentlyPickAnother() {
        assertThrows(ServerSelection.Problem.NoServerConfigured::class.java) {
            ServerSelection.resolve(
                services = listOf(casa, nuvem), activeServiceID = "apagado",
                perService = mapOf("casa" to chaveCasa), fallback = null, fallbackServiceID = null,
            )
        }
    }
}
