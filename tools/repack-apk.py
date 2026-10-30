"""把 APK 里的 resources.arsc 以 STORED 方式重写，并统一 zip 路径分隔符。

Android 11+（targetSdk >= 30）要求 resources.arsc 未压缩且 4 字节对齐，
否则安装会报 -124 / INSTALL_PARSE_FAILED_BAD_MANIFEST。
.NET 的 ZipArchive 在 Windows PowerShell 5.1 下无法可靠写出 STORED 条目，
所以这里用 Python 的 zipfile 重写，再由 zipalign 对齐。
"""
import sys
import zipfile


def main():
    if len(sys.argv) != 3:
        print("usage: repack-apk.py <src.apk> <dst.apk>")
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", allowZip64=True) as zout:
        for item in zin.infolist():
            name = item.filename.replace("\\", "/")
            if name.startswith("META-INF/"):
                continue
            data = zin.read(item.filename)
            item.filename = name
            if name == "resources.arsc" or name.endswith(".so"):
                zout.writestr(item, data, compress_type=zipfile.ZIP_STORED)
            else:
                zout.writestr(item, data, compress_type=item.compress_type)
    print("repacked:", dst)
    return 0


if __name__ == "__main__":
    sys.exit(main())
