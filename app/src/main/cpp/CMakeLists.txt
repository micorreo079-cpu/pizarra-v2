cmake_minimum_required(VERSION 3.22.1)
project("sony_dhw")

add_library(sony_dhw SHARED sony_dhw.cpp)

find_library(log-lib log)
target_link_libraries(sony_dhw dl ${log-lib})

# Activación: código del aparato y comprobación de la clave (secreto incluido).
add_library(rbact SHARED activation.cpp)
target_link_libraries(rbact ${log-lib})
