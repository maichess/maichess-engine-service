package maichess.engine.chess

// Magic bitboards for O(1) sliding-piece attack lookup.
// Tables are built once at JVM startup via PRNG trial-and-error magic-finding.
@SuppressWarnings(Array("org.wartremover.warts.Var"))
object Magics:

  private val rookTable:   Array[Array[Long]] = Array.ofDim(64, 4096)
  private val bishopTable: Array[Array[Long]] = Array.ofDim(64, 512)
  val  rookMasks:   Array[Long] = new Array(64)
  val  bishopMasks: Array[Long] = new Array(64)
  private val rookMagics:   Array[Long] = new Array(64)
  private val bishopMagics: Array[Long] = new Array(64)
  private val rookShifts:   Array[Int]  = new Array(64)
  private val bishopShifts: Array[Int]  = new Array(64)
  private val rookDirs:   Array[(Int, Int)] = Array((1,0),(-1,0),(0,1),(0,-1))
  private val bishopDirs: Array[(Int, Int)] = Array((1,1),(1,-1),(-1,1),(-1,-1))
  private var seed = 0x123456789ABCDEF0L   // xorshift64 seed (must be non-zero)

  locally { init() }

  inline def rookAttacks(sq: Int, occ: Long): Long =
    rookTable(sq)(((occ & rookMasks(sq)) * rookMagics(sq) >>> rookShifts(sq)).toInt)

  inline def bishopAttacks(sq: Int, occ: Long): Long =
    bishopTable(sq)(((occ & bishopMasks(sq)) * bishopMagics(sq) >>> bishopShifts(sq)).toInt)

  private def rookMask(sq: Int): Long =
    val f = sq & 7; val r = sq >> 3
    val filePart = (BB.FileA << f) & ~BB.Rank1 & ~BB.Rank8
    val rankPart = (BB.Rank1  << (r * 8)) & ~BB.FileA & ~BB.FileH
    (filePart | rankPart) & ~(1L << sq)

  private def bishopMask(sq: Int): Long =
    var bb = 0L; val f = sq & 7; val r = sq >> 3
    for df <- Array(-1, 1); dr <- Array(-1, 1) do
      var cf = f + df; var cr = r + dr
      while cf >= 1 && cf <= 6 && cr >= 1 && cr <= 6 do
        bb |= 1L << ((cr << 3) | cf); cf += df; cr += dr
    bb

  // Ray-trace attacks along dirs from sq, stopping at first blocker (inclusive).
  private def slideAttacks(sq: Int, occ: Long, dirs: Array[(Int, Int)]): Long =
    var atk = 0L
    for (df, dr) <- dirs do
      var f = (sq & 7) + df; var r = (sq >> 3) + dr
      while f >= 0 && f < 8 && r >= 0 && r < 8 do
        val bit = 1L << ((r << 3) | f); atk |= bit
        if (occ & bit) != 0L then { f = -1; r = -1 } else { f += df; r += dr }
    atk

  // Xorshift64 PRNG; AND of 3 values gives sparse magic candidates.
  private def nextSparse: Long =
    def next(): Long = { seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17; seed }
    next() & next() & next()

  private inline def forSubsets(mask: Long)(f: Long => Unit): Unit =
    var subset = 0L; var going = true
    while going do
      f(subset)
      subset = (subset - mask) & mask
      going = subset != 0L

  private def findMagic(sq: Int, mask: Long, bits: Int, isRook: Boolean): Long =
    val n    = 1 << bits
    val occ  = new Array[Long](n)
    val atk  = new Array[Long](n)
    val used = new Array[Long](n)
    val dirs = if isRook then rookDirs else bishopDirs
    var i = 0
    forSubsets(mask) { subset => occ(i) = subset; atk(i) = slideAttacks(sq, subset, dirs); i += 1 }
    var magic = 0L; var found = false
    while !found do
      magic = nextSparse
      java.util.Arrays.fill(used, 0L)
      found = true; var j = 0
      while j < n && found do
        val idx = ((occ(j) * magic) >>> (64 - bits)).toInt
        if used(idx) == 0L then used(idx) = atk(j)
        else if used(idx) != atk(j) then found = false
        j += 1
    magic

  private def fillTable(sq: Int, mask: Long, magic: Long, shift: Int, isRook: Boolean): Unit =
    val table = if isRook then rookTable(sq) else bishopTable(sq)
    val dirs  = if isRook then rookDirs else bishopDirs
    forSubsets(mask) { subset =>
      table(((subset * magic) >>> shift).toInt) = slideAttacks(sq, subset, dirs)
    }

  private def init(): Unit =
    var sq = 0
    while sq < 64 do
      val rm = rookMask(sq); val bm = bishopMask(sq)
      rookMasks(sq)  = rm;  bishopMasks(sq)  = bm
      val rb = java.lang.Long.bitCount(rm); val bb = java.lang.Long.bitCount(bm)
      rookShifts(sq)   = 64 - rb; bishopShifts(sq) = 64 - bb
      rookMagics(sq)   = findMagic(sq, rm, rb, true)
      bishopMagics(sq) = findMagic(sq, bm, bb, false)
      fillTable(sq, rm, rookMagics(sq),   rookShifts(sq),   true)
      fillTable(sq, bm, bishopMagics(sq), bishopShifts(sq), false)
      sq += 1
