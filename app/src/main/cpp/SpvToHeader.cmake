# Converts a .spv file into a C++ header containing the byte array, so the
# shaders are embedded in libdsbench.so and no assets or runtime files are needed.
#
# Invoked as: cmake -DSPV=<file> -DOUT=<header> -DNAME=<identifier> -P SpvToHeader.cmake
file(READ "${SPV}" hex HEX)
string(LENGTH "${hex}" hexLen)
math(EXPR byteLen "${hexLen} / 2")
string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," bytes "${hex}")
file(WRITE "${OUT}"
    "// Generated from ${SPV} - do not edit.\n"
    "#pragma once\n"
    "static const unsigned char ${NAME}_spv[] = {${bytes}};\n"
    "static const unsigned int ${NAME}_spv_size = ${byteLen};\n")
