"""Verify production placeholder patterns against native Windows ICU4C.

Windows native ICU is mandatory when this command is invoked. There is no JDK
regex fallback. This checks a local native ICU backend, not an Android device.
The ordinary CI path does not need to invoke this Windows-specific guard.

Exit 0: both source patterns compile and pass all fixed synthetic contracts.
Exit 1: native compilation or a synthetic matching contract fails.
Exit 2: the source patterns cannot be extracted or native ICU is unavailable.
No private backup is read; reports contain controlled names, status and source hash.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path,
                        default=root / 'app/src/main/java/com/douai/module/config/ConfigSecrets.java')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if os.name != 'nt':
        print(json.dumps({'failure': 'WINDOWS_NATIVE_ICU_REQUIRED'}))
        return 2
    try:
        source_bytes = args.source.read_bytes()
        source = source_bytes.decode('utf-8-sig')
    except (OSError, UnicodeError):
        print(json.dumps({'failure': 'SOURCE_UNAVAILABLE'}))
        return 2
    locations = (
        ('redact_endpoint', r'value\.replaceAll\("((?:\\.|[^"\\])*)",\s*"douai-placeholder"\)'),
        ('placeholder_match', r'decoded\.matches\("((?:\\.|[^"\\])*)"\)'),
    )
    patterns = []
    for name, location in locations:
        matched = re.search(location, source)
        if matched is None:
            print(json.dumps({'failure': 'SOURCE_PATTERN_NOT_FOUND', 'check': name}))
            return 2
        try:
            pattern = json.loads('"' + matched.group(1) + '"')
        except ValueError:
            print(json.dumps({'failure': 'SOURCE_PATTERN_NOT_FOUND', 'check': name}))
            return 2
        patterns.append((name, pattern))

    try:
        library = ctypes.CDLL(str(Path(os.environ.get('WINDIR', r'C:\Windows')) / 'System32/icu.dll'))
        open_regex = library.uregex_open
        open_regex.argtypes = [ctypes.POINTER(ctypes.c_uint16), ctypes.c_int32, ctypes.c_uint32,
                              ctypes.c_void_p, ctypes.POINTER(ctypes.c_int32)]
        open_regex.restype = ctypes.c_void_p
        close_regex = library.uregex_close
        close_regex.argtypes = [ctypes.c_void_p]
        close_regex.restype = None
        error_name = library.u_errorName
        error_name.argtypes = [ctypes.c_int32]
        error_name.restype = ctypes.c_char_p
        set_text = library.uregex_setText
        set_text.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint16), ctypes.c_int32,
                            ctypes.POINTER(ctypes.c_int32)]
        set_text.restype = None
        matches = library.uregex_matches
        matches.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.POINTER(ctypes.c_int32)]
        matches.restype = ctypes.c_int8
        version = (ctypes.c_uint8 * 4)()
        library.u_getVersion.argtypes = [ctypes.POINTER(ctypes.c_uint8)]
        library.u_getVersion.restype = None
        library.u_getVersion(version)
    except (OSError, AttributeError):
        print(json.dumps({'failure': 'NATIVE_ICU_UNAVAILABLE'}))
        return 2

    def uchars(value):
        raw = value.encode('utf-16-le')
        return (ctypes.c_uint16 * (len(raw) // 2)).from_buffer_copy(raw)

    cases = [('text_placeholder', '{text}', True), ('voice_placeholder', '{voice}', True),
             ('identifier_with_digit', '{a_1}', True), ('ordinary_text', 'text', False),
             ('empty_identifier', '{}', False), ('leading_digit', '{9text}', False),
             ('punctuation', '{text-value}', False), ('trailing_text', '{text}tail', False)]
    checks = []
    failed = False
    for name, pattern in patterns:
        chars = uchars(pattern)
        status = ctypes.c_int32(0)
        compiled = open_regex(chars, len(chars), 0, None, ctypes.byref(status))
        check = {'check': name, 'compiled': bool(compiled), 'status': status.value,
                 'statusName': error_name(status.value).decode('ascii')}
        if not compiled or status.value > 0:
            failed = True
        if compiled:
            try:
                contracts = []
                buffers = []  # ICU retains the supplied UTF-16 text until reset/close.
                for case_name, value, expected in cases:
                    text = uchars(value)
                    buffers.append(text)
                    state = ctypes.c_int32(0)
                    set_text(compiled, text, len(text), ctypes.byref(state))
                    actual = bool(matches(compiled, 0, ctypes.byref(state)))
                    passed = state.value <= 0 and actual == expected
                    contracts.append({'case': case_name, 'passed': passed})
                    failed |= not passed
                check['syntheticContract'] = contracts
            finally:
                close_regex(compiled)
        checks.append(check)
    report = {'engine': 'native Windows ICU4C', 'icuVersion': list(version),
              'sourceSha256': hashlib.sha256(source_bytes).hexdigest(), 'checks': checks,
              'passed': not failed}
    encoded = json.dumps(report, indent=2)
    if args.output is not None:
        try:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded + '\n', encoding='utf-8')
        except OSError:
            print(json.dumps({'failure': 'REPORT_WRITE_FAILED'}))
            return 2
    print(encoded)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
