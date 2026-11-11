#!/usr/bin/env python3
"""
把 APK 里 AndroidManifest.xml 的 android:minSdkVersion 改成一个更小的值, 并重写成一份新的 APK.

为什么需要它: 2026 年这一代 AndroidX 的 minSdk 底线是 23, 而目标设备是 Android 5.0 (API 21)。
AGP 的 manifest 合并只会拿**声明的** minSdk 去和每个库比, 一个库一轮报错; 而 D8 编出来的 dex
版本只取决于 minSdk < 24 与否 —— 21 和 23 都出 **DEX 035**, 默认方法/lambda/java.time 的脱糖
完全一样。所以「按 23 构建 + 事后把清单里的 21 改回去」与「按 21 构建」产出的代码逐字节等价,
只是绕开了合并期的逐个比对。

改完必须重新签名 (v1 + v2): 原来那套签名块在重写 zip 时已经被丢掉了。

用法:
    patch-apk-min-sdk.py <输入.apk> <新的 minSdk> <输出.apk>
"""

import struct
import sys
import zipfile

ATTR_MIN_SDK_VERSION = 0x0101020C
RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102


def read_string_pool(data, chunk_start, chunk_size, chunk_header_size):
    # 字符串池的字段就在 chunk header 之后 (headerSize 已经包含了那 8 字节), 而 offsets 数组从 chunk_header_size 开始;
    # stringsStart 是相对 **chunk 起点** 的偏移, 不是相对 body 的。
    fields = chunk_start + 8
    string_count, style_count, flags, strings_start, styles_start = struct.unpack('<IIIII', data[fields:fields + 20])
    offsets_start = chunk_start + chunk_header_size
    offsets = struct.unpack('<%dI' % string_count, data[offsets_start:offsets_start + 4 * string_count])
    utf8 = bool(flags & 0x100)
    base = chunk_start + strings_start
    chunk_end = chunk_start + chunk_size
    strings = []
    for offset in offsets:
        pos = base + offset
        if utf8:
            pos += 1 if data[pos] < 0x80 else 2      # utf-16 长度
            length = data[pos]
            pos += 1
            if length & 0x80:
                length = ((length & 0x7F) << 8) | data[pos]
                pos += 1
            strings.append(data[pos:min(pos + length, chunk_end)].decode('utf-8', 'replace'))
        else:
            length = struct.unpack('<H', data[pos:pos + 2])[0]
            pos += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | struct.unpack('<H', data[pos:pos + 2])[0]
                pos += 2
            strings.append(data[pos:min(pos + 2 * length, chunk_end)].decode('utf-16-le', 'replace'))
    return strings


def patch_manifest(data, new_min_sdk):
    """原地改写二进制 AXML 里 uses-sdk/minSdkVersion 的整数值, 返回 (是否改动, 旧值)."""
    if struct.unpack('<H', data[0:2])[0] != RES_XML_TYPE:
        raise SystemExit('AndroidManifest.xml 不是二进制 AXML, 无法打补丁')
    pos = struct.unpack('<H', data[2:4])[0]
    strings = []
    old_value = None
    while pos + 8 <= len(data):
        chunk_type, chunk_header_size, chunk_size = struct.unpack('<HHI', data[pos:pos + 8])
        if chunk_size < 8:
            break
        body = pos + chunk_header_size
        if chunk_type == RES_STRING_POOL_TYPE:
            strings = read_string_pool(data, pos, chunk_size, chunk_header_size)
        elif chunk_type == RES_XML_START_ELEMENT_TYPE:
            name_index, attr_start, attr_size, attr_count = struct.unpack('<IHHH', data[body + 4:body + 14])
            element = strings[name_index] if name_index < len(strings) else ''
            if element == 'uses-sdk':
                for i in range(attr_count):
                    attr = body + attr_start + i * attr_size
                    # ns(4) name(4) rawValue(4) size(2) res0(1) dataType(1) data(4)
                    name = struct.unpack('<I', data[attr + 4:attr + 8])[0]
                    is_min_sdk = name == ATTR_MIN_SDK_VERSION or (
                            name < len(strings) and strings[name] == 'minSdkVersion')
                    if not is_min_sdk:
                        continue
                    old_value = struct.unpack('<I', data[attr + 16:attr + 20])[0]
                    struct.pack_into('<I', data, attr + 16, new_min_sdk)
                    return True, old_value
        pos += chunk_size
    return False, old_value


def is_signature_file(name):
    if not name.startswith('META-INF/'):
        return False
    upper = name.upper()
    return upper == 'META-INF/MANIFEST.MF' or upper.endswith(('.SF', '.RSA', '.DSA', '.EC'))


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    src, new_min_sdk, dst = sys.argv[1], int(sys.argv[2]), sys.argv[3]

    with zipfile.ZipFile(src) as zin:
        manifest = zin.read('AndroidManifest.xml')
    patched = bytearray(manifest)
    changed, old_value = patch_manifest(patched, new_min_sdk)
    if not changed:
        raise SystemExit('没在 AndroidManifest.xml 里找到 uses-sdk/minSdkVersion')
    print(f'minSdkVersion: {old_value} -> {new_min_sdk}')
    if old_value == new_min_sdk:
        print('已经是目标值, 只重新打包')

    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
        for info in zin.infolist():
            # 旧的 v1 签名文件必须丢掉: 它们签的是改之前的清单, 留着只会让重签后的包验签失败
            if is_signature_file(info.filename):
                continue
            if info.filename == 'AndroidManifest.xml':
                data = bytes(patched)
            else:
                data = zin.read(info.filename)
            entry = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            entry.compress_type = info.compress_type
            entry.external_attr = info.external_attr
            entry.internal_attr = info.internal_attr
            entry.create_system = info.create_system
            # 清单在原始 APK 里是 deflate 的, 保持一致
            zout.writestr(entry, data)
    print(f'已写出 {dst}')


if __name__ == '__main__':
    main()
