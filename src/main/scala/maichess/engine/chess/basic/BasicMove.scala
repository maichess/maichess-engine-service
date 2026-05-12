package maichess.engine.chess.basic

object BasicFlag:
  val Quiet      = 0
  val DoublePush = 1
  val Castle     = 2
  val EP         = 3
  val Capture    = 4

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class BasicMove(from: Int, to: Int, promo: Int = 0, flag: Int = BasicFlag.Quiet)
