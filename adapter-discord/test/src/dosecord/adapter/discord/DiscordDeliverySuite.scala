package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.core.chat.*
import dosecord.core.chat.MediatorFakes.*
import dosecord.tests.conformance.RecordingSink

class DiscordDeliverySuite extends munit.FunSuite:
  private val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.fill[Byte](16)(1)), None))

  List("Recorded.", "No open dose.").foreach: toast =>
    test(s"toast-only slash completes its deferred original: $toast"):
      ox.supervised:
        val server = DiscordFakeVendorServer(RecordingSink())
        val uow = InMemoryUnitOfWork()
        val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
        val spec = CommandSpec("test", "synthetic")
        adapter.start(
          ChatMediator(
            uow,
            Map("discord" -> adapter),
            codec,
            RecordingHandler(_ => Reply(toast = Some(toast))),
            FixedClock(server.clock),
            commands = Map(spec.name -> spec)
          ),
          None
        )
        try
          server.transport.deliver(
            DiscordEvent.SlashCommand(
              FakeDiscordInteraction(
                server.transport,
                Snowflake.mint(server.clock, 20),
                DiscordUser("synthetic", "synthetic"),
                "dm-1",
                DiscordContext.BotDm
              ),
              spec.name,
              Nil
            )
          )
          val deadline = System.nanoTime() + 3000000000L
          while server.transport.interactionReplies.isEmpty && server.transport.sent.isEmpty && System
              .nanoTime() < deadline
          do Thread.sleep(10)
          assertEquals(server.transport.sent.size, 0)
          assert(server.transport.interactionReplies.exists(_._2.text == toast))
        finally adapter.stop()

  List(false, true).foreach: replacement =>
    test(s"oversized reply bounds every request and preserves all text: replacement=$replacement"):
      ox.supervised:
        val server = DiscordFakeVendorServer(RecordingSink())
        val uow = InMemoryUnitOfWork()
        val text = "a" * 5000
        val source = MessageHandle("discord", "dm-1", "source")
        val message = OutboundMessage(
          body = List(Node.Paragraph(List(Inline.Text(text)))),
          replaces = Option.when(replacement)(source),
          dedupeKey = "large",
          correlationId = "test"
        )
        val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
        adapter.start(
          ChatMediator(
            uow,
            Map("discord" -> adapter),
            codec,
            RecordingHandler(_ => Reply(followUps = List(message))),
            FixedClock(server.clock),
            commands = Map("test" -> CommandSpec("test", "synthetic"))
          ),
          None
        )
        try
          server.transport.deliver(
            DiscordEvent.SlashCommand(
              FakeDiscordInteraction(
                server.transport,
                Snowflake.mint(server.clock, 21),
                DiscordUser("synthetic", "synthetic"),
                "dm-1",
                DiscordContext.BotDm
              ),
              "test",
              Nil
            )
          )
          val deadline = System.nanoTime() + 3000000000L
          def parts = server.transport.edits.map(_.text).toList ++ server.transport.interactionReplies
            .map(_._2.text)
            .toList ++ server.transport.sent.map(_.text).toList
          while parts.mkString.length < text.length && System.nanoTime() < deadline do Thread.sleep(10)
          assertEquals(parts.mkString, text)
          assert(parts.forall(_.length <= 2000))
        finally adapter.stop()

  for
    expiry <- List("before-render", "between-render-delivery", "failed-followup")
    replacement <- List(false, true)
  do
    test(s"private canary never reaches persistent delivery or safety retry: $expiry replacement=$replacement"):
      ox.supervised:
        val server = DiscordFakeVendorServer(RecordingSink())
        val uow = InMemoryUnitOfWork()
        val checks = new java.util.concurrent.atomic.AtomicInteger(0)
        val handled = new java.util.concurrent.atomic.AtomicBoolean(false)
        val canary = "PRIVATE-CANARY"
        val source = MessageHandle("discord", "dm-1", "source")
        val message = OutboundMessage(
          body = List(Node.Paragraph(List(Inline.Text(canary)))),
          visibility = Visibility.Ephemeral,
          replaces = Option.when(replacement)(source),
          dedupeKey = "private",
          correlationId = "test"
        )
        val handler = RecordingHandler(_ =>
          if expiry == "failed-followup" then server.transport.failNextRaw(10015, None, "expired", "followUp")
          handled.set(true)
          Reply(followUps = List(message))
        )
        val adapter = DiscordAdapter(
          server.transport,
          (_, _) => Nil,
          _ => (),
          () =>
            if handled.get() && (expiry == "before-render" || (expiry == "between-render-delivery" && checks
                .incrementAndGet() >= 2))
            then server.clock.plusSeconds(960)
            else server.clock
        )
        adapter.start(
          ChatMediator(
            uow,
            Map("discord" -> adapter),
            codec,
            handler,
            FixedClock(server.clock),
            commands = Map("test" -> CommandSpec("test", "synthetic"))
          ),
          None
        )
        try
          server.transport.deliver(
            DiscordEvent.SlashCommand(
              FakeDiscordInteraction(
                server.transport,
                Snowflake.mint(server.clock, 22),
                DiscordUser("synthetic", "synthetic"),
                "dm-1",
                DiscordContext.BotDm
              ),
              "test",
              Nil
            )
          )
          val deadline = System.nanoTime() + 3000000000L
          while uow.inboundEvents.all.isEmpty && System.nanoTime() < deadline do Thread.sleep(10)
          Thread.sleep(100)
          assert(!server.transport.sent.exists(_.text.contains(canary)))
          assert(!server.transport.edits.exists(_.text.contains(canary)))
          assert(!server.transport.interactionReplies.exists(r => r._2.text.contains(canary) && !r._3))
          if expiry == "failed-followup" then
            assert(server.transport.interactionReplies.exists(_._2.text == "Response sent separately."))
            assert(!server.transport.interactionReplies.exists(_._2.text.contains(canary)))
          assertEquals(uow.outbox.allEnqueued.filter(_.payload.contains(canary)).size, 0)
          assert(uow.inboundEvents.all.forall(_.processedAt.nonEmpty))
        finally adapter.stop()

  test("source hook edits bound chunks and put controls only on the final followup"):
    ox.supervised:
      val sink = RecordingSink()
      val server = DiscordFakeVendorServer(sink)
      val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
      adapter.start(sink, None)
      try
        val token = codec
          .encode(CallbackMode.Direct, ActionRegistry.byName("dose.taken").get, java.util.UUID.randomUUID(), 0)
          .wire
        server.transport.deliver(
          DiscordEvent.Component(
            FakeDiscordInteraction(
              server.transport,
              Snowflake.mint(server.clock, 23),
              DiscordUser("synthetic", "synthetic"),
              "dm-1",
              DiscordContext.BotDm
            ),
            token,
            Nil,
            "source"
          )
        )
        val deadline = System.nanoTime() + 3000000000L
        while sink.all.isEmpty && System.nanoTime() < deadline do Thread.sleep(10)
        val hook = sink.all.head.interaction.get
        hook.deferUpdate()
        hook.editSource(
          RenderedMessage(
            chunks = List("a" * 2000, "b" * 2000, "c" * 1000),
            controls = List(RenderedControls.Buttons(List(List(RenderedChoice(1, "Done", token))))),
            silent = true,
            suppressPreview = true
          )
        )
        val replies = server.transport.interactionReplies.toList
        assertEquals(replies.map(_._1), List("editOriginal", "followUp", "followUp"))
        assertEquals(replies.map(_._2.text).mkString, "a" * 2000 + "b" * 2000 + "c" * 1000)
        assert(replies.init.forall(_._2.components.isEmpty))
        assert(replies.last._2.components.nonEmpty)
        assert(replies.forall(r => r._2.silent && r._2.suppressPreview))
      finally adapter.stop()

  test("a missed modal deadline retains the persisted sequential form"):
    ox.supervised:
      val server = DiscordFakeVendorServer(RecordingSink())
      val uow = InMemoryUnitOfWork()
      val sessionId = java.util.UUID.randomUUID()
      val degraded = new java.util.concurrent.atomic.AtomicInteger(0)
      val wire = codec.encode(CallbackMode.Direct, ActionRegistry.byName("wizard.confirm").get, sessionId, 0).wire
      val form = Form("synthetic", "Synthetic form", List(Field("value", "Value", FieldType.Text)), wire)
      val handler = new ChatHandler:
        override def handle(event: InboundEvent, principal: Principal, tx: dosecord.core.ports.Tx): Reply =
          FormRunner(codec).start(tx, sessionId, form.id, wire)
          server.advanceClock(java.time.Duration.ofSeconds(4))
          Reply(followUps =
            List(
              OutboundMessage(
                body = List(Node.Paragraph(List(Inline.Text("Form")))),
                blocks = List(Block.FormBlock(form)),
                dedupeKey = "form",
                correlationId = "test"
              )
            )
          )
      val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
      val mediator = ChatMediator(
        uow,
        Map("discord" -> adapter),
        codec,
        handler,
        FixedClock(server.clock),
        commands = Map("synthetic" -> CommandSpec("synthetic", "test", opensForm = true)),
        recordFormDegraded = () => { degraded.incrementAndGet(); () }
      )
      adapter.start(mediator, None)
      adapter.registerCommands(List(CommandSpec("synthetic", "test", opensForm = true)))
      try
        server.transport.deliver(
          DiscordEvent.SlashCommand(
            FakeDiscordInteraction(
              server.transport,
              Snowflake.mint(server.clock, 2),
              DiscordUser("synthetic", "synthetic"),
              "dm-1",
              DiscordContext.BotDm
            ),
            "synthetic",
            Nil
          )
        )
        val deadline = System.nanoTime() + 3000000000L
        while server.transport.interactionReplies.isEmpty && server.transport.sent.isEmpty && System
            .nanoTime() < deadline
        do Thread.sleep(10)
        assertEquals(server.transport.modalPayloads.size, 0)
        assertEquals(degraded.get(), 1)
        assert(uow.formRuns.loadForUpdate(sessionId).nonEmpty)
        assert(
          (server.transport.interactionReplies.map(_._2.text) ++ server.transport.sent.map(_.text))
            .exists(_.contains("Value"))
        )
      finally adapter.stop()

  test("a known replacement edits the source card without sending a new message"):
    ox.supervised:
      val server = DiscordFakeVendorServer(RecordingSink())
      val uow = InMemoryUnitOfWork()
      val source = MessageHandle("discord", "dm-1", "source-card")
      val wire =
        codec.encode(CallbackMode.Direct, ActionRegistry.byName("dose.taken").get, java.util.UUID.randomUUID(), 0).wire
      val reply = OutboundMessage(
        body = List(Node.Paragraph(List(Inline.Text("recorded")))),
        replaces = Some(source),
        dedupeKey = "source-edit",
        correlationId = "test"
      )
      val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
      val mediator = ChatMediator(
        uow,
        Map("discord" -> adapter),
        codec,
        RecordingHandler(_ => Reply(replace = Some(reply))),
        FixedClock(server.clock)
      )
      adapter.start(mediator, None)
      try
        server.transport.deliver(
          DiscordEvent.Component(
            FakeDiscordInteraction(
              server.transport,
              Snowflake.mint(server.clock, 3),
              DiscordUser("synthetic", "synthetic"),
              "dm-1",
              DiscordContext.BotDm
            ),
            wire,
            Nil,
            source.messageId
          )
        )
        val deadline = System.nanoTime() + 3000000000L
        while server.transport.edits.isEmpty && server.transport.interactionReplies.isEmpty && System
            .nanoTime() < deadline
        do Thread.sleep(10)
        assertEquals(server.transport.edits.map(_.messageId).toList, List(Some(source.messageId)))
        assertEquals(server.transport.sent.size, 0)
        assertEquals(server.transport.interactionReplies.size, 0)
      finally adapter.stop()

  test("a modal opened before restart submits through a fresh independently acknowledged interaction"):
    ox.supervised:
      val uow = InMemoryUnitOfWork()
      val session = java.util.UUID.randomUUID()
      val wire = codec.encode(CallbackMode.Direct, ActionRegistry.byName("wizard.text_step").get, session, 0).wire
      val form = Form("restart", "Synthetic", List(Field("value", "Value", FieldType.Text)), wire)
      val submitted = new java.util.concurrent.atomic.AtomicReference[Map[String, String]](Map.empty)
      def launch(server: DiscordFakeVendorServer): DiscordAdapter =
        val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
        val handler = new ChatHandler:
          override def handle(event: InboundEvent, principal: Principal, tx: dosecord.core.ports.Tx): Reply =
            event.body match
              case Inbound.FormSubmitted(_, _, fields) =>
                assert(tx.formRuns.loadForUpdate(session).nonEmpty)
                tx.formRuns.delete(session)
                submitted.set(fields)
                Reply(followUps =
                  List(
                    OutboundMessage(
                      body = List(Node.Paragraph(List(Inline.Text("submitted")))),
                      dedupeKey = "submitted",
                      correlationId = "test"
                    )
                  )
                )
              case _ =>
                FormRunner(codec).start(tx, session, form.id, wire)
                Reply(followUps =
                  List(
                    OutboundMessage(
                      body = List(Node.Paragraph(List(Inline.Text("Form")))),
                      blocks = List(Block.FormBlock(form)),
                      dedupeKey = "opening",
                      correlationId = "test"
                    )
                  )
                )
        val spec = CommandSpec("synthetic", "test", opensForm = true)
        adapter.start(
          ChatMediator(
            uow,
            Map("discord" -> adapter),
            codec,
            handler,
            FixedClock(server.clock),
            commands = Map(spec.name -> spec)
          ),
          None
        )
        adapter.registerCommands(List(spec))
        adapter
      val before = DiscordFakeVendorServer(RecordingSink())
      val first = launch(before)
      try
        before.transport.deliver(
          DiscordEvent.SlashCommand(
            FakeDiscordInteraction(
              before.transport,
              Snowflake.mint(before.clock, 10),
              DiscordUser("synthetic", "synthetic"),
              "dm-1",
              DiscordContext.BotDm
            ),
            "synthetic",
            Nil
          )
        )
        val deadline = System.nanoTime() + 3000000000L
        while before.transport.modalPayloads.isEmpty && System.nanoTime() < deadline do Thread.sleep(10)
        assertEquals(before.transport.acks.head.kind, "replyModal")
      finally first.stop()
      val after = DiscordFakeVendorServer(RecordingSink())
      val second = launch(after)
      try
        after.transport.deliver(
          DiscordEvent.ModalSubmit(
            FakeDiscordInteraction(
              after.transport,
              Snowflake.mint(after.clock, 11),
              DiscordUser("synthetic", "synthetic"),
              "dm-1",
              DiscordContext.BotDm
            ),
            s"restart:$wire",
            Map("value" -> "synthetic answer")
          )
        )
        val deadline = System.nanoTime() + 3000000000L
        while after.transport.interactionReplies.isEmpty && System.nanoTime() < deadline do Thread.sleep(10)
        assertEquals(submitted.get(), Map("value" -> "synthetic answer"))
        assertEquals(after.transport.acks.head.kind, "deferReply")
        assert(after.transport.interactionReplies.exists(_._2.text == "submitted"))
      finally second.stop()

  test("a non-expiry modal failure leaves the safety row without degrading or failing the scope"):
    ox.supervised:
      val server = DiscordFakeVendorServer(RecordingSink())
      val uow = InMemoryUnitOfWork()
      val wire = codec
        .encode(CallbackMode.Direct, ActionRegistry.byName("wizard.confirm").get, java.util.UUID.randomUUID(), 0)
        .wire
      val form = Form("failure", "Synthetic", List(Field("value", "Value", FieldType.Text)), wire)
      val degraded = new java.util.concurrent.atomic.AtomicInteger(0)
      val handler = RecordingHandler(_ =>
        server.transport.failNextRaw(50001, None, "missing access", "ack")
        Reply(followUps =
          List(
            OutboundMessage(
              body = List(Node.Paragraph(List(Inline.Text("Form")))),
              blocks = List(Block.FormBlock(form)),
              dedupeKey = "failure",
              correlationId = "test"
            )
          )
        )
      )
      val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
      val spec = CommandSpec("synthetic", "test", opensForm = true)
      adapter.start(
        ChatMediator(
          uow,
          Map("discord" -> adapter),
          codec,
          handler,
          FixedClock(server.clock),
          commands = Map(spec.name -> spec),
          recordFormDegraded = () => { degraded.incrementAndGet(); () }
        ),
        None
      )
      adapter.registerCommands(List(spec))
      try
        server.transport.deliver(
          DiscordEvent.SlashCommand(
            FakeDiscordInteraction(
              server.transport,
              Snowflake.mint(server.clock, 12),
              DiscordUser("synthetic", "synthetic"),
              "dm-1",
              DiscordContext.BotDm
            ),
            spec.name,
            Nil
          )
        )
        val deadline = System.nanoTime() + 3000000000L
        while uow.inboundEvents.all.isEmpty && System.nanoTime() < deadline do Thread.sleep(10)
        Thread.sleep(100)
        assertEquals(degraded.get(), 0)
        assertEquals(server.transport.sent.size, 0)
        assertEquals(server.transport.interactionReplies.size, 0)
      finally adapter.stop()

  test("mediator completes the deferred slash original rather than a channel send"):
    ox.supervised:
      val server = DiscordFakeVendorServer(RecordingSink())
      val uow = InMemoryUnitOfWork()
      val reply = OutboundMessage(
        body = List(Node.Paragraph(List(Inline.Text("completed")))),
        dedupeKey = "reply",
        correlationId = "test"
      )
      val handler = RecordingHandler(_ => Reply(followUps = List(reply)))
      val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock)
      val mediator = ChatMediator(
        uow,
        Map("discord" -> adapter),
        codec,
        handler,
        FixedClock(server.clock),
        commands = Map("test" -> CommandSpec("test", "synthetic"))
      )
      adapter.start(mediator, None)
      try
        val interaction = FakeDiscordInteraction(
          server.transport,
          Snowflake.mint(server.clock, 1),
          DiscordUser("synthetic", "synthetic"),
          "dm-1",
          DiscordContext.BotDm
        )
        server.transport.deliver(DiscordEvent.SlashCommand(interaction, "test", Nil))
        val deadline = System.nanoTime() + 3000000000L
        while server.transport.sent.isEmpty && server.transport.interactionReplies.isEmpty && System
            .nanoTime() < deadline
        do Thread.sleep(10)
        assertEquals(server.transport.sent.size, 0)
        assert(server.transport.interactionReplies.exists(_._2.text == "completed"))
      finally adapter.stop()
