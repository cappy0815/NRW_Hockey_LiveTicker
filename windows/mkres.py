#!/usr/bin/env python3
"""Erzeugt rsrc_windows_amd64.syso (COFF-Objekt mit .rsrc-Abschnitt) fuer den Go-Linker:
Icon (optional), Versionsinfo und Manifest. Aufruf: mkres.py <version a.b.c.d> [icon.ico]"""
import struct, sys

def u16z(s):
    return s.encode('utf-16-le') + b'\0\0'

def pad4(b):
    return b + b'\0' * (-len(b) % 4)

# ---------------------------------------------------------------- Versionsinfo (VS_VERSIONINFO)
def vnode(key, value=b'', wtype=1, children=(), vlen=None):
    if vlen is None:
        vlen = len(value) // 2 if wtype == 1 else len(value)
    head = pad4(struct.pack('<HHH', 0, vlen, wtype) + u16z(key))
    body = head + pad4(value) if value else head
    for c in children:
        body = pad4(body) + c
    return struct.pack('<H', len(body)) + body[2:]

def version_info(ver, strings):
    v = [int(x) for x in ver.split('.')]
    ms, ls = (v[0] << 16) | v[1], (v[2] << 16) | v[3]
    fixed = struct.pack('<13I', 0xFEEF04BD, 0x00010000, ms, ls, ms, ls, 0x3F, 0, 0x40004, 1, 0, 0, 0)
    strs = [vnode(k, u16z(val), 1) for k, val in strings]
    table = vnode('040704B0', b'', 1, strs, vlen=0)
    sfi = vnode('StringFileInfo', b'', 1, [table], vlen=0)
    var = vnode('Translation', struct.pack('<HH', 0x0407, 0x04B0), 0)
    vfi = vnode('VarFileInfo', b'', 1, [var], vlen=0)
    return vnode('VS_VERSION_INFO', fixed, 0, [sfi, vfi])

MANIFEST = b'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity type="win32" name="EHV.LiveTicker" version="3.2.0.0" processorArchitecture="amd64"/>
  <description>EHV-NRW Live-Ticker</description>
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3">
    <security><requestedPrivileges><requestedExecutionLevel level="asInvoker" uiAccess="false"/></requestedPrivileges></security>
  </trustInfo>
  <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1">
    <application><supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/></application>
  </compatibility>
  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings><dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true</dpiAware></windowsSettings>
  </application>
</assembly>
'''

# ---------------------------------------------------------------- Icon
def icon_resources(path):
    d = open(path, 'rb').read()
    _, typ, n = struct.unpack_from('<HHH', d, 0)
    assert typ == 1 and n > 0, 'keine gueltige ICO-Datei'
    icons, grp = [], struct.pack('<HHH', 0, 1, n)
    for i in range(n):
        w, h, cc, r, planes, bits, size, off = struct.unpack_from('<BBBBHHII', d, 6 + 16 * i)
        icons.append((i + 1, d[off:off + size]))
        grp += struct.pack('<BBBBHHIH', w, h, cc, r, planes, bits, size, i + 1)
    return icons, grp

# ---------------------------------------------------------------- Ressourcenbaum + COFF
def build(resources):
    """resources: {typ: {id: bytes}} -> (section bytes, reloc offsets)"""
    LANG = 0x0407
    types = sorted(resources)
    # Groessen: Verzeichnisse zuerst, dann Datenbeschreibungen, dann Daten
    dirs, entries = [], []
    off = 16 + 8 * len(types)
    type_dirs = []
    for t in types:
        ids = sorted(resources[t])
        type_dirs.append(off)
        off += 16 + 8 * len(ids)
    name_dirs = {}
    for t in types:
        for i in sorted(resources[t]):
            name_dirs[(t, i)] = off
            off += 16 + 8
    data_entries = {}
    for t in types:
        for i in sorted(resources[t]):
            data_entries[(t, i)] = off
            off += 16
    data_off, blob = {}, b''
    for t in types:
        for i in sorted(resources[t]):
            blob = pad4(blob) if blob else blob
            data_off[(t, i)] = off + len(blob)
            blob += resources[t][i]
    sec = bytearray()
    hdr = lambda n: struct.pack('<IIHHHH', 0, 0, 0, 0, 0, n)
    sec += hdr(len(types))
    for t, o in zip(types, type_dirs):
        sec += struct.pack('<II', t, 0x80000000 | o)
    for t in types:
        ids = sorted(resources[t])
        sec += hdr(len(ids))
        for i in ids:
            sec += struct.pack('<II', i, 0x80000000 | name_dirs[(t, i)])
    for t in types:
        for i in sorted(resources[t]):
            sec += hdr(1) + struct.pack('<II', LANG, data_entries[(t, i)])
    relocs = []
    for t in types:
        for i in sorted(resources[t]):
            relocs.append(len(sec))
            sec += struct.pack('<IIII', data_off[(t, i)], len(resources[t][i]), 0, 0)
    sec += blob
    sec = bytes(pad4(bytes(sec)))
    return sec, relocs

def coff(sec, relocs):
    p_raw = 20 + 40
    p_rel = p_raw + len(sec)
    p_sym = p_rel + 10 * len(relocs)
    out = struct.pack('<HHIIIHH', 0x8664, 1, 0, p_sym, 1, 0, 0)
    out += struct.pack('<8sIIIIIIHHI', b'.rsrc', 0, 0, len(sec), p_raw, p_rel, 0, len(relocs), 0, 0x40000040)
    out += sec
    for r in relocs:
        out += struct.pack('<IIH', r, 0, 3)  # IMAGE_REL_AMD64_ADDR32NB
    out += struct.pack('<8sIhHBB', b'.rsrc', 0, 1, 0, 3, 0)  # Abschnittssymbol
    out += struct.pack('<I', 4)  # leere Stringtabelle
    return out

if __name__ == '__main__':
    ver = sys.argv[1]
    res = {16: {1: version_info(ver, [
        ('CompanyName', 'Michael Nowak (privat)'), ('FileDescription', 'EHV-NRW Live-Ticker'),
        ('FileVersion', ver), ('InternalName', 'EHV_NRW_LiveTicker'),
        ('LegalCopyright', 'Privates, inoffizielles Tool'), ('OriginalFilename', 'EHV_NRW_LiveTicker.exe'),
        ('ProductName', 'EHV-NRW Live-Ticker'), ('ProductVersion', ver)])},
        24: {1: MANIFEST}}
    if len(sys.argv) > 2:
        icons, grp = icon_resources(sys.argv[2])
        res[3] = dict(icons)
        res[14] = {1: grp}
    open('rsrc_windows_amd64.syso', 'wb').write(coff(*build(res)))
    print('ok', {k: len(v) for k, v in res.items()})
