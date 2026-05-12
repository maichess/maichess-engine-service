package maichess.engine.domain

enum EngineVariant:
  case Basic           // Tier 0: mailbox minimax, no bitboards
  case Base            // Tier 1: bitboard alpha-beta (Search.scala)
  case EnhancedSearch  // Tier 2: + LMR, null-move pruning, PVS
  case EnhancedOrdering// Tier 3: + killer moves, history, SEE, aspiration, check extensions
  case EnhancedEval    // Tier 4: + king safety, pawn structure, full mobility
  case Knowledge       // Tier 5: + opening book, endgame tablebase probing
