# Networks

- `g170e-b10c128-s1141046784-d204142634.txt.gz` — a KataGo network of the g170 run (10 blocks, 128 channels, model
  version 8, ~3.0 M parameters), from KataGo's v1.3 release:
  https://github.com/lightvector/KataGo/releases/tag/v1.3 (sha-256
  `3d8a24697ba25fe4da39af4c2b6bd405907b0ad8295322f5a550fa2d8fe4a2f4`). The g170 networks are released under CC0
  ("effectively public domain"): https://katagotraining.org/network_license/. Trained by David J. Wu's KataGo.
- `katago-truth.cfg` — Homebrew KataGo's `gtp_example.cfg` (KataGo, MIT) with this game's rules written out one by
  one (positional superko, area scoring, no suicide, no friendly pass), one visit, FP32 on Metal; used by
  `net/jvm/src/test/scala/okay/go/net/Truth.scala` to make the fixture the reference is checked against.
