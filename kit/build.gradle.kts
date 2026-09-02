plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// :kit é o domínio puro e testável do Cadentia — o espelho Kotlin/JVM do
// Packages/CadentiaKit do iOS. Nenhuma dependência de Android: entra número,
// sai FloatArray ou data class. Se dá para testar sem emulador, mora aqui.
// É isto que garante a paridade de DSP e a interop .rostab com o iOS e o web.

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

// Bytecode 17 (o mesmo do :app), compilado com o JDK que estiver rodando o
// Gradle — sem toolchain estrito, que exigiria exatamente um JDK 17 na máquina.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
