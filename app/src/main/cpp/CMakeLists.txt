cmake_minimum_required(VERSION 3.22.1)
project(cadentia_audio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe vem por prefab (AAR com.google.oboe:oboe).
find_package(oboe REQUIRED CONFIG)

add_library(cadentia_audio SHARED
        native-lib.cpp
        AudioEngine.cpp)

target_link_libraries(cadentia_audio
        oboe::oboe
        android
        log)

# Áudio de tempo real: otimiza, mas mantém o comportamento previsível.
target_compile_options(cadentia_audio PRIVATE -O2 -ffast-math -Wall)
