# Third-party notices

## haismart-local

The local protocol implementation in `driver-haismart/` (uSS message framing, biz-data encryption,
EPP frames, the UDISCOVERY codec, the classic status layout and group-set field positions) is a
Kotlin adaptation of the Python reference implementation in **haismart-local**
(<https://github.com/enapt/haismart-local>). The shared wall-split model profile in
`core-domain/.../ModelProfile.kt` is taken from the published device configuration that project
ships as a test fixture. The HaiSmart account client in `driver-haismart/.../cloud/` (sign-in request
signing and password wrapping, token refresh, device list, and the MQTT key-gateway exchange and
credential derivation) is a Kotlin re-implementation of the protocol facts documented in
haismart-local's `haismart_extractor` package (`cloud.py`, `gateway.py`).

```
MIT License

Copyright (c) 2026 haismart-local contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Libraries

| Library | Licence |
|---|---|
| AndroidX (Core, Activity, Lifecycle, Browser, Compose, Material 3) | Apache 2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache 2.0 |

Versions and test-only libraries: docs/LICENCES.md. (CameraX and ZXing were removed in 2.2.0.)

This project is not affiliated with or endorsed by Haier, Gree or Midea.

## greeclimate (reference for the Gree protocol, no code copied)

`driver-gree/` is an independent Kotlin implementation of the Gree Wi-Fi module LAN protocol. The
protocol facts it relies on (UDP port 7000, the scan/bind/status/cmd JSON envelopes, the public
generic encryption keys, the GCM nonce and AAD, property names such as `Pow`/`Mod`/`SetTem`, value
tables and the `TemSen` +40 offset heuristic) were cross-checked on 2026-11-12 against
**greeclimate** (<https://github.com/cmroche/greeclimate>), which is licensed under the
**GNU GPL v3**. No greeclimate source code was copied or translated line by line; only
interoperability facts were used.

> Licensing note for the project owner: if you plan to distribute this app, have the relationship to
> GPL-licensed references reviewed. The intention here is that facts about a wire protocol are not
> copyrightable, but this is not legal advice.

## msmart-ng (Midea protocol)

`driver-midea/` is a Kotlin adaptation of **midea-msmart / msmart-ng**
(<https://github.com/mill1000/midea-msmart>), revision `dd09947` (2026-11-09):

- the LAN discovery request and reply parsing (`discover.py`, `const.py`);
- the V2/V3 packet layers, V3 handshake and fixed LAN cipher (`lan.py`);
- the frame checksum and CRC-8 (`frame.py`, `crc8.py`);
- the AC state query, state write and state reply layout (`device/AC/command.py`);
- the NetHome Plus request signing, login and token request (`cloud.py`).

The library's built-in shared cloud accounts were deliberately not carried over. Test vectors in
`driver-midea/src/test` are captured traffic published in msmart-ng's own test suite.

```
MIT License

Copyright (c) 2022-2026 Tucker Kern
Copyright (c) 2020 Colin Kuebler (@kueblc)
Copyright (c) 2020 Mac_Zhou (@mac-zhou)
Copyright (c) 2018 NeoAcheron

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
