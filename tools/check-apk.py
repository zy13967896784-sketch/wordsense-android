"""校验 APK 的 resources.arsc 是否满足 Android 11+ 的安装要求。"""
import struct
import sys
import zipfile


def main():
    if len(sys.argv) != 2:
        print("usage: check-apk.py <apk>")
        return 2
    path = sys.argv[1]
    with zipfile.ZipFile(path) as z:
        names = [n.replace("\\", "/") for n in z.namelist()]
        if any("\\" in n for n in names):
            print("FAIL: zip entry contains backslash")
            return 1
        try:
            info = z.getinfo("resources.arsc")
        except KeyError:
            print("FAIL: resources.arsc missing")
            return 1
        if info.compress_type != zipfile.ZIP_STORED:
            print("FAIL: resources.arsc is compressed, method=", info.compress_type)
            return 1
        with open(path, "rb") as f:
            f.seek(info.header_offset)
            header = f.read(30)
            if len(header) != 30:
                print("FAIL: truncated local header")
                return 1
            sig, _ver, _flags, method, _t, _d, _crc, _cs, _us, nlen, elen = struct.unpack(
                "<IHHHHHIIIHH", header
            )
            data_off = info.header_offset + 30 + nlen + elen
        if method != 0:
            print("FAIL: local header method=", method)
            return 1
        if data_off % 4 != 0:
            print("FAIL: resources.arsc offset", data_off, "not 4-byte aligned")
            return 1
        print(
            "OK: resources.arsc stored, offset=%d aligned=0, entries=%d"
            % (data_off, len(names))
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
