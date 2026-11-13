package dosegram.render.tokens

import dosegram.render.color.Rgb

/** The two image themes (VISUALS section 3.3.3). */
enum Theme:
  case Light, Dark

/** The five tracked domains that own a hue (VISUALS D6). The body domain has none and is drawn in neutral ink. */
enum Domain:
  case Medication, Water, Food, Mood, Sleep

/** One theme's resolved colour tokens (VISUALS section 3.3). This file is the single source of every colour in a
  * generated image; `TokensCss` derives the Mini App stylesheet from it. The `critical` token is absent on purpose: it
  * is not allowed in any image (VA-PR-08).
  */
final case class Tokens(
    theme: Theme,
    surface: Rgb,
    panel: Rgb,
    grid: Rgb,
    ink: Rgb,
    ink2: Rgb,
    ink3: Rgb,
    attention: Rgb,
    spruce: Rgb,
    tide: Rgb,
    rye: Rgb,
    beet: Rgb,
    dusk: Rgb,
    spruceLate: Rgb,
    spruceHeat: Vector[Rgb]
):
  def hue(domain: Domain): Rgb =
    domain match
      case Domain.Medication => spruce
      case Domain.Water      => tide
      case Domain.Food       => rye
      case Domain.Mood       => beet
      case Domain.Sleep      => dusk

  /** The five domain bases in the fixed order of VISUALS D6. */
  def domainHues: Vector[Rgb] = Domain.values.toVector.map(hue)

  /** Every colour a card may use outside the blob, for the palette whitelist lint. */
  def palette: Set[Rgb] =
    Set(surface, panel, grid, ink, ink2, ink3, spruceLate) ++ domainHues ++ spruceHeat

object Tokens:
  val Light: Tokens = Tokens(
    theme = Theme.Light,
    surface = Rgb.hex("#eef5f8"),
    panel = Rgb.hex("#ffffff"),
    grid = Rgb.hex("#cdd6db"),
    ink = Rgb.hex("#13242e"),
    ink2 = Rgb.hex("#435561"),
    ink3 = Rgb.hex("#5e6e78"),
    attention = Rgb.hex("#c9690a"),
    spruce = Rgb.hex("#0d9675"),
    tide = Rgb.hex("#02558f"),
    rye = Rgb.hex("#955907"),
    beet = Rgb.hex("#940f52"),
    dusk = Rgb.hex("#8a66d9"),
    spruceLate = Rgb.hex("#78c3a9"),
    spruceHeat = Vector("#78c3a9", "#32b08d", "#007c60", "#004937").map(Rgb.hex)
  )

  val Dark: Tokens = Tokens(
    theme = Theme.Dark,
    surface = Rgb.hex("#0f222b"),
    panel = Rgb.hex("#071820"),
    grid = Rgb.hex("#2f434d"),
    ink = Rgb.hex("#e8f0f4"),
    ink2 = Rgb.hex("#b1c0c8"),
    ink3 = Rgb.hex("#8b9ba3"),
    attention = Rgb.hex("#ea942f"),
    spruce = Rgb.hex("#2cab88"),
    tide = Rgb.hex("#0072be"),
    rye = Rgb.hex("#9e5d00"),
    beet = Rgb.hex("#bf3f75"),
    dusk = Rgb.hex("#9d7aef"),
    spruceLate = Rgb.hex("#038265"),
    spruceHeat = Vector("#04614b", "#038265", "#29a987", "#8bd6bc").map(Rgb.hex)
  )

  def of(theme: Theme): Tokens =
    theme match
      case Theme.Light => Light
      case Theme.Dark  => Dark
