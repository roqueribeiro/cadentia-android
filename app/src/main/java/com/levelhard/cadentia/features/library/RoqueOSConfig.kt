package com.levelhard.cadentia.features.library

import android.content.Context
import java.util.Properties

/**
 * Onde o RoqueOS mora — port do `RoqueOSConfig.swift`. Vem de
 * `assets/roqueos-config.properties`, que é GITIGNORED e gerado por
 * `scripts/gen-roqueos-config.py` a partir do `.env` do roqueos-front
 * (espelho do gen-roqueos-config.mjs do iOS).
 *
 * A chave do Firebase aqui é a CHAVE DE CLIENTE, a mesma que já vai no
 * bundle JavaScript do site: identifica o projeto, não autoriza nada — quem
 * autoriza são as regras do Firestore. Ainda assim fica fora do git.
 *
 * Sem o arquivo, `isConfigured` é false e a tela avisa em vez de quebrar —
 * o MESMO estado do clone do iOS, que também não tem o plist.
 */
class RoqueOSConfig private constructor(private val values: Properties) {
    companion object {
        private const val ASSET = "roqueos-config.properties"

        fun load(context: Context): RoqueOSConfig {
            val values = Properties()
            runCatching {
                context.assets.open(ASSET).use { values.load(it) }
            }
            return RoqueOSConfig(values)
        }
    }

    val projectID: String get() = value("projectID") ?: "roqueos"
    val functionsRegion: String get() = value("functionsRegion") ?: "southamerica-east1"
    val apiKey: String? get() = value("apiKey")

    /** Sem chave o app não fala com o RoqueOS. A tela avisa em vez de quebrar. */
    val isConfigured: Boolean get() = !apiKey.isNullOrEmpty()

    val functionsBaseURL: String get() = "https://$functionsRegion-$projectID.cloudfunctions.net"

    val firestoreBaseURL: String
        get() = "https://firestore.googleapis.com/v1/projects/$projectID/databases/(default)/documents"

    /** O caminho de aprovação é `/link/<pairingId>`; sem o id a página não sabe o quê aprovar. */
    val webBaseURL: String get() = value("webBaseURL") ?: "https://roqueos.com.br"

    fun approvalURL(pairingID: String): String = "$webBaseURL/link/$pairingID"

    private fun value(key: String): String? =
        values.getProperty(key)?.takeIf { it.isNotEmpty() }
}
