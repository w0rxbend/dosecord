package dosegram.api

import dosegram.api.auth.AttemptLimiter

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The limits of the share builder's preview (supervisor decision, 2026-11-27): a minute's count per account, and the
  * drawings underway across the process, each answered 429 before anything is drawn.
  */
class PreviewLimitsSuite extends munit.FunSuite:

  private val now = Instant.parse("2026-11-27T10:00:00Z")

  test("an account's previews are counted by the minute; another account has its own"):
    val limits = PreviewLimits(AttemptLimiter(2, Duration.ofMinutes(1)), drawingAtOnce = 2)
    val (one, two) = (UUID.randomUUID(), UUID.randomUUID())
    assertEquals(limits.admit(one, now), Right(()))
    assertEquals(limits.admit(one, now.plusSeconds(10)), Right(()))
    val refused = limits.admit(one, now.plusSeconds(20)).left.toOption.getOrElse(fail("the third preview was admitted"))
    assertEquals((refused.status.code, refused.retryAfterSeconds), (429, Some(40L)))
    assertEquals(limits.admit(two, now.plusSeconds(20)), Right(()))
    assertEquals(limits.admit(one, now.plusSeconds(60)), Right(()))

  test("a drawing while every slot is busy is refused without drawing; a slot frees when its drawing ends"):
    val limits = PreviewLimits(AttemptLimiter(10, Duration.ofMinutes(1)), drawingAtOnce = 1)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val busy = Thread.ofVirtual().start: () =>
      limits.drawing { started.countDown(); release.await(5, TimeUnit.SECONDS) }
      ()
    assert(started.await(5, TimeUnit.SECONDS))
    var drawn = false
    val refused = limits.drawing { drawn = true }.left.toOption.getOrElse(fail("a second drawing ran"))
    assertEquals((refused.status.code, refused.retryAfterSeconds, drawn), (429, Some(1L), false))
    release.countDown()
    busy.join(5000)
    assertEquals(limits.drawing("drawn"), Right("drawn"))

  test("a drawing that throws gives its slot back"):
    val limits = PreviewLimits(AttemptLimiter(10, Duration.ofMinutes(1)), drawingAtOnce = 1)
    intercept[IllegalStateException](limits.drawing(throw IllegalStateException("rasteriser")))
    assertEquals(limits.drawing(1), Right(1))
