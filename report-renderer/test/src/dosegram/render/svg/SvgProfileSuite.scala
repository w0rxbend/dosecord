package dosegram.render.svg

class SvgProfileSuite extends munit.FunSuite:
  private def document(body: String): String =
    s"""<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 10 10">$body</svg>"""

  test("a document of profile elements and attributes has no violation"):
    val svg = document("""<g transform="translate(1 2)"><rect x="0" y="0" width="4" height="4" fill="#ffffff"/></g>""")
    assertEquals(SvgProfile.violations(svg), Vector.empty)

  test("elements that open a file or network path are violations"):
    List("image", "foreignObject", "script", "style", "use", "clipPath", "mask", "pattern", "filter", "textPath").foreach { name =>
      val found = SvgProfile.violations(document(s"<$name/>"))
      assertEquals(found, Vector(s"element <$name> is outside the profile"))
    }

  test("an attribute outside the whitelist is a violation"):
    val found = SvgProfile.violations(document("""<rect x="0" y="0" width="1" height="1" href="http://example.org/a.png"/>"""))
    assertEquals(found, Vector("attribute href is outside the profile"))

  test("a font family outside the pinned pack is a violation, including generic families"):
    val found = SvgProfile.violations(document("""<text x="0" y="0" font-family="sans-serif">a</text>"""))
    assertEquals(found, Vector("font family 'sans-serif' is not in the font pack"))

  test("a paint server reference that is not a local gradient is a violation"):
    val found = SvgProfile.violations(document("""<rect x="0" y="0" width="1" height="1" fill="url(http://example.org/#g1)"/>"""))
    assertEquals(found, Vector("fill refers outside the document"))
    assertEquals(SvgProfile.violations(document("""<rect x="0" y="0" width="1" height="1" fill="url(#g1)"/>""")), Vector.empty)
