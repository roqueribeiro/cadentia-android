package com.levelhard.cadentia.kit

import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port 1:1 do `StemMixTests.swift`. */
class StemMixTest {
    private fun tracks(
        muted: Set<String> = emptySet(),
        soloed: Set<String> = emptySet(),
        volume: Float = 1f,
    ): List<StemMix.State> = listOf("drums", "bass", "other", "vocals").map {
        StemMix.State(id = it, volume = volume, isMuted = it in muted, isSoloed = it in soloed)
    }

    @Test fun everythingPlaysByDefault() {
        assertTrue(StemMix.gains(tracks()).values.all { it == 1f })
    }

    @Test fun mutingSilencesOnlyThatTrack() {
        val gains = StemMix.gains(tracks(muted = setOf("drums")))
        assertEquals(0f, gains["drums"])
        assertEquals(1f, gains["bass"])
        assertEquals(1f, gains["other"])
        assertEquals(1f, gains["vocals"])
    }

    /** O ponto inteiro do solo: todo o resto cala. */
    @Test fun soloSilencesEverythingElse() {
        val gains = StemMix.gains(tracks(soloed = setOf("bass")))
        assertEquals(1f, gains["bass"])
        assertEquals(0f, gains["drums"])
        assertEquals(0f, gains["other"])
        assertEquals(0f, gains["vocals"])
    }

    /** Solo numa faixa mutada tem que soar, senão o usuário ouve silêncio inexplicável. */
    @Test fun soloBeatsMuteOnTheSameTrack() {
        val gains = StemMix.gains(tracks(muted = setOf("bass"), soloed = setOf("bass")))
        assertEquals(1f, gains["bass"])
        assertEquals(0f, gains["drums"])
    }

    @Test fun severalSoloesPlayTogether() {
        val gains = StemMix.gains(tracks(soloed = setOf("bass", "drums")))
        assertEquals(1f, gains["bass"])
        assertEquals(1f, gains["drums"])
        assertEquals(0f, gains["other"])
        assertEquals(0f, gains["vocals"])
    }

    /** Mutar preserva a posição do fader; desmutar volta ao nível escolhido. */
    @Test fun volumeSurvivesMuting() {
        val states = tracks(volume = 0.4f).toMutableList()
        states[0] = states[0].copy(isMuted = true)
        assertEquals(0f, StemMix.gains(states)["drums"])
        states[0] = states[0].copy(isMuted = false)
        assertEquals(0.4f, StemMix.gains(states)["drums"])
    }
}

/** Port 1:1 do `StemMixMemoryTests` (morava no PracticeLoopTests do iOS). */
class StemMixMemoryTest {
    private val json = Json

    private fun snapshot(speed: Double = 0.8) = StemMixSnapshot(
        volumes = mapOf("vocals" to 0.4f), muted = setOf("bass"), semitones = -2, speed = speed,
    )

    @Test fun roundTripsThroughJson() {
        val memory = StemMixMemory()
        memory.remember(snapshot(), "song-a")
        val back = json.decodeFromString(
            StemMixMemory.serializer(),
            json.encodeToString(StemMixMemory.serializer(), memory),
        )
        assertEquals(snapshot(), back.snapshot("song-a"))
    }

    /** Voltar tudo ao neutro APAGA a entrada: "desfiz meus ajustes" também é decisão. */
    @Test fun neutralSnapshotErasesTheEntry() {
        val memory = StemMixMemory()
        memory.remember(snapshot(), "song-a")
        memory.remember(StemMixSnapshot(volumes = mapOf("vocals" to 1f)), "song-a")
        assertNull(memory.snapshot("song-a"))
        assertEquals(0, memory.count)
    }

    @Test fun capacityEvictsTheOldest() {
        val memory = StemMixMemory()
        for (index in 0..StemMixMemory.CAPACITY) {
            memory.remember(snapshot(), "song-$index", atEpochMillis = index.toLong())
        }
        assertEquals(StemMixMemory.CAPACITY, memory.count)
        assertNull("a mais antiga sai", memory.snapshot("song-0"))
        assertNotNull(memory.snapshot("song-${StemMixMemory.CAPACITY}"))
    }

    @Test fun loopSurvivesTheRoundTrip() {
        val memory = StemMixMemory()
        val snap = snapshot().copy(loop = PracticeLoop.of(12.0, 18.0))
        memory.remember(snap, "song-a")
        val back = json.decodeFromString(
            StemMixMemory.serializer(),
            json.encodeToString(StemMixMemory.serializer(), memory),
        )
        assertEquals(PracticeLoop.of(12.0, 18.0), back.snapshot("song-a")?.loop)
    }
}

/** Port 1:1 do `RecentSongsTests.swift`. */
class RecentSongsTest {
    private fun song(path: String, title: String? = null, atSeconds: Long = 0) = RecentSong(
        title = title ?: path,
        source = RecentSong.Source.RoqueOSFile(path = path, downloadURL = "https://exemplo/$path"),
        lastOpenedEpochMillis = atSeconds * 1000,
    )

    @Test fun mostRecentComesFirst() {
        val recent = RecentSongs()
        recent.remember(song("a.mp3"))
        recent.remember(song("b.mp3"))
        assertEquals(listOf("b.mp3", "a.mp3"), recent.songs.map { it.title })
    }

    /** Reabrir MOVE para o topo em vez de duplicar. */
    @Test fun reopeningMovesInsteadOfDuplicating() {
        val recent = RecentSongs()
        recent.remember(song("a.mp3"))
        recent.remember(song("b.mp3"))
        recent.remember(song("a.mp3", atSeconds = 100))
        assertEquals(2, recent.songs.size)
        assertEquals("a.mp3", recent.songs.first().title)
        assertEquals(100_000L, recent.songs.first().lastOpenedEpochMillis)
    }

    @Test fun theListDoesNotGrowForever() {
        val recent = RecentSongs()
        for (index in 0..RecentSongs.LIMIT + 10) {
            recent.remember(song("faixa-$index.mp3"))
        }
        assertEquals(RecentSongs.LIMIT, recent.songs.size)
        assertEquals("faixa-${RecentSongs.LIMIT + 10}.mp3", recent.songs.first().title)
        assertFalse(recent.songs.any { it.title == "faixa-0.mp3" })
    }

    /** A identidade vem da origem, não do momento. */
    @Test fun identityDependsOnlyOnWhereTheSongLives() {
        assertEquals(song("rock/musica.mp3", atSeconds = 0).id, song("rock/musica.mp3", atSeconds = 99999).id)
        assertNotEquals(song("rock/musica.mp3").id, song("jazz/musica.mp3").id)
    }

    @Test fun differentSourcesNeverCollide() {
        val arquivo = RecentSong(
            title = "x", source = RecentSong.Source.RoqueOSFile("/x.mp3", "u"), lastOpenedEpochMillis = 0,
        )
        val disco = RecentSong(
            title = "x", source = RecentSong.Source.MappedDisk("s1", "d1", "/x.mp3"), lastOpenedEpochMillis = 0,
        )
        val aparelho = RecentSong(
            title = "x", source = RecentSong.Source.Device("", "x.mp3"), lastOpenedEpochMillis = 0,
        )
        assertEquals(3, setOf(arquivo.id, disco.id, aparelho.id).size)
    }

    /** A chave vira nome de pasta: só letra e número. */
    @Test fun identifierIsSafeAsAFolderName() {
        val id = song("pasta com espaço/sub/mú\$ica #1.mp3").id
        assertTrue(id.isNotEmpty())
        assertTrue(id.all { it.isLetterOrDigit() })
    }

    /** O mesmo disco em servidores diferentes é música diferente. */
    @Test fun sameDiskOnDifferentServersDoesNotCollide() {
        val casa = RecentSong(
            title = "x", source = RecentSong.Source.MappedDisk("casa", "d1", "/x.mp3"), lastOpenedEpochMillis = 0,
        )
        val nuvem = RecentSong(
            title = "x", source = RecentSong.Source.MappedDisk("nuvem", "d1", "/x.mp3"), lastOpenedEpochMillis = 0,
        )
        assertNotEquals(casa.id, nuvem.id)
    }

    @Test fun forgettingRemovesOnlyThatSong() {
        val recent = RecentSongs()
        recent.remember(song("a.mp3"))
        recent.remember(song("b.mp3"))
        recent.forget(song("a.mp3").id)
        assertEquals(listOf("b.mp3"), recent.songs.map { it.title })
    }

    @Test fun survivesEncodingRoundTrip() {
        val recent = RecentSongs()
        recent.remember(song("a.mp3"))
        recent.remember(
            RecentSong(
                title = "local", source = RecentSong.Source.Device("content://doc/1", "local.wav"),
                lastOpenedEpochMillis = 42_000,
            ),
        )
        recent.remember(
            RecentSong(
                title = "disco", source = RecentSong.Source.MappedDisk("s", "d", "/p.mp3"),
                lastOpenedEpochMillis = 7_000,
            ),
        )
        val json = Json
        val back = json.decodeFromString(
            RecentSongs.serializer(),
            json.encodeToString(RecentSongs.serializer(), recent),
        )
        assertEquals(recent, back)
    }
}

/** Port 1:1 do `SetlistsTests.swift`. */
class SetlistsTest {
    private fun song(name: String) = RecentSong(
        title = name,
        source = RecentSong.Source.Device("", "$name.mp3"),
        lastOpenedEpochMillis = 0,
    )

    @Test fun createsOnTopAndKeepsSongOrder() {
        val lists = Setlists()
        val show = checkNotNull(lists.create("Show de sábado"))
        lists.create("Ensaio")
        assertEquals(listOf("Ensaio", "Show de sábado"), lists.lists.map { it.name })

        lists.add(song("Abertura"), show.id)
        lists.add(song("Balada"), show.id)
        lists.add(song("Encerramento"), show.id)
        assertEquals(
            "a ordem do set é a ordem do show",
            listOf("Abertura", "Balada", "Encerramento"),
            lists.lists.first { it.id == show.id }.songs.map { it.title },
        )
    }

    @Test fun rejectsBlankNamesAndDuplicateSongs() {
        val lists = Setlists()
        assertNull(lists.create("   "))
        val show = checkNotNull(lists.create("Show"))
        lists.add(song("Mesma"), show.id)
        lists.add(song("Mesma"), show.id)
        assertEquals("tocar duas vezes no adicionar não duplica", 1, lists.lists[0].songs.size)
    }

    /** Duplicar dá id novo com as MESMAS músicas; mexer na cópia não mexe no original. */
    @Test fun duplicateSharesSongsButNotIdentity() {
        val lists = Setlists()
        val original = checkNotNull(lists.create("Banda A"))
        lists.add(song("Um"), original.id)
        lists.add(song("Dois"), original.id)

        val copy = checkNotNull(lists.duplicate(original.id, "Show de sexta"))
        assertNotEquals(original.id, copy.id)
        assertEquals("Show de sexta", copy.name)
        assertEquals(
            lists.lists.first { it.id == original.id }.songs.map { it.id },
            copy.songs.map { it.id },
        )

        lists.removeSong(copy.songs[0].id, copy.id)
        assertEquals(2, lists.lists.first { it.id == original.id }.songs.size)
        assertEquals(1, lists.lists.first { it.id == copy.id }.songs.size)
    }

    @Test fun renameAndDeleteWork() {
        val lists = Setlists()
        val show = checkNotNull(lists.create("Rascunho"))
        lists.rename(show.id, "Show do bar")
        assertEquals("Show do bar", lists.lists[0].name)
        lists.rename(show.id, "  ")
        assertEquals("nome vazio não apaga o atual", "Show do bar", lists.lists[0].name)
        lists.delete(show.id)
        assertTrue(lists.lists.isEmpty())
    }

    @Test fun capsAreEnforced() {
        val lists = Setlists()
        for (index in 0 until Setlists.MAX_LISTS + 5) lists.create("Lista $index")
        assertEquals(Setlists.MAX_LISTS, lists.lists.size)

        val one = Setlists()
        val show = checkNotNull(one.create("Cheio"))
        for (index in 0 until Setlists.MAX_SONGS + 5) one.add(song("m$index"), show.id)
        assertEquals(Setlists.MAX_SONGS, one.lists[0].songs.size)
    }

    /** O repertório NÃO depende das Recentes: mais músicas que o teto delas, intactas. */
    @Test fun survivesEncodingIndependentlyOfRecents() {
        val lists = Setlists()
        val show = checkNotNull(lists.create("Persistente"))
        for (index in 0 until 40) lists.add(song("faixa $index"), show.id)

        val json = Json
        val back = json.decodeFromString(
            Setlists.serializer(),
            json.encodeToString(Setlists.serializer(), lists),
        )
        assertEquals(lists, back)
        assertEquals(40, back.lists[0].songs.size)
    }
}

/** Port do `SongSearchTests`. */
class SongSearchTest {
    private fun song(title: String) = RecentSong(
        title = title, source = RecentSong.Source.Device("", "$title.mp3"), lastOpenedEpochMillis = 0,
    )

    @Test fun emptyQueryReturnsEverything() {
        val songs = listOf(song("Uma"), song("Outra"))
        assertEquals(2, SongSearch.filter(songs, "").size)
        assertEquals(2, SongSearch.filter(songs, "   ").size)
    }

    /** Ninguém digita acento no meio do ensaio. "agua" TEM que achar "Água". */
    @Test fun ignoresCaseAndDiacritics() {
        val songs = listOf(song("Água de Beber"), song("Coração"), song("Samba"))
        assertEquals(listOf("Água de Beber"), SongSearch.filter(songs, "agua").map { it.title })
        assertEquals(listOf("Coração"), SongSearch.filter(songs, "CORACAO").map { it.title })
    }

    @Test fun matchesAnywhereInTheTitle() {
        val songs = listOf(song("Cadentia Demo"), song("Cadentia Demo 2"))
        assertEquals(listOf("Cadentia Demo 2"), SongSearch.filter(songs, "2").map { it.title })
        assertEquals(2, SongSearch.filter(songs, "demo").size)
    }

    @Test fun noMatchIsEmptyNotEverything() {
        assertTrue(SongSearch.filter(listOf(song("Uma")), "zzz").isEmpty())
    }
}

/** Port 1:1 do `SetQueueTests.swift`. */
class SetQueueTest {
    private fun list(count: Int): Setlist {
        val lists = Setlists()
        val created = checkNotNull(lists.create("Show"))
        for (index in 0 until count) {
            lists.add(
                RecentSong(
                    title = "m$index",
                    source = RecentSong.Source.Device("", "m$index.mp3"),
                    lastOpenedEpochMillis = 0,
                ),
                created.id,
            )
        }
        return lists.lists[0]
    }

    @Test fun orderedPlaysTheSetInShowOrderAndEnds() {
        val queue = checkNotNull(SetQueue.of(list(3), SetQueue.Mode.Ordered))
        assertEquals("m0", queue.current?.title)
        assertEquals("m1", queue.advance()?.title)
        assertEquals("m2", queue.advance()?.title)
        assertNull("o fim do set é fim, não recomeço", queue.advance())
        assertEquals(3 to 3, queue.position)
    }

    @Test fun startingMidSetMeansTheShowStartsThere() {
        val setlist = list(4)
        val third = setlist.songs[2]
        val queue = checkNotNull(SetQueue.of(setlist, SetQueue.Mode.Ordered, startAt = third.id))
        assertEquals("m2", queue.current?.title)
        assertEquals(3 to 4, queue.position)
        assertEquals("voltar alcança o que ficou antes do ponto de partida", "m1", queue.goBack()?.title)
    }

    /** O aleatório é permutação: toca cada música exatamente uma vez. */
    @Test fun shuffleIsAPermutationNotADiceRoll() {
        val setlist = list(8)
        val queue = checkNotNull(SetQueue.of(setlist, SetQueue.Mode.Shuffled, random = Random(42)))
        val played = mutableListOf(checkNotNull(queue.current).title)
        while (true) played.add((queue.advance() ?: break).title)
        assertEquals(8, played.size)
        assertEquals(setlist.songs.map { it.title }.toSet(), played.toSet())
    }

    @Test fun shuffleStartingFromASongPutsItFirst() {
        val setlist = list(6)
        val chosen = setlist.songs[4]
        val queue = checkNotNull(
            SetQueue.of(setlist, SetQueue.Mode.Shuffled, startAt = chosen.id, random = Random(42)),
        )
        assertEquals(chosen.id, queue.current?.id)
        assertEquals(setlist.songs.map { it.id }.toSet(), queue.order.map { it.id }.toSet())
    }

    @Test fun emptySetlistHasNoQueue() {
        val lists = Setlists()
        val created = checkNotNull(lists.create("Vazio"))
        assertNull(SetQueue.of(created, SetQueue.Mode.Ordered))
    }

    @Test fun goBackStopsAtTheFirstSong() {
        val queue = checkNotNull(SetQueue.of(list(2), SetQueue.Mode.Ordered))
        assertNull(queue.goBack())
        queue.advance()
        assertEquals("m0", queue.goBack()?.title)
    }
}
