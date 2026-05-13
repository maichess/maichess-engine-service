# Opening Books

## performance.bin

Polyglot opening book bundled with the Knowledge-tier bots. The Polyglot
binary format is sorted by 8-byte Zobrist key; each entry is 16 bytes
(key + move + weight + learn).

- **Source**: <https://github.com/michaeldv/donna_opening_books> (`gm2001.bin`)
- **License**: public domain (no restrictions stated in the upstream repository)
- **Size**: ~475 KB

Renamed to `performance.bin` on the classpath for consistency with the engine's
`OpeningBook` loader. Replace with any other Polyglot `.bin` file (matching the
strict Polyglot Zobrist convention — XOR EP file only when capture is
possible) to swap repertoires.
