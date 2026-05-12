package maichess.engine.domain

import maichess.engine.domain.TimingStrategy.*
import maichess.engine.domain.EngineVariant.*

object BotRegistry:

  val all: List[BotConfig] = List(

    // ── Tier 1 (Base) bots — bitboard alpha-beta ──────────────────────────────

    BotConfig(
      id          = "bullet",
      name        = "Bullet",
      elo         = 1400,
      strategy    = Fixed(100L),
      variant     = Base,
      description = "A fully-featured bitboard engine that represents the board as packed 64-bit integers and analyses all pieces simultaneously using hardware instructions. A transposition table ensures positions are never evaluated twice, and captures are followed beyond the main search depth to prevent horizon blunders. Allocates a flat 100 ms per move — fast and instinctive.",
    ),
    BotConfig(
      id          = "bullet_proportional",
      name        = "Bullet Proportional",
      elo         = 1400,
      strategy    = Proportional(divisor = 40, minMs = 50L, fallbackMs = 100L),
      variant     = Base,
      description = "A fully-featured bitboard engine with transposition table and quiescence search. Distributes its remaining time proportionally across the estimated moves left in the game — a more clock-aware approach than a fixed allocation.",
    ),
    BotConfig(
      id          = "bullet_aggressive",
      name        = "Bullet Aggressive",
      elo         = 1400,
      strategy    = Aggressive(fraction = 0.07, minMs = 50L, fallbackMs = 100L),
      variant     = Base,
      description = "A fully-featured bitboard engine with transposition table and quiescence search. Spends 7% of remaining time per move, front-loading calculation into the early game where move choices are most consequential.",
    ),
    BotConfig(
      id          = "blitz",
      name        = "Blitz",
      elo         = 1700,
      strategy    = Fixed(1000L),
      variant     = Base,
      description = "A fully-featured bitboard engine using magic bitboards for instant sliding-piece attack lookup and a transposition table to avoid repeated work. With 1 second per move it reaches depths where it reliably handles basic tactics and applies positional pressure. A solid club-player level opponent.",
    ),
    BotConfig(
      id          = "blitz_proportional",
      name        = "Blitz Proportional",
      elo         = 1700,
      strategy    = Proportional(divisor = 30, minMs = 200L, fallbackMs = 1000L),
      variant     = Base,
      description = "A fully-featured bitboard engine at blitz speed. Proportional time management means it invests its clock across the whole game, allocating roughly equal time per phase rather than rushing in the endgame.",
    ),
    BotConfig(
      id          = "blitz_aggressive",
      name        = "Blitz Aggressive",
      elo         = 1700,
      strategy    = Aggressive(fraction = 0.06, minMs = 200L, fallbackMs = 1000L),
      variant     = Base,
      description = "A fully-featured bitboard engine at blitz speed. Uses 6% of remaining time per move — thinking most in the opening and middlegame, then playing quicker as the position simplifies.",
    ),
    BotConfig(
      id          = "classical",
      name        = "Classical",
      elo         = 2000,
      strategy    = Fixed(5000L),
      variant     = Base,
      description = "The bitboard engine at its most patient: 5 seconds per move allows significantly deeper searches than the blitz variant. Capable of finding multi-move combinations and applying coherent positional pressure across the whole game. A strong club-level opponent.",
    ),
    BotConfig(
      id          = "classical_proportional",
      name        = "Classical Proportional",
      elo         = 2000,
      strategy    = Proportional(divisor = 25, minMs = 500L, fallbackMs = 5000L),
      variant     = Base,
      description = "The bitboard engine at classical pace with proportional time management. Adapts how long it thinks based on game progress — a thoughtful, clock-aware engine that never rushes the endgame.",
    ),
    BotConfig(
      id          = "classical_aggressive",
      name        = "Classical Aggressive",
      elo         = 2000,
      strategy    = Aggressive(fraction = 0.05, minMs = 500L, fallbackMs = 5000L),
      variant     = Base,
      description = "The bitboard engine at classical pace. Spends 5% of remaining time per move — a front-loaded approach that ensures its deepest thinking happens in the most critical phases of the game.",
    ),

    // ── Tier 0 (Basic) bots — mailbox minimax, no bitboards ──────────────────

    BotConfig(
      id          = "basic_bullet",
      name        = "Basic Bullet",
      elo         = 700,
      strategy    = Fixed(100L),
      variant     = Basic,
      description = "A bare-bones engine that sees chess purely as material on a board. No bitboards, no position memory, no move ordering — just brute-force minimax with plain material counting. Allocates a flat 100 ms per move, making every decision as fast and honest as possible.",
    ),
    BotConfig(
      id          = "basic_bullet_prop",
      name        = "Basic Bullet Prop",
      elo         = 700,
      strategy    = Proportional(divisor = 40, minMs = 50L, fallbackMs = 100L),
      variant     = Basic,
      description = "A bare-bones engine that sees chess purely as material on a board. No bitboards, no position memory, no move ordering — just brute-force minimax with plain material counting. Divides remaining time proportionally across expected moves remaining so it never burns the clock early.",
    ),
    BotConfig(
      id          = "basic_bullet_aggr",
      name        = "Basic Bullet Aggressive",
      elo         = 700,
      strategy    = Aggressive(fraction = 0.07, minMs = 50L, fallbackMs = 100L),
      variant     = Basic,
      description = "A bare-bones engine that sees chess purely as material on a board. No bitboards, no position memory, no move ordering — just brute-force minimax with plain material counting. Spends 7% of remaining time per move, front-loading calculation into the opening and thinning out as the game nears its end.",
    ),
    BotConfig(
      id          = "basic_blitz",
      name        = "Basic Blitz",
      elo         = 800,
      strategy    = Fixed(1000L),
      variant     = Basic,
      description = "The simplest engine in the roster — a plain 8×8 board array with material-only evaluation and no position memory. Without a transposition table it re-analyses the same positions repeatedly, but 1 second per move lets it reach a few extra plies of depth, making it a small but visible step up in tactical sharpness.",
    ),
    BotConfig(
      id          = "basic_blitz_prop",
      name        = "Basic Blitz Prop",
      elo         = 800,
      strategy    = Proportional(divisor = 30, minMs = 200L, fallbackMs = 1000L),
      variant     = Basic,
      description = "The simplest engine in the roster — a plain 8×8 board array with material-only evaluation and no position memory. Spreads its clock evenly across the game, adapting move time to how many moves it estimates are left.",
    ),
    BotConfig(
      id          = "basic_blitz_aggr",
      name        = "Basic Blitz Aggressive",
      elo         = 800,
      strategy    = Aggressive(fraction = 0.06, minMs = 200L, fallbackMs = 1000L),
      variant     = Basic,
      description = "The simplest engine in the roster — a plain 8×8 board array with material-only evaluation and no position memory. Uses 6% of remaining time per move — a middle ground between consistent pacing and front-loaded thinking.",
    ),
    BotConfig(
      id          = "basic_classical",
      name        = "Basic Classical",
      elo         = 900,
      strategy    = Fixed(5000L),
      variant     = Basic,
      description = "The bare-bones engine at its most patient: 5 seconds per move lets it reach its deepest searches, but the lack of position memory and primitive evaluation still cap its strength well below club level. A good starting opponent for beginners or anyone curious about how much modern optimisations actually matter.",
    ),
    BotConfig(
      id          = "basic_classical_prop",
      name        = "Basic Classical Prop",
      elo         = 900,
      strategy    = Proportional(divisor = 25, minMs = 500L, fallbackMs = 5000L),
      variant     = Basic,
      description = "The bare-bones engine at its most patient. Distributes its clock proportionally — in a typical game it invests roughly 5 seconds per move, with the flexibility to think longer if fewer moves remain, avoiding any risk of running out of time.",
    ),
    BotConfig(
      id          = "basic_classical_aggr",
      name        = "Basic Classical Aggr",
      elo         = 900,
      strategy    = Aggressive(fraction = 0.05, minMs = 500L, fallbackMs = 5000L),
      variant     = Basic,
      description = "The bare-bones engine at its most patient. Spends 5% of remaining time each move, which at the start of a fresh game amounts to about 5 seconds — comparable to the fixed variant but with a natural taper as the clock runs down.",
    ),
  )

  def find(id: String): Option[BotConfig] = all.find(_.id == id)
