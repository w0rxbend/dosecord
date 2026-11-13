# Third-party notices

The Maven dependency tree is checked by `scripts/license-gate.sh`. This file lists what that script cannot see: font
files vendored in the repository and native tools the render pipeline runs as child processes. A test
(`report-renderer.test`, `TextSuite`) fails when a file in `report-renderer/fonts` is missing from this list or its
checksum differs.

## Fonts

All fonts are licensed under the SIL Open Font License, Version 1.1. The licence texts are stored beside the files.

| File | Family | Source | Licence text | SHA-256 |
|---|---|---|---|---|
| `report-renderer/fonts/IBMPlexSans-Regular.ttf` | IBM Plex Sans, weight 400 | https://github.com/IBM/plex | `report-renderer/fonts/OFL-IBM-Plex.txt` | `b47ebd72b5ce53b60f909033ff84d6fc12358cc147e2981f8c321ad3f36246b6` |
| `report-renderer/fonts/IBMPlexSans-Medium.ttf` | IBM Plex Sans, weight 500 | https://github.com/IBM/plex | `report-renderer/fonts/OFL-IBM-Plex.txt` | `a494d5a69fa094f7071a9ffbc98e86c85337d1a29f3fc1ff7c2e1c46161ddf27` |
| `report-renderer/fonts/IBMPlexSans-SemiBold.ttf` | IBM Plex Sans, weight 600 | https://github.com/IBM/plex | `report-renderer/fonts/OFL-IBM-Plex.txt` | `c51119b25f4a296da2b66edf3201b9aa1735091ed7efc1fca0313346f3851a4d` |
| `report-renderer/fonts/IBMPlexSansCondensed-Medium.ttf` | IBM Plex Sans Condensed, weight 500 | https://github.com/IBM/plex | `report-renderer/fonts/OFL-IBM-Plex.txt` | `afbf818e40448e28a979a58aa01c600e066a91661521a69589293f284068722c` |
| `report-renderer/fonts/Geologica-SemiBold.ttf` | Geologica, weight 600 | https://github.com/googlefonts/geologica | `report-renderer/fonts/OFL-Geologica.txt` | `b91a06c78559e76112c88a3131de912a253ea11d58e2120f55a61c479f604fe5` |

Copyright: IBM Plex is Copyright 2017 IBM Corp. with Reserved Font Name "Plex". Geologica is Copyright 2020 The
Geologisk Project Authors.

## Native tools run as child processes

| Tool | Version | Licence | Use |
|---|---|---|---|
| resvg (https://github.com/linebender/resvg) | 0.49.0 | Apache-2.0 OR MIT | Rasterises the SVG of a card to PNG (`report-renderer.resvg`). The binary is not stored in the repository. |
