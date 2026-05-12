package maichess.engine.chess.basic

// Pseudo-legal move generator for the mailbox board.
// Direction arrays replace bitboard tricks — each sliding piece walks its rays
// square by square until blocked or off the board.
//
// generateAll / generateCaptures produce pseudo-legal moves.
// isInCheck checks whether a side's king is under attack.
// legalMoves filters generateAll to only moves that leave the moving king safe.
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
object BasicMoveGen:
  import BasicPiece.*
  import BasicFlag.*

  // Direction tables: index i gives (rank-delta, file-delta)
  private val rookDr   = Array( 1, -1,  0,  0)
  private val rookDf   = Array( 0,  0,  1, -1)
  private val bishopDr = Array( 1,  1, -1, -1)
  private val bishopDf = Array( 1, -1,  1, -1)
  private val queenDr  = Array( 1, -1,  0,  0,  1,  1, -1, -1)
  private val queenDf  = Array( 0,  0,  1, -1,  1, -1,  1, -1)
  private val knightDr = Array( 2,  2, -2, -2,  1,  1, -1, -1)
  private val knightDf = Array( 1, -1,  1, -1,  2, -2,  2, -2)
  private val kingDr   = Array( 1, -1,  0,  0,  1,  1, -1, -1)
  private val kingDf   = Array( 0,  0,  1, -1,  1, -1,  1, -1)

  def generateAll(pos: BasicPosition): List[BasicMove] =
    var result: List[BasicMove] = Nil
    var sq = 0
    while sq < 64 do
      val piece = pos.board(sq)
      if piece * pos.sideToMove > 0 then
        result = generateForSquare(pos, sq, piece, result, capturesOnly = false)
      sq += 1
    result

  def generateCaptures(pos: BasicPosition): List[BasicMove] =
    var result: List[BasicMove] = Nil
    var sq = 0
    while sq < 64 do
      val piece = pos.board(sq)
      if piece * pos.sideToMove > 0 then
        result = generateForSquare(pos, sq, piece, result, capturesOnly = true)
      sq += 1
    result

  def legalMoves(pos: BasicPosition): List[BasicMove] =
    val side = pos.sideToMove
    generateAll(pos).filter { mv =>
      val saved = pos.makeMove(mv)
      val legal = !isInCheck(pos, side)
      pos.unmakeMove(mv, saved)
      legal
    }

  def legalCaptures(pos: BasicPosition): List[BasicMove] =
    val side = pos.sideToMove
    generateCaptures(pos).filter { mv =>
      val saved = pos.makeMove(mv)
      val legal = !isInCheck(pos, side)
      pos.unmakeMove(mv, saved)
      legal
    }

  // Returns true if `side`'s king is attacked by the opponent.
  def isInCheck(pos: BasicPosition, side: Int): Boolean =
    val kingPiece = side * WKing
    var kSq = -1
    var i   = 0
    while i < 64 && kSq < 0 do
      if pos.board(i) == kingPiece then kSq = i
      i += 1
    kSq < 0 || isSquareAttacked(pos, kSq, -side)

  // True if `targetSq` is attacked by any piece of `bySide`.
  private def isSquareAttacked(pos: BasicPosition, targetSq: Int, bySide: Int): Boolean =
    val tr = targetSq / 8
    val tf = targetSq % 8

    // Pawn attacks: a pawn of bySide at rank (tr - bySide) on adjacent files attacks targetSq.
    val pr = tr - bySide
    if pr >= 0 && pr < 8 then
      val pawnPiece = bySide * WPawn
      if tf > 0 && pos.board(pr * 8 + tf - 1) == pawnPiece then return true
      if tf < 7 && pos.board(pr * 8 + tf + 1) == pawnPiece then return true

    // Knight attacks
    val knightPiece = bySide * WKnight
    var d = 0
    while d < 8 do
      val nr = tr + knightDr(d); val nf = tf + knightDf(d)
      if nr >= 0 && nr < 8 && nf >= 0 && nf < 8 then
        if pos.board(nr * 8 + nf) == knightPiece then return true
      d += 1

    // King attacks (prevents castling into adjacent enemy king)
    val kingPiece = bySide * WKing
    d = 0
    while d < 8 do
      val nr = tr + kingDr(d); val nf = tf + kingDf(d)
      if nr >= 0 && nr < 8 && nf >= 0 && nf < 8 then
        if pos.board(nr * 8 + nf) == kingPiece then return true
      d += 1

    // Rook / queen on orthogonal rays
    val rookPiece  = bySide * WRook
    val queenPiece = bySide * WQueen
    d = 0
    while d < 4 do
      var nr = tr + rookDr(d); var nf = tf + rookDf(d)
      var stop = false
      while !stop && nr >= 0 && nr < 8 && nf >= 0 && nf < 8 do
        val p = pos.board(nr * 8 + nf)
        if p == rookPiece || p == queenPiece then return true
        else if p != Empty then stop = true
        else { nr += rookDr(d); nf += rookDf(d) }
      d += 1

    // Bishop / queen on diagonal rays
    val bishopPiece = bySide * WBishop
    d = 0
    while d < 4 do
      var nr = tr + bishopDr(d); var nf = tf + bishopDf(d)
      var stop = false
      while !stop && nr >= 0 && nr < 8 && nf >= 0 && nf < 8 do
        val p = pos.board(nr * 8 + nf)
        if p == bishopPiece || p == queenPiece then return true
        else if p != Empty then stop = true
        else { nr += bishopDr(d); nf += bishopDf(d) }
      d += 1

    false

  private def generateForSquare(
    pos:          BasicPosition,
    from:         Int,
    piece:        Int,
    acc:          List[BasicMove],
    capturesOnly: Boolean,
  ): List[BasicMove] =
    Math.abs(piece) match
      case 1 => generatePawnMoves(pos, from, acc, capturesOnly)
      case 2 => generateJumps(pos, from, knightDr, knightDf, acc, capturesOnly)
      case 3 => generateSliding(pos, from, bishopDr, bishopDf, acc, capturesOnly)
      case 4 => generateSliding(pos, from, rookDr, rookDf, acc, capturesOnly)
      case 5 => generateSliding(pos, from, queenDr, queenDf, acc, capturesOnly)
      case 6 => generateKingMoves(pos, from, acc, capturesOnly)
      case _ => acc

  private def generatePawnMoves(
    pos:          BasicPosition,
    from:         Int,
    acc:          List[BasicMove],
    capturesOnly: Boolean,
  ): List[BasicMove] =
    val side      = pos.sideToMove
    val r         = from / 8
    val f         = from % 8
    val startRank = if side == 1 then 1 else 6
    val promoRank = if side == 1 then 6 else 1
    val dir       = 8 * side

    var result = acc

    if !capturesOnly then
      val to1 = from + dir
      if pos.board(to1) == Empty then
        if r == promoRank then
          result = BasicMove(from, to1, WQueen,  Quiet) :: result
          result = BasicMove(from, to1, WRook,   Quiet) :: result
          result = BasicMove(from, to1, WBishop, Quiet) :: result
          result = BasicMove(from, to1, WKnight, Quiet) :: result
        else
          result = BasicMove(from, to1, 0, Quiet) :: result
          if r == startRank then
            val to2 = from + 2 * dir
            if pos.board(to2) == Empty then
              result = BasicMove(from, to2, 0, DoublePush) :: result

    // Diagonal captures: NE/SE (file+1) and NW/SW (file-1) relative to the pawn's attack direction
    val captureDeltas = Array(dir + 1, dir - 1)
    val fileShifts    = Array(1, -1)
    var d = 0
    while d < 2 do
      val tf = f + fileShifts(d)
      if tf >= 0 && tf < 8 then
        val to   = from + captureDeltas(d)
        val isEP = to == pos.epSquare
        val isCapture = pos.board(to) * side < 0
        if isEP || isCapture then
          val flag = if isEP then EP else Capture
          if r == promoRank then
            result = BasicMove(from, to, WQueen,  flag) :: result
            result = BasicMove(from, to, WRook,   flag) :: result
            result = BasicMove(from, to, WBishop, flag) :: result
            result = BasicMove(from, to, WKnight, flag) :: result
          else
            result = BasicMove(from, to, 0, flag) :: result
      d += 1
    result

  private def generateJumps(
    pos:          BasicPosition,
    from:         Int,
    dr:           Array[Int],
    df:           Array[Int],
    acc:          List[BasicMove],
    capturesOnly: Boolean,
  ): List[BasicMove] =
    val r = from / 8; val f = from % 8
    var result = acc
    var d = 0
    while d < dr.length do
      val nr = r + dr(d); val nf = f + df(d)
      if nr >= 0 && nr < 8 && nf >= 0 && nf < 8 then
        val to     = nr * 8 + nf
        val target = pos.board(to)
        if target * pos.sideToMove <= 0 then
          val isCapture = target != Empty
          if !capturesOnly || isCapture then
            val flag = if isCapture then Capture else Quiet
            result = BasicMove(from, to, 0, flag) :: result
      d += 1
    result

  private def generateSliding(
    pos:          BasicPosition,
    from:         Int,
    dr:           Array[Int],
    df:           Array[Int],
    acc:          List[BasicMove],
    capturesOnly: Boolean,
  ): List[BasicMove] =
    val r = from / 8; val f = from % 8
    var result = acc
    var d = 0
    while d < dr.length do
      var nr = r + dr(d); var nf = f + df(d)
      var stop = false
      while !stop && nr >= 0 && nr < 8 && nf >= 0 && nf < 8 do
        val to     = nr * 8 + nf
        val target = pos.board(to)
        if target * pos.sideToMove > 0 then
          stop = true
        else if target == Empty then
          if !capturesOnly then result = BasicMove(from, to, 0, Quiet) :: result
          nr += dr(d); nf += df(d)
        else
          result = BasicMove(from, to, 0, Capture) :: result
          stop = true
      d += 1
    result

  private def generateKingMoves(
    pos:          BasicPosition,
    from:         Int,
    acc:          List[BasicMove],
    capturesOnly: Boolean,
  ): List[BasicMove] =
    var result = generateJumps(pos, from, kingDr, kingDf, acc, capturesOnly)
    if !capturesOnly then result = generateCastling(pos, from, result)
    result

  private def generateCastling(pos: BasicPosition, from: Int, acc: List[BasicMove]): List[BasicMove] =
    var result = acc
    if pos.sideToMove == 1 && from == 4 then
      if (pos.castling & 1) != 0 && pos.board(5) == Empty && pos.board(6) == Empty && pos.board(7) == WRook then
        if !isSquareAttacked(pos, 4, -1) && !isSquareAttacked(pos, 5, -1) then
          result = BasicMove(4, 6, 0, Castle) :: result
      if (pos.castling & 2) != 0 && pos.board(3) == Empty && pos.board(2) == Empty && pos.board(1) == Empty && pos.board(0) == WRook then
        if !isSquareAttacked(pos, 4, -1) && !isSquareAttacked(pos, 3, -1) then
          result = BasicMove(4, 2, 0, Castle) :: result
    else if pos.sideToMove == -1 && from == 60 then
      if (pos.castling & 4) != 0 && pos.board(61) == Empty && pos.board(62) == Empty && pos.board(63) == BRook then
        if !isSquareAttacked(pos, 60, 1) && !isSquareAttacked(pos, 61, 1) then
          result = BasicMove(60, 62, 0, Castle) :: result
      if (pos.castling & 8) != 0 && pos.board(59) == Empty && pos.board(58) == Empty && pos.board(57) == Empty && pos.board(56) == BRook then
        if !isSquareAttacked(pos, 60, 1) && !isSquareAttacked(pos, 59, 1) then
          result = BasicMove(60, 58, 0, Castle) :: result
    result
