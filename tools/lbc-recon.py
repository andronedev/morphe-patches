#!/usr/bin/env python3
"""Reconnaissance d'un APK pour préparer les patchs Morphe LBC.

Extrait, sans SDK Android ni apktool (stdlib uniquement) :
  - le manifest binaire (package, version, classe Application, permissions, activités)
  - la table des chaînes de chaque classes*.dex
  - les noms de classes / méthodes / champs
  - les candidats intéressants : endpoints d'API, SDK publicitaires, OkHttp/Retrofit

Usage :
    python3 tools/lbc-recon.py fr.leboncoin.apk -o recon/
    python3 tools/lbc-recon.py --selftest

La sortie `recon/` sert à écrire les fingerprints des patchs et à remplir `bindings.json`.
Voir docs/leboncoin/ARCHITECTURE.md.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import struct
import sys
import zipfile
from collections import defaultdict

# --------------------------------------------------------------------------- dex

DEX_MAGIC = b"dex\n"


def _uleb128(buf: bytes, off: int) -> tuple[int, int]:
    """Décode un uleb128. Retourne (valeur, offset suivant)."""
    result = 0
    shift = 0
    while True:
        byte = buf[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if byte & 0x80 == 0:
            return result, off
        shift += 7
        if shift > 28:
            raise ValueError("uleb128 trop long")


class Dex:
    """Lecteur minimal du format dex : chaînes, types, méthodes, champs."""

    def __init__(self, buf: bytes):
        if buf[:4] != DEX_MAGIC:
            raise ValueError("magic dex invalide")
        self.buf = buf
        (
            self.string_ids_size,
            self.string_ids_off,
            self.type_ids_size,
            self.type_ids_off,
        ) = struct.unpack_from("<IIII", buf, 0x38)
        (
            self.proto_ids_size,
            self.proto_ids_off,
            self.field_ids_size,
            self.field_ids_off,
            self.method_ids_size,
            self.method_ids_off,
            self.class_defs_size,
            self.class_defs_off,
        ) = struct.unpack_from("<IIIIIIII", buf, 0x48)

    def string(self, idx: int) -> str:
        off = struct.unpack_from("<I", self.buf, self.string_ids_off + idx * 4)[0]
        _, off = _uleb128(self.buf, off)  # taille en unités utf16, inutile ici
        end = self.buf.index(b"\x00", off)
        return self.buf[off:end].decode("utf-8", errors="replace")

    def strings(self) -> list[str]:
        return [self.string(i) for i in range(self.string_ids_size)]

    def type_name(self, idx: int) -> str:
        return self.string(struct.unpack_from("<I", self.buf, self.type_ids_off + idx * 4)[0])

    def types(self) -> list[str]:
        return [self.type_name(i) for i in range(self.type_ids_size)]

    def methods(self) -> list[tuple[str, str]]:
        """[(classe, nom)] pour chaque method_id."""
        out = []
        for i in range(self.method_ids_size):
            class_idx, _proto, name_idx = struct.unpack_from(
                "<HHI", self.buf, self.method_ids_off + i * 8
            )
            out.append((self.type_name(class_idx), self.string(name_idx)))
        return out

    def fields(self) -> list[tuple[str, str]]:
        out = []
        for i in range(self.field_ids_size):
            class_idx, _type, name_idx = struct.unpack_from(
                "<HHI", self.buf, self.field_ids_off + i * 8
            )
            out.append((self.type_name(class_idx), self.string(name_idx)))
        return out


# ------------------------------------------------------------------ manifest axml

_RES_XML = 0x00080003
_CHUNK_STRING_POOL = 0x001C0001
_CHUNK_START_TAG = 0x00100102
_CHUNK_END_TAG = 0x00100103
_UTF8_FLAG = 1 << 8

_TYPE_STRING = 0x03
_TYPE_INT_DEC = 0x10
_TYPE_INT_HEX = 0x11
_TYPE_INT_BOOL = 0x12


def _axml_string_pool(buf: bytes, off: int) -> list[str]:
    _type, _size, count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<IIIIIII", buf, off
    )
    utf8 = bool(flags & _UTF8_FLAG)
    offsets = struct.unpack_from("<%dI" % count, buf, off + 28)
    base = off + strings_start
    out = []
    for rel in offsets:
        p = base + rel
        if utf8:
            _n_utf16, p = _uleb128(buf, p)
            n_bytes, p = _uleb128(buf, p)
            out.append(buf[p : p + n_bytes].decode("utf-8", errors="replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            if n & 0x8000:  # chaîne longue, encodée sur deux u16
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p + 2)[0]
                p += 4
            else:
                p += 2
            out.append(buf[p : p + n * 2].decode("utf-16-le", errors="replace"))
    return out


class ManifestTag:
    def __init__(self, name: str, attrs: dict[str, str], depth: int):
        self.name = name
        self.attrs = attrs
        self.depth = depth


def parse_axml(buf: bytes) -> list[ManifestTag]:
    """Parse un AndroidManifest.xml binaire. Retourne la liste à plat des balises ouvrantes."""
    magic = struct.unpack_from("<I", buf, 0)[0]
    if magic != _RES_XML:
        raise ValueError("ce n'est pas un AXML (magic 0x%08x)" % magic)

    pool: list[str] = []
    tags: list[ManifestTag] = []
    depth = 0
    off = struct.unpack_from("<H", buf, 2)[0]  # header_size du chunk racine

    def s(idx: int) -> str:
        return pool[idx] if 0 <= idx < len(pool) else ""

    while off + 8 <= len(buf):
        chunk_type, chunk_size = struct.unpack_from("<II", buf, off)
        if chunk_size < 8:
            break
        if chunk_type == _CHUNK_STRING_POOL:
            pool = _axml_string_pool(buf, off)
        elif chunk_type == _CHUNK_START_TAG:
            header_size = struct.unpack_from("<H", buf, off + 2)[0]
            name = s(struct.unpack_from("<I", buf, off + 20)[0])
            attr_start, _attr_size, attr_count = struct.unpack_from("<HHH", buf, off + 24)
            attrs: dict[str, str] = {}
            for i in range(attr_count):
                # attr_start est relatif à la fin de l'en-tête de chunk (attrExt).
                a = off + header_size + attr_start + i * 20
                a_ns, a_name, a_raw = struct.unpack_from("<III", buf, a)
                data_type = buf[a + 15]
                data = struct.unpack_from("<I", buf, a + 16)[0]
                key = s(a_name)
                if s(a_ns).endswith("android"):
                    key = "android:" + key
                if a_raw != 0xFFFFFFFF and data_type == _TYPE_STRING:
                    value = s(a_raw)
                elif data_type == _TYPE_INT_BOOL:
                    value = "true" if data else "false"
                elif data_type == _TYPE_INT_HEX:
                    value = hex(data)
                elif data_type == _TYPE_INT_DEC:
                    value = str(data)
                else:
                    value = s(a_raw) if a_raw != 0xFFFFFFFF else "0x%08x" % data
                attrs[key] = value
            tags.append(ManifestTag(name, attrs, depth))
            depth += 1
        elif chunk_type == _CHUNK_END_TAG:
            depth -= 1
        off += chunk_size
    return tags


# ------------------------------------------------------------------------ analyse

AD_SDK_MARKERS = {
    "google-ads": ["com.google.android.gms.ads", "com/google/android/gms/ads"],
    "admob": ["com.google.android.gms.ads.MobileAds"],
    "criteo": ["com.criteo", "criteo.com"],
    "applovin": ["com.applovin", "applovin.com"],
    "smart-adserver": ["com.smartadserver", "smartadserver.com"],
    "teads": ["tv.teads", "teads.tv"],
    "outbrain": ["com.outbrain", "outbrain.com"],
    "batch": ["com.batch.android"],
    "adjust": ["com.adjust.sdk"],
    "firebase-analytics": ["com.google.firebase.analytics"],
    "amplitude": ["com.amplitude"],
    "didomi": ["io.didomi"],
    "prebid": ["org.prebid", "prebid.org"],
}

HTTP_MARKERS = {
    "okhttp": ["okhttp3.OkHttpClient", "Lokhttp3/OkHttpClient;"],
    "retrofit": ["retrofit2.Retrofit", "Lretrofit2/Retrofit;"],
    "ktor": ["io.ktor.client"],
    "apollo-graphql": ["com.apollographql.apollo"],
}

# Endpoints intéressants pour les fonctions visées.
ENDPOINT_HINTS = [
    "finder/search",
    "/ads",
    "/api/",
    "leboncoin.fr",
    "leboncoin.io",
    "/deposit",
    "/delete",
    "/mes-annonces",
    "saved_search",
    "recherche",
]

URL_RE = re.compile(r"https?://[\w.\-]+(?:/[\w.\-/{}$:%]*)?")
PATH_RE = re.compile(r"^/[\w.\-/{}]{3,}$")


def analyse_apk(apk_path: str, out_dir: str) -> dict:
    os.makedirs(out_dir, exist_ok=True)
    report: dict = {"apk": os.path.basename(apk_path), "dex": {}, "sdk": {}, "http": {}}

    with zipfile.ZipFile(apk_path) as z:
        names = z.namelist()

        # --- manifest
        if "AndroidManifest.xml" in names:
            try:
                tags = parse_axml(z.read("AndroidManifest.xml"))
                manifest = {
                    "permissions": sorted(
                        t.attrs.get("android:name", "")
                        for t in tags
                        if t.name == "uses-permission"
                    ),
                    "activities": sorted(
                        t.attrs.get("android:name", "") for t in tags if t.name == "activity"
                    ),
                }
                for t in tags:
                    if t.name == "manifest":
                        manifest["package"] = t.attrs.get("package", "")
                        manifest["versionName"] = t.attrs.get("android:versionName", "")
                        manifest["versionCode"] = t.attrs.get("android:versionCode", "")
                    elif t.name == "application":
                        manifest["applicationClass"] = t.attrs.get("android:name", "")
                        manifest["debuggable"] = t.attrs.get("android:debuggable", "false")
                report["manifest"] = manifest
            except Exception as exc:  # manifest exotique : on continue sans
                report["manifest"] = {"error": "%s: %s" % (type(exc).__name__, exc)}

        # --- dex
        all_strings: list[str] = []
        all_types: set[str] = set()
        methods_by_class: dict[str, set[str]] = defaultdict(set)

        for name in sorted(n for n in names if n.endswith(".dex")):
            try:
                dex = Dex(z.read(name))
            except Exception as exc:
                report["dex"][name] = {"error": str(exc)}
                continue
            strings = dex.strings()
            types = dex.types()
            all_strings.extend(strings)
            all_types.update(types)
            for cls, meth in dex.methods():
                methods_by_class[cls].add(meth)
            report["dex"][name] = {
                "strings": len(strings),
                "types": len(types),
                "methods": dex.method_ids_size,
                "classes": dex.class_defs_size,
            }

    unique_strings = sorted(set(all_strings))

    # --- SDK détectés
    joined = "\n".join(unique_strings) + "\n" + "\n".join(sorted(all_types))
    for label, markers in AD_SDK_MARKERS.items():
        hits = [m for m in markers if m in joined]
        if hits:
            report["sdk"][label] = hits
    for label, markers in HTTP_MARKERS.items():
        hits = [m for m in markers if m in joined]
        if hits:
            report["http"][label] = hits

    # --- URLs et chemins d'API
    urls = sorted({m.group(0) for s in unique_strings for m in URL_RE.finditer(s)})
    paths = sorted({s for s in unique_strings if PATH_RE.match(s)})
    interesting = sorted(
        {s for s in unique_strings if any(h in s for h in ENDPOINT_HINTS)}
    )

    # --- écriture
    def dump(fname: str, lines) -> None:
        with io.open(os.path.join(out_dir, fname), "w", encoding="utf-8") as fh:
            for line in lines:
                fh.write(line + "\n")

    dump("strings.txt", unique_strings)
    dump("types.txt", sorted(all_types))
    dump("urls.txt", urls)
    dump("api-paths.txt", paths)
    dump("endpoints-candidats.txt", interesting)
    dump(
        "okhttp-classes.txt",
        sorted(
            "%s -> %s" % (cls, ",".join(sorted(m)))
            for cls, m in methods_by_class.items()
            if "okhttp" in cls.lower() or "retrofit" in cls.lower()
        ),
    )

    report["counts"] = {
        "strings": len(unique_strings),
        "types": len(all_types),
        "urls": len(urls),
        "apiPaths": len(paths),
        "endpointCandidates": len(interesting),
    }
    with io.open(os.path.join(out_dir, "report.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, ensure_ascii=False)
    return report


# ----------------------------------------------------------------------- selftest


def _build_synthetic_dex(strings: list[str], types: list[int], methods: list[tuple[int, int]]) -> bytes:
    """Construit un dex minimal (tables de chaînes/types/méthodes) pour tester le parseur."""
    header = bytearray(0x70)
    header[0:8] = b"dex\n035\x00"

    data = bytearray()
    data_base = 0x70

    def uleb(value: int) -> bytes:
        out = bytearray()
        while True:
            byte = value & 0x7F
            value >>= 7
            if value:
                out.append(byte | 0x80)
            else:
                out.append(byte)
                return bytes(out)

    string_offsets = []
    for s in strings:
        encoded = s.encode("utf-8")
        string_offsets.append(data_base + len(data))
        data += uleb(len(s)) + encoded + b"\x00"

    string_ids_off = data_base + len(data)
    for off in string_offsets:
        data += struct.pack("<I", off)

    type_ids_off = data_base + len(data)
    for string_idx in types:
        data += struct.pack("<I", string_idx)

    method_ids_off = data_base + len(data)
    for type_idx, name_idx in methods:
        data += struct.pack("<HHI", type_idx, 0, name_idx)

    struct.pack_into("<IIII", header, 0x38, len(strings), string_ids_off, len(types), type_ids_off)
    struct.pack_into(
        "<IIIIIIII", header, 0x48,
        0, 0,                        # proto
        0, 0,                        # field
        len(methods), method_ids_off,
        0, 0,                        # class_defs
    )
    return bytes(header) + bytes(data)


def _build_synthetic_axml(pool: list[str], tag_name_idx: int, attrs: list[tuple[int, int, int]]) -> bytes:
    """Construit un AXML minimal : en-tête + string pool UTF-16 + une balise ouvrante."""
    # --- string pool
    encoded = bytearray()
    offsets = []
    for s in pool:
        offsets.append(len(encoded))
        encoded += struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\x00\x00"
    while len(encoded) % 4:
        encoded += b"\x00"
    strings_start = 28 + 4 * len(pool)
    pool_size = strings_start + len(encoded)
    pool_chunk = struct.pack(
        "<IIIIIII", _CHUNK_STRING_POOL, pool_size, len(pool), 0, 0, strings_start, 0
    ) + struct.pack("<%dI" % len(pool), *offsets) + bytes(encoded)

    # --- start tag
    attr_bytes = bytearray()
    for ns_idx, name_idx, raw_idx in attrs:
        attr_bytes += struct.pack("<III", ns_idx, name_idx, raw_idx)
        attr_bytes += struct.pack("<HBBI", 8, 0, _TYPE_STRING, raw_idx)
    tag_size = 36 + len(attr_bytes)
    tag_chunk = struct.pack(
        "<IIIIII", _CHUNK_START_TAG, tag_size, 1, 0xFFFFFFFF, 0xFFFFFFFF, tag_name_idx
    ) + struct.pack("<HHHHHH", 20, 20, len(attrs), 0, 0, 0) + bytes(attr_bytes)

    body = pool_chunk + tag_chunk
    # ResChunk_header racine : type u16, headerSize u16, size u32.
    return struct.pack("<HHI", 0x0003, 0x0008, 8 + len(body)) + body


def selftest() -> int:
    strings = ["Lfr/leboncoin/App;", "onCreate", "https://api.leboncoin.fr/finder/search", "héllo"]
    dex = Dex(_build_synthetic_dex(strings, types=[0], methods=[(0, 1)]))

    assert dex.strings() == strings, dex.strings()
    assert dex.types() == ["Lfr/leboncoin/App;"], dex.types()
    assert dex.methods() == [("Lfr/leboncoin/App;", "onCreate")], dex.methods()
    assert _uleb128(b"\xe5\x8e\x26", 0) == (624485, 3)
    assert _uleb128(b"\x00", 0) == (0, 1)
    assert URL_RE.findall(strings[2]) == ["https://api.leboncoin.fr/finder/search"]
    assert PATH_RE.match("/finder/search")
    assert not PATH_RE.match("finder/search")

    pool = ["http://schemas.android.com/apk/res/android", "name", "application", "fr.leboncoin.LBCApplication"]
    axml = _build_synthetic_axml(pool, tag_name_idx=2, attrs=[(0, 1, 3)])
    tags = parse_axml(axml)
    assert len(tags) == 1, tags
    assert tags[0].name == "application", tags[0].name
    assert tags[0].attrs == {"android:name": "fr.leboncoin.LBCApplication"}, tags[0].attrs

    print("selftest OK (dex strings/types/methods, uleb128, regex, axml)")
    return 0


# --------------------------------------------------------------------------- main


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", nargs="?", help="chemin de l'APK (ou du base.apk d'un split)")
    parser.add_argument("-o", "--out", default="recon", help="dossier de sortie (défaut: recon)")
    parser.add_argument("--selftest", action="store_true", help="teste le parseur sans APK")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()
    if not args.apk:
        parser.error("il faut un APK (ou --selftest)")
    if not os.path.isfile(args.apk):
        parser.error("APK introuvable: %s" % args.apk)

    report = analyse_apk(args.apk, args.out)
    manifest = report.get("manifest", {})
    print("package        : %s" % manifest.get("package", "?"))
    print("version        : %s (%s)" % (manifest.get("versionName", "?"), manifest.get("versionCode", "?")))
    print("Application    : %s" % manifest.get("applicationClass", "?"))
    print("dex            : %d" % len(report.get("dex", {})))
    print("SDK pub        : %s" % (", ".join(sorted(report.get("sdk", {}))) or "aucun détecté"))
    print("stack HTTP     : %s" % (", ".join(sorted(report.get("http", {}))) or "inconnue"))
    for key, value in sorted(report.get("counts", {}).items()):
        print("%-15s: %d" % (key, value))
    print("\nSortie dans %s/ (report.json, strings.txt, endpoints-candidats.txt, ...)" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
