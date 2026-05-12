package maichess.engine

import zio.*
import zio.test.*
import zio.test.Assertion.*
import maichess.engine.chess.basic.{BasicEval, BasicPosition}

object BasicEvalSpec extends ZIOSpecDefault:

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def spec = suite("BasicEval")(

    test("starting position evaluates to 0 (symmetric material)") {
      for pos <- ZIO.fromEither(BasicPosition.fromFen(startFen))
      yield assertTrue(BasicEval.evaluate(pos) == 0)
    },

    test("white material advantage is positive for white to move") {
      // White has an extra queen compared to black
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKQNR w KQkq - 0 1"
      for pos <- ZIO.fromEither(BasicPosition.fromFen(fen))
      yield assertTrue(BasicEval.evaluate(pos) > 0)
    },

    test("black material advantage is positive for black to move") {
      // Black has an extra queen; it is black to move so eval should be positive
      val fen = "rnbqkqnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"
      for pos <- ZIO.fromEither(BasicPosition.fromFen(fen))
      yield assertTrue(BasicEval.evaluate(pos) > 0)
    },

    test("empty board with only kings evaluates to 0") {
      val fen = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
      for pos <- ZIO.fromEither(BasicPosition.fromFen(fen))
      yield assertTrue(BasicEval.evaluate(pos) == 0)
    },

    test("pawn advantage of 100 centipawns") {
      // White has one extra pawn
      val fen = "4k3/8/8/8/8/8/P7/4K3 w - - 0 1"
      for pos <- ZIO.fromEither(BasicPosition.fromFen(fen))
      yield assertTrue(BasicEval.evaluate(pos) == 100)
    },
  )
