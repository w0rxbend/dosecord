package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.core.application.{Application, CommandRegistry}
import dosecord.core.chat.*
import dosecord.core.ports.Clock
import dosecord.infra.db.*
import dosecord.tests.conformance.RecordingSink
import java.util.UUID
import scala.language.implicitConversions

/** Disposable Postgres plus the real application/engine and Discord adapter; no gateway or credentials. */
class DiscordApplicationPgSuite extends PgSuite:
  private val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.fill[Byte](16)(1)), None))

  private def buttons(message: DiscordMessage): List[DiscordButton] = message.components.flatMap:
    case DiscordComponent.ButtonRow(values) => values
    case _                                  => Nil

  private def count(table: String): Long = withConnection: conn =>
    val statement = conn.createStatement()
    try
      val rows = statement.executeQuery(s"SELECT count(*) FROM $table")
      rows.next()
      rows.getLong(1)
    finally statement.close()

  private def await(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 10000000000L
    while !condition && System.nanoTime() < deadline do Thread.sleep(10)
    assert(condition)

  test("real Discord single-select account timezone survives restart and completes account creation"):
    ox.supervised:
      var server = DiscordFakeVendorServer(RecordingSink())
      val clock = new Clock:
        override def now(): java.time.Instant = server.clock
      def launch(): DiscordAdapter =
        val uow = PgUnitOfWork(dataSource, clock)
        val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
        val adapters: Map[String, ChatAdapter] = Map("discord" -> adapter)
        adapter.start(
          ChatMediator(
            uow,
            adapters,
            codec,
            Application.handler(uow, adapters, codec, clock),
            clock,
            commands = CommandRegistry.byName
          ),
          None
        )
        adapter.registerCommands(CommandRegistry.all)
        adapter
      def interaction(sequence: Int): FakeDiscordInteraction =
        FakeDiscordInteraction(
          server.transport,
          Snowflake.mint(server.clock, sequence),
          DiscordUser("select-owner", "synthetic"),
          "select-dm",
          DiscordContext.BotDm
        )
      val before = launch()
      val (selectId, selected) = try
        server.transport.deliver(DiscordEvent.SlashCommand(interaction(301), "start", Nil))
        await(server.transport.interactionReplies.nonEmpty)
        server.transport.interactionReplies.last._2.components
          .collectFirst:
            case DiscordComponent.Select(id, options, _, _) =>
              (id, options.find(_.label.endsWith("Europe/Kyiv")).get.value)
          .get
      finally before.stop()
      server = DiscordFakeVendorServer(RecordingSink())
      val restarted = launch()
      try
        val usersBefore = count("users")
        server.transport.deliver(DiscordEvent.Component(interaction(302), selectId, List(selected), "tz-source"))
        await(server.transport.editedMessages.nonEmpty)
        val confirmation = server.transport.editedMessages.last
        assert(buttons(confirmation).exists(_.label == "Yes"), "single selection must reach timezone confirmation")
        assertEquals(count("users"), usersBefore)
        val yes = buttons(confirmation).find(_.label == "Yes").get.customId
        server.transport.deliver(DiscordEvent.Component(interaction(303), yes, Nil, "confirm-source"))
        await(count("users") == usersBefore + 1)
        withConnection: conn =>
          given java.sql.Connection = conn
          val zone = sql"""SELECT u.timezone FROM users u JOIN platform_identities p ON p.user_id = u.id
                            WHERE p.vendor = 'discord' AND p.vendor_user_id = 'select-owner'""".query[String]().head
          assertEquals(zone, "Europe/Kyiv")
        server.transport.deliver(DiscordEvent.SlashCommand(interaction(304), "menu", Nil))
        await(server.transport.interactionReplies.exists(r => buttons(r._2).exists(_.label == "Account")))
        val accountMenu = server.transport.interactionReplies.map(_._2).flatMap(buttons).find(_.label == "Account").get
        server.transport.deliver(DiscordEvent.Component(interaction(305), accountMenu.customId, Nil, "menu-source"))
        await(server.transport.editedMessages.exists(m => buttons(m).exists(_.label == "Timezone")))
        val timezone = server.transport.editedMessages.flatMap(buttons).find(_.label == "Timezone").get
        server.transport.deliver(DiscordEvent.Component(interaction(306), timezone.customId, Nil, "account-source"))
        def changePicker: Option[(String, String)] =
          (server.transport.editedMessages.toList ++ server.transport.interactionReplies.toList.map(_._2))
            .flatMap(_.components)
            .collectFirst:
              case DiscordComponent.Select(id, options, _, _) =>
                (id, options.find(_.label.endsWith("Europe/London")).get.value)
        await(changePicker.nonEmpty)
        val (changeId, london) = changePicker.get
        val editsBefore = server.transport.editedMessages.size
        server.transport.deliver(DiscordEvent.Component(interaction(307), changeId, List(london), "change-source"))
        await(server.transport.editedMessages.size > editsBefore)
        val changeConfirm = server.transport.editedMessages.last
        assert(buttons(changeConfirm).exists(_.label == "Yes"), "account timezone selection must reach confirmation")
        def storedTimezone: String = withConnection: conn =>
          given java.sql.Connection = conn
          sql"""SELECT u.timezone FROM users u JOIN platform_identities p ON p.user_id = u.id
                WHERE p.vendor = 'discord' AND p.vendor_user_id = 'select-owner'""".query[String]().head
        assertEquals(storedTimezone, "Europe/Kyiv", "selection alone must not change the account")
        server.transport.deliver(
          DiscordEvent.Component(
            interaction(308),
            buttons(changeConfirm).find(_.label == "Yes").get.customId,
            Nil,
            "change-confirm-source"
          )
        )
        await(storedTimezone == "Europe/London")
        assertEquals(count("users"), usersBefore + 1)
      finally restarted.stop()

  test("real application toast outcomes complete slash hooks and manual log dedupes"):
    ox.supervised:
      val server = DiscordFakeVendorServer(RecordingSink())
      val clock = new Clock:
        override def now(): java.time.Instant = server.clock
      val uow = PgUnitOfWork(dataSource, clock)
      val account = Fixtures(dataSource).account("UTC")
      withConnection: conn =>
        given java.sql.Connection = conn
        sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, linked_at)
              VALUES (${UUID.randomUUID()}, $account, 'discord', 'toast-owner', ${server.clock})""".execute()
        sql"""INSERT INTO medications (id, user_id, name, status)
              VALUES (${UUID.randomUUID()}, $account, 'Synthetic', 'active')""".execute()
      val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
      val adapters: Map[String, ChatAdapter] = Map("discord" -> adapter)
      adapter.start(
        ChatMediator(
          uow,
          adapters,
          codec,
          Application.handler(uow, adapters, codec, clock),
          clock,
          commands = CommandRegistry.byName
        ),
        None
      )
      adapter.registerCommands(CommandRegistry.all)
      try
        def command(sequence: Int, name: String, options: List[DiscordOption]): Unit =
          server.transport.deliver(
            DiscordEvent.SlashCommand(
              FakeDiscordInteraction(
                server.transport,
                Snowflake.mint(server.clock, sequence),
                DiscordUser("toast-owner", "synthetic"),
                "toast-dm",
                DiscordContext.BotDm
              ),
              name,
              options
            )
          )
        command(101, "taken", List(DiscordOption("medication", "Synthetic")))
        await(server.transport.interactionReplies.exists(_._1 == "followUp"))
        assertEquals(count("dose_actions"), 0L)
        val before = server.transport.interactionReplies.size
        command(102, "log", List(DiscordOption("medication", "Synthetic")))
        await(server.transport.interactionReplies.size > before)
        await(count("dose_actions") == 1L)
        command(102, "log", List(DiscordOption("medication", "Synthetic")))
        Thread.sleep(100)
        assertEquals(count("dose_actions"), 1L)
        assertEquals(server.transport.sent.size, 0)
        assert(server.transport.interactionReplies.count(_._1 == "followUp") >= 2)
      finally adapter.stop()

  test("real persisted medication modal survives fresh application and rejects owner stale and tampered submits"):
    ox.supervised:
      val first = DiscordFakeVendorServer(RecordingSink())
      val clock = new Clock:
        override def now(): java.time.Instant = first.clock
      val account = Fixtures(dataSource).account("UTC")
      withConnection: conn =>
        given java.sql.Connection = conn
        sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, linked_at)
              VALUES (${UUID.randomUUID()}, $account, 'discord', 'wizard-owner', ${first.clock})""".execute()
      def launch(server: DiscordFakeVendorServer): DiscordAdapter =
        val uow = PgUnitOfWork(dataSource, clock)
        val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
        val adapters: Map[String, ChatAdapter] = Map("discord" -> adapter)
        adapter.start(
          ChatMediator(
            uow,
            adapters,
            codec,
            Application.handler(uow, adapters, codec, clock),
            clock,
            commands = CommandRegistry.byName
          ),
          None
        )
        adapter.registerCommands(CommandRegistry.all)
        adapter
      def interaction(
          server: DiscordFakeVendorServer,
          sequence: Int,
          owner: String = "wizard-owner"
      ): FakeDiscordInteraction =
        FakeDiscordInteraction(
          server.transport,
          Snowflake.mint(server.clock, sequence),
          DiscordUser(owner, "synthetic"),
          "wizard-dm",
          DiscordContext.BotDm
        )
      def wire(server: DiscordFakeVendorServer, label: String): String =
        (server.transport.editedMessages.toList.reverse ++ server.transport.interactionReplies.toList.reverse.map(
          _._2
        )).iterator.flatMap(buttons).find(_.label == label).get.customId
      val before = launch(first)
      val customId = try
        first.transport.deliver(DiscordEvent.SlashCommand(interaction(first, 201), "menu", Nil))
        await(first.transport.interactionReplies.nonEmpty)
        val meds = wire(first, "Medications")
        first.transport.deliver(DiscordEvent.Component(interaction(first, 202), meds, Nil, "menu-source"))
        await(first.transport.editedMessages.exists(m => buttons(m).exists(_.label == "Add medication")))
        first.transport.deliver(
          DiscordEvent.Component(interaction(first, 203), wire(first, "Add medication"), Nil, "meds-source")
        )
        await(first.transport.modalPayloads.nonEmpty)
        first.transport.modalPayloads.last.split(";", 2).head.stripPrefix("custom_id=")
      finally before.stop()
      val after = DiscordFakeVendorServer(RecordingSink())
      val restarted = launch(after)
      try
        val fields = Map("name" -> "Restart synthetic", "dose" -> "", "times" -> "", "instructions" -> "")
        val medicationsBefore = count("medications")
        after.transport.deliver(DiscordEvent.ModalSubmit(interaction(after, 204, "intruder"), customId, fields))
        await(after.transport.interactionReplies.nonEmpty)
        assertEquals(count("medications"), medicationsBefore)
        after.transport.deliver(DiscordEvent.ModalSubmit(interaction(after, 205), customId, fields))
        await(after.transport.interactionReplies.exists(r => buttons(r._2).exists(_.label == "Create")))
        assert(after.transport.acks.exists(_.kind == "deferReply"))
        val create = wire(after, "Create")
        after.transport.deliver(DiscordEvent.Component(interaction(after, 206), create, Nil, "review-source"))
        await(count("medications") == medicationsBefore + 1)
        after.transport.deliver(DiscordEvent.Component(interaction(after, 207), create, Nil, "review-source"))
        after.transport.deliver(DiscordEvent.ModalSubmit(interaction(after, 208), customId, fields))
        val tampered = customId.dropRight(1) + (if customId.last == 'A' then "B" else "A")
        after.transport.deliver(DiscordEvent.ModalSubmit(interaction(after, 209), tampered, fields))
        Thread.sleep(200)
        assertEquals(count("medications"), medicationsBefore + 1)
        assert(count("auth_audit_log") > 0)
      finally restarted.stop()
