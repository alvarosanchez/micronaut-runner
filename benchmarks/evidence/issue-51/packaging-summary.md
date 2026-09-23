# Packaging profile

Source `d576974bedd0cbb4114a2dd17ee40af4de374822` (dirty) on Mac OS X 26.6.2 / aarch64 / JDK 25.0.3+9-LTS-jvmci-b01.

OS page-cache state is **uncontrolled**. Elapsed/allocation samples are independent from RSS/peak-heap diagnostic invocations; diagnostic elapsed time is not reported. Input/output columns are logical file bytes, not filesystem-block or kernel I/O counters.

| Workload | Compression | Scenario | Iteration | Elapsed ms | Allocated bytes | Peak heap bytes | Peak RSS bytes | Input bytes | Output bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| no-manifest | stored | first-build | 0 | 80.036 | 14680960 | 19106472 | 174358528 | 238844 | 745291 |
| no-manifest | stored | first-build | 1 | 83.774 | 14680960 | 18057896 | 173670400 | 238844 | 745291 |
| no-manifest | stored | unchanged-rebuild | 0 | 89.175 | 14681976 | 19106472 | 173555712 | 238844 | 745291 |
| no-manifest | stored | unchanged-rebuild | 1 | 81.730 | 14682160 | 19106472 | 176455680 | 238844 | 745291 |
| no-manifest | stored | application-edit | 0 | 79.468 | 14885936 | 19106472 | 176586752 | 238863 | 745525 |
| no-manifest | stored | application-edit | 1 | 77.809 | 14885936 | 19106472 | 176111616 | 238863 | 745525 |
| no-manifest | stored | dependency-edit | 0 | 85.565 | 14890200 | 19106472 | 175587328 | 239043 | 745771 |
| no-manifest | stored | dependency-edit | 1 | 88.208 | 14890200 | 19106472 | 175325184 | 239043 | 745771 |
| no-manifest | preserve | first-build | 0 | 76.357 | 14455800 | 19106472 | 174063616 | 238844 | 502422 |
| no-manifest | preserve | first-build | 1 | 80.098 | 14455624 | 19106472 | 173883392 | 238844 | 502422 |
| no-manifest | preserve | unchanged-rebuild | 0 | 80.955 | 14457000 | 19106472 | 173228032 | 238844 | 502422 |
| no-manifest | preserve | unchanged-rebuild | 1 | 79.192 | 14457000 | 19106472 | 172359680 | 238844 | 502422 |
| no-manifest | preserve | application-edit | 0 | 75.934 | 14661160 | 19106472 | 173670400 | 238863 | 502656 |
| no-manifest | preserve | application-edit | 1 | 99.044 | 14661160 | 19106472 | 171835392 | 238863 | 502656 |
| no-manifest | preserve | dependency-edit | 0 | 79.831 | 14664480 | 19106472 | 173998080 | 239043 | 502920 |
| no-manifest | preserve | dependency-edit | 1 | 94.854 | 14664480 | 19106472 | 171982848 | 239043 | 502920 |
| representative | stored | first-build | 0 | 335.661 | 117698144 | 71874776 | 243712000 | 4995658 | 25209497 |
| representative | stored | first-build | 1 | 339.265 | 118301336 | 72214536 | 245235712 | 4995658 | 25209497 |
| representative | stored | unchanged-rebuild | 0 | 337.249 | 118061296 | 72203720 | 244989952 | 4995658 | 25209497 |
| representative | stored | unchanged-rebuild | 1 | 328.553 | 117272568 | 71868504 | 243892224 | 4995658 | 25209497 |
| representative | stored | application-edit | 0 | 341.807 | 117588840 | 71535272 | 243941376 | 4995677 | 25209731 |
| representative | stored | application-edit | 1 | 318.369 | 117906312 | 71883944 | 241778688 | 4995677 | 25209731 |
| representative | stored | dependency-edit | 0 | 353.905 | 118122960 | 72230312 | 240549888 | 4995857 | 25209977 |
| representative | stored | dependency-edit | 1 | 326.979 | 117622312 | 72209032 | 241844224 | 4995857 | 25209977 |
| representative | preserve | first-build | 0 | 230.031 | 96117312 | 60437832 | 221200384 | 4995658 | 6693652 |
| representative | preserve | first-build | 1 | 271.228 | 95412128 | 60437832 | 223395840 | 4995658 | 6693652 |
| representative | preserve | unchanged-rebuild | 0 | 234.336 | 93417536 | 60437832 | 222642176 | 4995658 | 6693652 |
| representative | preserve | unchanged-rebuild | 1 | 260.259 | 94264080 | 60219384 | 222527488 | 4995658 | 6693652 |
| representative | preserve | application-edit | 0 | 266.648 | 93360176 | 60110160 | 225329152 | 4995677 | 6693886 |
| representative | preserve | application-edit | 1 | 222.945 | 96593264 | 60219384 | 222838784 | 4995677 | 6693886 |
| representative | preserve | dependency-edit | 0 | 229.920 | 96352176 | 60219384 | 223150080 | 4995857 | 6694150 |
| representative | preserve | dependency-edit | 1 | 252.942 | 95651304 | 60437832 | 221806592 | 4995857 | 6694150 |
| wide | stored | first-build | 0 | 513.031 | 289263976 | 91462312 | 268369920 | 5509325 | 15476369 |
| wide | stored | first-build | 1 | 529.362 | 288635832 | 91462312 | 270155776 | 5509325 | 15476369 |
| wide | stored | unchanged-rebuild | 0 | 518.912 | 289146088 | 91396264 | 270516224 | 5509325 | 15476369 |
| wide | stored | unchanged-rebuild | 1 | 526.397 | 288904984 | 91396264 | 270057472 | 5509325 | 15476369 |
| wide | stored | application-edit | 0 | 516.831 | 289187472 | 91330216 | 270532608 | 5509344 | 15476603 |
| wide | stored | application-edit | 1 | 543.039 | 288810128 | 91462312 | 267091968 | 5509344 | 15476603 |
| wide | stored | dependency-edit | 0 | 532.426 | 289506224 | 91462312 | 268353536 | 5509524 | 15476849 |
| wide | stored | dependency-edit | 1 | 521.145 | 289046968 | 91330216 | 269598720 | 5509524 | 15476849 |
| wide | preserve | first-build | 0 | 525.892 | 290158568 | 91071208 | 269205504 | 5509325 | 8153391 |
| wide | preserve | first-build | 1 | 480.484 | 289975344 | 91359368 | 265011200 | 5509325 | 8153391 |
| wide | preserve | unchanged-rebuild | 0 | 464.567 | 290351960 | 90799944 | 267239424 | 5509325 | 8153391 |
| wide | preserve | unchanged-rebuild | 1 | 467.451 | 290405040 | 91724232 | 268435456 | 5509325 | 8153391 |
| wide | preserve | application-edit | 0 | 473.673 | 290450480 | 91727400 | 267714560 | 5509344 | 8153625 |
| wide | preserve | application-edit | 1 | 551.484 | 290406592 | 91334200 | 268337152 | 5509344 | 8153625 |
| wide | preserve | dependency-edit | 0 | 465.281 | 290638224 | 91349496 | 265732096 | 5509524 | 8153889 |
| wide | preserve | dependency-edit | 1 | 482.499 | 290773720 | 91462296 | 267829248 | 5509524 | 8153889 |
