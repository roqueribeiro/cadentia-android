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
