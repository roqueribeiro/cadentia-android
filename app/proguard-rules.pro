# A ponte JNI é chamada pelo nome a partir do C++: o R8 não pode renomear
# nem remover os métodos native (nem a classe que os declara).
-keepclasseswithmembernames class com.levelhard.cadentia.audio.AudioEngineBridge {
    native <methods>;
}
