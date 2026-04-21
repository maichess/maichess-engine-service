ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.3"

val zioVersion     = "2.1.25"
val zioGrpcVersion = "0.6.3"
val grpcVersion    = "1.80.0"
val scalapbVersion = "0.11.17"

lazy val root = (project in file("."))
  .settings(
    name := "maichess-engine-service",

    // ── Resolver & credentials for platform-protos ───────────────────────────
    resolvers += "GitHub Packages" at
      "https://maven.pkg.github.com/maichess/maichess-api-contracts",

    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      sys.env.getOrElse("GITHUB_ACTOR", "_"),
      sys.env.getOrElse("GITHUB_TOKEN", ""),
    ),

    // ── Dependencies ──────────────────────────────────────────────────────────
    libraryDependencies ++= Seq(
      "io.github.maichess"            %% "platform-protos"      % "0.2.5",
      "dev.zio"                       %% "zio"                  % zioVersion,
      "dev.zio"                       %% "zio-streams"          % zioVersion,
      "io.grpc"                        % "grpc-netty-shaded"    % grpcVersion,
      "com.thesamet.scalapb"          %% "scalapb-runtime-grpc" % scalapbVersion,
      "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core"        % zioGrpcVersion,
      "dev.zio"                       %% "zio-test"             % zioVersion % Test,
      "dev.zio"                       %% "zio-test-sbt"         % zioVersion % Test,
    ),

    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    // ── WartRemover (Compile only) ────────────────────────────────────────────
    // Wart.Any is excluded: Scala's s"..." interpolation desugars to Any* varargs,
    // making Wart.Any incompatible with idiomatic string interpolation and ZIO's
    // intentional use of Any for the empty-environment type.
    Compile / compile / wartremoverErrors ++= Warts.unsafe.filterNot(_ == Wart.Any),

    // ── Coverage (100%, exclude server entry point and chess engine) ─────────
    // The chess/ package is a performance-critical port with timing-dependent
    // branches (search time limit, quiescence depth guard) that cannot be
    // exercised by deterministic unit tests. All service/domain/grpc code is
    // covered at 100%.
    coverageEnabled          := true,
    coverageMinimumStmtTotal := 100,
    coverageFailOnMinimum    := true,
    coverageExcludedFiles    := ".*Main.*",
    coverageExcludedPackages := "maichess\\.engine\\.chess\\..*",

    // ── SemanticDB for Scalafix ───────────────────────────────────────────────
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
  )
