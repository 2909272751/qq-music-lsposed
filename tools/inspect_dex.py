"""List matching DEX class names from an APK without decompiling every class."""

import re
import struct
import sys
import zipfile


def u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def read_uleb(data, offset):
    while data[offset] & 0x80:
        offset += 1
    return offset + 1


def dex_classes(data):
    strings_count, strings_offset = u32(data, 0x38), u32(data, 0x3C)
    types_count, types_offset = u32(data, 0x40), u32(data, 0x44)
    classes_count, classes_offset = u32(data, 0x60), u32(data, 0x64)
    strings = []
    for i in range(strings_count):
        start = read_uleb(data, u32(data, strings_offset + i * 4))
        end = data.index(0, start)
        strings.append(data[start:end].decode("utf-8", "replace"))
    types = [strings[u32(data, types_offset + i * 4)] for i in range(types_count)]
    for i in range(classes_count):
        yield types[u32(data, classes_offset + i * 32)]


apk, pattern = sys.argv[1:3]
matcher = re.compile(pattern, re.IGNORECASE)
with zipfile.ZipFile(apk) as package:
    for entry in package.namelist():
        if re.fullmatch(r"classes\d*\.dex", entry):
            for name in dex_classes(package.read(entry)):
                if matcher.search(name):
                    print(name.strip("L;").replace("/", "."))
