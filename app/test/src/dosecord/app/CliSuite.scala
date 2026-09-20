package dosecord.app

import dosecord.infra.Role

class CliSuite extends munit.FunSuite:

  test("migrate"):
    assertEquals(Cli.parse(List("migrate")), Right(Cli.Command.Migrate))

  test("run without flags keeps the Settings role"):
    assertEquals(Cli.parse(List("run")), Right(Cli.Command.Run(None)))

  test("run --role gateway|worker|all"):
    assertEquals(Cli.parse(List("run", "--role", "gateway")), Right(Cli.Command.Run(Some(Role.Gateway))))
    assertEquals(Cli.parse(List("run", "--role=worker")), Right(Cli.Command.Run(Some(Role.Worker))))
    assertEquals(Cli.parse(List("run", "--role", "all")), Right(Cli.Command.Run(Some(Role.All))))

  test("bad role, unknown args and duplicates are rejected"):
    assert(Cli.parse(List("run", "--role", "boss")).isLeft)
    assert(Cli.parse(List("run", "--role")).isLeft)
    assert(Cli.parse(List("run", "--adapter", "console")).isLeft)
    assert(Cli.parse(List("run", "--role", "gateway", "--role", "worker")).isLeft)
    assert(Cli.parse(List("status")).isLeft)
    assert(Cli.parse(Nil).isLeft)
