# A ponte JNI é chamada pelo nome a partir do C++: o R8 não pode renomear
# nem remover os métodos native (nem a classe que os declara).
-keepclasseswithmembernames class com.levelhard.cadentia.audio.AudioEngineBridge {
    native <methods>;
}

# MediaPipe Tasks (Cordas, modo câmera): o JNI chama classes Java pelo nome e
# os protos são lidos por reflexão. O AAR não traz regras de consumidor
# (conferido: nenhum proguard.txt em tasks-core/tasks-vision 1.0.0), então
# elas moram aqui. Sem isto o build qa minificado morre ao criar o landmarker.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# O datatransport é referenciado pelo tasks-core; o backend CCT fica sem
# registro pelo manifesto, mas as classes precisam existir para não cair.
-keep class com.google.android.datatransport.** { *; }
-dontwarn com.google.android.datatransport.**

# Flogger, que o MediaPipe usa para registrar log: `FluentLogger
# .forEnclosingClass()` descobre quem chamou ANDANDO A PILHA e comparando o
# nome da classe. O R8 inlina `findLoggingClass` dentro dele e mescla os
# frames; o `Graph.<clinit>` então morre com
# `IllegalStateException: no caller found on the stack for: l7.c`
# (l7.c = com.google.common.flogger.FluentLogger no mapping) e o app FECHA ao
# abrir o Cordas na câmera — só na build minificada, por isso passou por todo
# o QA em debug (achado no aparelho do Roque, 05/09/2026).
-keep class com.google.common.flogger.** { *; }
-keepnames class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# ONNX Runtime: a JNI constrói TensorInfo/OnnxTensor/OrtSession$Result pelo
# nome (NewObjectV). O R8 apagava o construtor de TensorInfo e a primeira
# inferência morria com SIGABRT "JNI NewObjectV called with pending
# exception NoSuchMethodError" (achado no emulador, 04/09).
-keep class ai.onnxruntime.** { *; }
