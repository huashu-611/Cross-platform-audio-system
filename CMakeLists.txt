cmake_minimum_required(VERSION 3.16)
project(audiohub LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "" FORCE)
endif()

# ------------------------------------------------------------------ 核心库
# 平台无关：协议、抖动缓冲、时钟同步、重采样、混音。
# 单独成库的目的很实际——它能在没有音频设备的机器上编译和跑测试，
# CI 里不需要声卡。
add_library(audiohub_core STATIC
  src/packet.cpp
  src/jitter_buffer.cpp
  src/clock_sync.cpp
  src/asrc.cpp
  src/mixer.cpp
  src/udp_socket.cpp
)
target_include_directories(audiohub_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_options(audiohub_core PRIVATE
  $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra>
  $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

if(WIN32)
  target_link_libraries(audiohub_core PUBLIC ws2_32)
else()
  target_link_libraries(audiohub_core PUBLIC pthread)
endif()

# ------------------------------------------------------------------ 平台层
if(WIN32)
  add_library(audiohub_win STATIC platform/win/wasapi_audio.cpp)
  target_include_directories(audiohub_win PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/platform/win)
  target_link_libraries(audiohub_win PUBLIC audiohub_core ole32)
endif()

# ------------------------------------------------------------------ 应用
if(WIN32)
  add_executable(audiohub-source apps/source_main.cpp)
  target_link_libraries(audiohub-source PRIVATE audiohub_win)

  add_executable(audiohub-hub apps/hub_main.cpp)
  target_link_libraries(audiohub-hub PRIVATE audiohub_win)

  # 放进同一个目录，方便直接双击/命令行对照运行
  set_target_properties(audiohub-source audiohub-hub PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
endif()

# ------------------------------------------------------------------ 测试
enable_testing()

add_executable(audiohub_tests
  tests/test_main.cpp
  tests/test_packet.cpp
  tests/test_ring.cpp
  tests/test_jitter.cpp
  tests/test_clock.cpp
  tests/test_asrc.cpp
  tests/test_mixer.cpp
)
target_link_libraries(audiohub_tests PRIVATE audiohub_core)
target_include_directories(audiohub_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/tests)

add_test(NAME audiohub_tests COMMAND audiohub_tests)
