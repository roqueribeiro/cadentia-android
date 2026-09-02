package com.levelhard.cadentia

import android.app.Application
import android.content.ComponentCallbacks2
import com.levelhard.cadentia.audio.SampleInstall
import com.levelhard.cadentia.kit.SampleBank

class CadentiaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Os bancos de sample entram aqui, antes de qualquer tela existir
        // (o `CadentiaApp.swift` faz o mesmo). Pack ausente não é erro: o app
        // volta a tocar com síntese sozinho. NÃO se aquece nada aqui — a
        // lição do iOS foi 565 MB decodificados para sobrar órgão e nylon.
        SampleInstall.install(this)
    }

    /**
     * Aviso de memória do sistema: o PCM decodificado é a coisa mais cara e
     * a mais barata de reconstruir — os arquivos continuam no disco, e a
     * próxima nota decodifica de novo. Segurar 96 MB enquanto o sistema pede
     * espaço é o caminho curto para ser encerrado.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) SampleBank.shared.purge()
    }
}
