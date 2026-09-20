package dosecord.core.chat

/** Suite B1 (ROADMAP M0.9): one renderer golden per scenario per profile — the cross-vendor UX spec (ADR-005). Any
  * renderer change fails here until the goldens are regenerated and reviewed.
  */
class RendererGoldenSuite extends munit.FunSuite:

  GoldenFiles.matrix.foreach { (scenario, profile) =>
    test(s"$profile/$scenario"):
      assertEquals(
        GoldenFiles.expected(scenario, profile),
        GoldenFiles.load(scenario, profile),
        s"golden drifted; regenerate: ./mill core.test.runMain dosecord.core.chat.rendererGoldenGenerate"
      )
  }

  test("discreet goldens contain no medication name"):
    CapabilityProfiles.all.foreach { (profile, _) =>
      val golden = GoldenFiles.load("discreet-reminder", profile)
      GoldenScenarios.MedicationNames.foreach { name =>
        assert(!golden.contains(name), s"discreet golden for $profile leaks '$name'")
      }
      assert(golden.contains("suppressPreview=true"), s"discreet golden for $profile does not suppress previews")
    }
end RendererGoldenSuite
