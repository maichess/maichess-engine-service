package maichess.engine.domain

import maichess.engine.domain.TimingStrategy.*

object BotRegistry:

  val all: List[BotConfig] = List(
    BotConfig(
      id          = "bullet",
      name        = "Bullet",
      elo         = 1400,
      strategy    = Fixed(100L),
      description = "A fully-featured bitboard engine that represents the board as packed 64-bit integers and analyses all pieces simultaneously using hardware instructions. A transposition table ensures positions are never evaluated twice, and captures are followed beyond the main search depth to prevent horizon blunders. Allocates a flat 100 ms per move — fast and instinctive.",
    ),
    BotConfig(
      id          = "bullet_proportional",
      name        = "Bullet Proportional",
      elo         = 1400,
      strategy    = Proportional(divisor = 40, minMs = 50L, fallbackMs = 100L),
      description = "A fully-featured bitboard engine with transposition table and quiescence search. Distributes its remaining time proportionally across the estimated moves left in the game — a more clock-aware approach than a fixed allocation.",
    ),
    BotConfig(
      id          = "bullet_aggressive",
      name        = "Bullet Aggressive",
      elo         = 1400,
      strategy    = Aggressive(fraction = 0.07, minMs = 50L, fallbackMs = 100L),
      description = "A fully-featured bitboard engine with transposition table and quiescence search. Spends 7% of remaining time per move, front-loading calculation into the early game where move choices are most consequential.",
    ),
    BotConfig(
      id          = "blitz",
      name        = "Blitz",
      elo         = 1700,
      strategy    = Fixed(1000L),
      description = "A fully-featured bitboard engine using magic bitboards for instant sliding-piece attack lookup and a transposition table to avoid repeated work. With 1 second per move it reaches depths where it reliably handles basic tactics and applies positional pressure. A solid club-player level opponent.",
    ),
    BotConfig(
      id          = "blitz_proportional",
      name        = "Blitz Proportional",
      elo         = 1700,
      strategy    = Proportional(divisor = 30, minMs = 200L, fallbackMs = 1000L),
      description = "A fully-featured bitboard engine at blitz speed. Proportional time management means it invests its clock across the whole game, allocating roughly equal time per phase rather than rushing in the endgame.",
    ),
    BotConfig(
      id          = "blitz_aggressive",
      name        = "Blitz Aggressive",
      elo         = 1700,
      strategy    = Aggressive(fraction = 0.06, minMs = 200L, fallbackMs = 1000L),
      description = "A fully-featured bitboard engine at blitz speed. Uses 6% of remaining time per move — thinking most in the opening and middlegame, then playing quicker as the position simplifies.",
    ),
    BotConfig(
      id          = "classical",
      name        = "Classical",
      elo         = 2000,
      strategy    = Fixed(5000L),
      description = "The bitboard engine at its most patient: 5 seconds per move allows significantly deeper searches than the blitz variant. Capable of finding multi-move combinations and applying coherent positional pressure across the whole game. A strong club-level opponent.",
    ),
    BotConfig(
      id          = "classical_proportional",
      name        = "Classical Proportional",
      elo         = 2000,
      strategy    = Proportional(divisor = 25, minMs = 500L, fallbackMs = 5000L),
      description = "The bitboard engine at classical pace with proportional time management. Adapts how long it thinks based on game progress — a thoughtful, clock-aware engine that never rushes the endgame.",
    ),
    BotConfig(
      id          = "classical_aggressive",
      name        = "Classical Aggressive",
      elo         = 2000,
      strategy    = Aggressive(fraction = 0.05, minMs = 500L, fallbackMs = 5000L),
      description = "The bitboard engine at classical pace. Spends 5% of remaining time per move — a front-loaded approach that ensures its deepest thinking happens in the most critical phases of the game.",
    ),
  )

  def find(id: String): Option[BotConfig] = all.find(_.id == id)
