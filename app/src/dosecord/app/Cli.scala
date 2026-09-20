package dosecord.app

import dosecord.infra.Role

/** `dosecord run [--role gateway|worker|all]` | `dosecord migrate` (ROADMAP M0.5; roles wired in M4.3). */
object Cli:

  enum Command:
    case Run(role: Option[Role])
    case Migrate

  val usage = "usage: dosecord run [--role gateway|worker|all] | dosecord migrate"

  def parse(args: List[String]): Either[String, Command] =
    args match
      case "migrate" :: Nil => Right(Command.Migrate)
      case "run" :: rest    => parseRole(rest, None)
      case _                => Left(s"unknown arguments: ${args.mkString(" ")}")

  private def parseRole(args: List[String], role: Option[Role]): Either[String, Command] =
    args match
      case Nil                                      => Right(Command.Run(role))
      case "--role" :: value :: rest                => withRole(value, role, rest)
      case arg :: rest if arg.startsWith("--role=") => withRole(arg.stripPrefix("--role="), role, rest)
      case arg :: _                                 => Left(s"unknown argument for 'run': $arg")

  private def withRole(value: String, existing: Option[Role], rest: List[String]): Either[String, Command] =
    if existing.isDefined then Left("duplicate --role")
    else
      Role.parse(value) match
        case Some(r) => parseRole(rest, Some(r))
        case None    => Left(s"--role must be gateway, worker or all, got '$value'")
