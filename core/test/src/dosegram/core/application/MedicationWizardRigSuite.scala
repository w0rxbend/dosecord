package dosegram.core.application

import dosegram.core.application.MedicationWizardRig.Profile
import dosegram.core.domain.copy.MenuCopy

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MedicationWizardRigSuite extends munit.FunSuite:
  test("a tap transcript action waits for its final send before returning a snapshot"):
    ox.supervised:
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val rig = MedicationWizardRig(
        Profile.Telegram,
        message =>
          if message.chunks.exists(_.contains("Let's add a medication")) then
            entered.countDown()
            if !release.await(10, TimeUnit.SECONDS) then throw TimeoutException("final send was not released")
      )
      rig.bootstrapTelegram()
      rig.command("menu")
      rig.tapLabel(MenuCopy.mainPrompt, "Medications")
      val worker = Executors.newSingleThreadExecutor()
      try
        val action = worker.submit(new Callable[List[String]]:
          override def call(): List[String] = rig.tapT("Medications", "Add medication"))
        val sendEntered = entered.await(5, TimeUnit.SECONDS)
        val prematureSnapshot =
          try Some(action.get(1, TimeUnit.SECONDS))
          catch case _: TimeoutException => None
        release.countDown()
        val transcript = action.get(5, TimeUnit.SECONDS)
        assert(sendEntered, "the send must reach the controlled delivery boundary")
        assertEquals(prematureSnapshot, None, "the snapshot must wait for the event's delivery plan")
        assert(transcript.exists(_.contains("Let's add a medication")), "the snapshot must include the final send")
        assertEquals(rig.uow.inboundEvents.all.size, 6, "completion must not add fixture-only inbound rows")
      finally
        release.countDown()
        worker.shutdownNow()
        val terminated = worker.awaitTermination(5, TimeUnit.SECONDS)
        if !terminated then throw TimeoutException("transcript worker did not terminate")
