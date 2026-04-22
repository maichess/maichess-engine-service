package maichess.engine.domain

final case class PrincipalVariation(rank: Int, evaluationCp: Int, moves: List[String])
final case class AnalysisUpdate(depth: Int, lines: List[PrincipalVariation])
