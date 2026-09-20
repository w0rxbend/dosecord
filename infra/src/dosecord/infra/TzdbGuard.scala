package dosecord.infra

import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Startup assertion of the runtime IANA tzdb version — the M0.7 no-go remedy that became required of M0.5
  * (docs/spikes/dst-primitives.md, ROADMAP risk 2).
  *
  * The runtime tzdb source is, in order: the directory named by `jdk.timezone.zip.dir` when the image points the JDK at
  * the Alpine tzdata package (`tzdata.zi` header), otherwise the JDK-bundled `lib/tzdb.dat`.
  *
  * Policy: below [[FloorVersion]] the seven-zone DST goldens are not known-good and startup fails; below
  * [[TargetVersion]] startup continues with a warning (the image's tzdata override step is expected to carry the
  * runtime past the target; the M0.11 metric keeps the version observable).
  */
object TzdbGuard:

  val FloorVersion = "2025b"
  val TargetVersion = "2026c"

  enum Verdict:
    case Ok(version: String)
    case Outdated(version: String)
    case Unsupported(reason: String)

  def check(): Verdict = check(resolveRuntimeVersion())

  def check(version: Option[String]): Verdict =
    version match
      case None                                  => Verdict.Unsupported("runtime tzdb version is unreadable")
      case Some(v) if !atLeast(v, FloorVersion)  => Verdict.Unsupported(s"tzdb $v is below floor $FloorVersion")
      case Some(v) if !atLeast(v, TargetVersion) => Verdict.Outdated(v)
      case Some(v)                               => Verdict.Ok(v)

  /** Fails startup below the floor, warns below the target. Returns the runtime version for the M0.11 metric. */
  def assertSupported(log: String => Unit): String =
    check() match
      case Verdict.Ok(version) =>
        log(s"tzdb $version (>= $TargetVersion)")
        version
      case Verdict.Outdated(version) =>
        log(
          s"WARNING: tzdb $version is below target $TargetVersion; rebuild the image so the tzdata override step picks up a newer package"
        )
        version
      case Verdict.Unsupported(reason) =>
        throw IllegalStateException(s"unsupported runtime tzdb: $reason (see docs/spikes/dst-primitives.md)")

  def resolveRuntimeVersion(): Option[String] =
    sys.props.get("jdk.timezone.zip.dir") match
      case Some(dir) => zoneinfoVersion(Path.of(dir))
      case None      => jdkBundledVersion(Path.of(System.getProperty("java.home"), "lib", "tzdb.dat"))

  /** First `# version <id>` line of the `tzdata.zi` shipped next to compiled zoneinfo files. */
  private def zoneinfoVersion(dir: Path): Option[String] =
    val zi = dir.resolve("tzdata.zi")
    if Files.isRegularFile(zi) then
      try
        Files
          .readAllLines(zi)
          .toArray(Array[String]())
          .collectFirst { case line if line.startsWith("# version ") => line.stripPrefix("# version ").trim }
      catch case _: Exception => None
    else None

  /** JDK 9+ `tzdb.dat` header: file-version byte, "TZDB" magic, short count, version ids as UTF strings. */
  private def jdkBundledVersion(file: Path): Option[String] =
    if Files.isRegularFile(file) then
      try
        val in = DataInputStream(Files.newInputStream(file))
        try
          in.readByte()
          require(in.readUTF() == "TZDB", "bad magic")
          require(in.readShort() >= 1, "no version")
          Some(in.readUTF())
        finally in.close()
      catch case _: Exception => None
    else None

  def atLeast(actual: String, required: String): Boolean =
    (parse(actual), parse(required)) match
      case (Some((ay, al)), Some((ry, rl))) => ay > ry || (ay == ry && al >= rl)
      case _                                => false

  private def parse(version: String): Option[(Int, Char)] =
    if version.length == 5 && version.take(4).forall(_.isDigit) && version(4).isLetter then
      Some((version.take(4).toInt, version(4)))
    else None
