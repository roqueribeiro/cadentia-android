package com.levelhard.cadentia

import android.app.Application
import android.content.ComponentCallbacks2
import com.levelhard.cadentia.audio.SampleInstall
import com.levelhard.cadentia.kit.SampleBank

class CadentiaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Os bancos de sample entram aqui, antes de qualquer tela existir
        // (o `CadentiaApp.swift` faz o mesmo). NÃO se aquece nada aqui — a
        // lição do iOS foi 565 MB decodificados para sobrar órgão e nylon.
        //
        // Os packs vêm dentro do pacote. Na primeira abertura de cada versão
        // os 38 MB saem dos assets para `filesDir/samples` numa thread de IO
        // (cópia de ~1 s que não pode segurar o primeiro quadro); enquanto
        // isso o app toca com síntese e troca para sample na nota seguinte à
        // instalação. Nas aberturas seguintes é só ler os manifestos.
        if (SampleInstall.isBundledInstalled(this)) {
            SampleInstall.install(this)
        } else {
            Thread({
                SampleInstall.copyBundled(this)
                SampleInstall.install(this)
            }, "cadentia-samples-install").apply { isDaemon = true }.start()
        }
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
