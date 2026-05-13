package maichess.engine

import zio.*
import zio.test.*
import maichess.engine.chess.{EvalV2, Position}

object EvalV2Spec extends ZIOSpecDefault:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def fen(s: String) = ZIO.fromEither(Position.fromFen(s))

  def spec = suite("EvalV2")(

    test("starting position evaluates to exactly 0 (mirror symmetry)") {
      for pos <- fen(startFen)
      yield assertTrue(EvalV2.evaluate(pos) == 0)
    },

    test("symmetric mirrored positions evaluate to 0") {
      for pos <- fen("4k3/pppppppp/8/8/8/8/PPPPPPPP/4K3 w - - 0 1")
      yield assertTrue(EvalV2.evaluate(pos) == 0)
    },

    test("returns negative when down material") {
      // White has no queen, black has its queen — heavy material deficit for white.
      for pos <- fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR w KQkq - 0 1")
      yield assertTrue(EvalV2.evaluate(pos) < 0)
    },

    test("returns positive when up material") {
      // Black has no queen; white still does.
      for pos <- fen("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
      yield assertTrue(EvalV2.evaluate(pos) > 0)
    },

    test("doubled pawn: doubled white pawn evaluates worse than non-doubled") {
      // Both positions have 2 white pawns and 0 black pawns. In `doubled` the
      // pawns share the d-file; in `clean` they're on adjacent files. The only
      // structural difference is the doubled-pawn penalty.
      for
        doubled <- fen("4k3/8/8/8/3P4/3P4/8/4K3 w - - 0 1")
        clean   <- fen("4k3/8/8/8/3P4/4P3/8/4K3 w - - 0 1")
      yield assertTrue(EvalV2.evaluate(doubled) < EvalV2.evaluate(clean))
    },

    test("isolated pawn: isolated white pawn evaluates worse than connected pair") {
      // Isolated: white pawn on d4 alone, no pawns on c or e.
      // Connected: white pawn on d4 plus a pawn on e4 (same material count of 1+1).
      for
        isolated  <- fen("4k3/8/8/8/3P4/3K4/8/8 w - - 0 1")
        connected <- fen("4k3/8/8/8/3PP3/3K4/8/8 w - - 0 1")
      yield
        // The isolated single pawn is penalised; the connected pair is not.
        // Compare per-pawn-normalised scores by checking that *adding* a friend
        // (which removes the isolated penalty AND adds a pawn) is more than just
        // adding a pawn's worth (≈100cp) of material.
        val gain = EvalV2.evaluate(connected) - EvalV2.evaluate(isolated)
        assertTrue(gain > 100)
    },

    test("passed pawn: advanced passed pawn scores higher than back-rank passed pawn") {
      // Advanced passed pawn on a7 (one square from queening) vs same pawn on a2.
      for
        advanced <- fen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        backRank <- fen("4k3/8/8/8/8/8/P7/4K3 w - - 0 1")
      yield assertTrue(EvalV2.evaluate(advanced) > EvalV2.evaluate(backRank))
    },

    test("king safety: castled king evaluates better than centralised king") {
      // Equivalent material/positions. Same piece count on both sides; only the
      // white king's position differs (castled vs central, with shield pawns
      // intact in both).
      for
        castled <- fen("rnbq1rk1/ppp2ppp/3p1n2/4p3/4P3/3P1N2/PPP2PPP/RNBQ1RK1 w - - 0 1")
        central <- fen("rnbq1rk1/ppp2ppp/3p1n2/4p3/4P3/3P1N2/PPP1KPPP/RNBQ3R w - - 0 1")
      yield assertTrue(EvalV2.evaluate(castled) > EvalV2.evaluate(central))
    },

    test("rook on open file: open-file rook scores higher than blocked rook") {
      // Equal material. Only difference: which pawn sits on the a-file vs b-file.
      // `openF` shifts the white pawn off the a-file (a-file open under the rook).
      // `blocked` keeps a white pawn on a2 (blocking the rook on the a-file).
      for
        openF   <- fen("4k3/8/8/8/8/8/1PP5/R3K3 w - - 0 1")
        blocked <- fen("4k3/8/8/8/8/8/PP6/R3K3 w - - 0 1")
      yield assertTrue(EvalV2.evaluate(openF) > EvalV2.evaluate(blocked))
    },

    test("evaluation is from side-to-move perspective") {
      // The same position with the side-to-move flipped should negate the score.
      for
        whiteToMove <- fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR w KQkq - 0 1")
        blackToMove <- fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR b KQkq - 0 1")
      yield assertTrue(EvalV2.evaluate(whiteToMove) == -EvalV2.evaluate(blackToMove))
    },
  )
