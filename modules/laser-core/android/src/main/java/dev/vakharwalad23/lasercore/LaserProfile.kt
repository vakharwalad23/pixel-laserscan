package dev.vakharwalad23.lasercore

import android.content.Context
import android.os.Build

// Source of truth: the observed dark laser test, then Google's per-model specs, else unknown.
enum class LaserKind(val id: String) {
  MULTI("multi"),     // multi-zone ToF (8 Pro, 9 Pro, 10, 10 Pro, 11 ...); 8 Pro is an 8x8 VL53L8
  SINGLE("single"),   // single-zone ToF (6, 7, 8, 9 ...)
  LASER("laser"),     // has LDAF, zone count not published (Folds)
  NONE("none"),       // no laser: focus distance comes from PDAF / contrast only
  UNKNOWN("unknown");

  companion object {
    fun from(id: String?): LaserKind? = values().firstOrNull { it.id == id }
  }
}

data class LaserTuning(
  val coneHalfDeg: Float,     // half-angle of the emitter cone used for the edge check
  val agreeLo: Float,         // accepted laser/ARCore ratio range on the AF ray
  val agreeHi: Float,
  val edgeSpread: Float,      // (p90-p10)/p50 of ARCore depth inside the cone above which the cone straddles an edge
  val edgeAgree: Float,       // when straddling an edge, the laser must match the centre depth this closely
  val weight: Float,          // TSDF weight of one hit (one depth ray = 1)
  val spotHalfAngle: Float,   // radians, splat footprint of one hit
  val maxRange: Float,        // meters
  val strictWithoutDepth: Boolean // with no ARCore depth to cross-check, only accept just-settled readings
)

data class Profile(
  val kind: LaserKind,
  val tableKind: LaserKind,
  val observedKind: LaserKind?,
  val source: String,
  val family: String,
  val note: String
) {
  val tuning: LaserTuning get() = tuningFor(kind)

  fun toMap(): Map<String, Any?> = mapOf(
    "kind" to kind.id,
    "tableKind" to tableKind.id,
    "observedKind" to observedKind?.id,
    "source" to source,
    "family" to family,
    "note" to note,
    "manufacturer" to Build.MANUFACTURER,
    "model" to Build.MODEL,
    "device" to Build.DEVICE,
    "maxRangeM" to tuning.maxRange.toDouble(),
    "coneHalfDeg" to tuning.coneHalfDeg.toDouble()
  )
}

object LaserProfiles {
  private const val PREFS = "lasercore"
  private const val KEY_KIND = "observedLaserKind"
  private const val KEY_MODEL = "observedLaserModel"

  fun tuningFor(kind: LaserKind): LaserTuning = when (kind) {
    LaserKind.MULTI -> LaserTuning(3f, 0.7f, 1.4f, 0.35f, 0.08f, 6f, 0.02f, 3.5f, false)
    LaserKind.SINGLE, LaserKind.LASER -> LaserTuning(12.5f, 0.8f, 1.25f, 0.12f, 0.06f, 4f, 0.012f, 2.5f, true)
    LaserKind.NONE -> LaserTuning(4f, 0.85f, 1.18f, 0.10f, 0.05f, 1.5f, 0.01f, 1.2f, true)
    LaserKind.UNKNOWN -> LaserTuning(12.5f, 0.8f, 1.25f, 0.12f, 0.06f, 3f, 0.012f, 2.0f, true)
  }

  // Build.DEVICE codename -> marketing model, for builds that report odd MODEL strings.
  private val codenames = mapOf(
    "sailfish" to "Pixel", "marlin" to "Pixel XL", "walleye" to "Pixel 2", "taimen" to "Pixel 2 XL",
    "blueline" to "Pixel 3", "crosshatch" to "Pixel 3 XL", "sargo" to "Pixel 3a", "bonito" to "Pixel 3a XL",
    "flame" to "Pixel 4", "coral" to "Pixel 4 XL", "sunfish" to "Pixel 4a", "bramble" to "Pixel 4a 5G",
    "redfin" to "Pixel 5", "barbet" to "Pixel 5a", "oriole" to "Pixel 6", "raven" to "Pixel 6 Pro",
    "bluejay" to "Pixel 6a", "panther" to "Pixel 7", "cheetah" to "Pixel 7 Pro", "lynx" to "Pixel 7a",
    "felix" to "Pixel Fold", "tangorpro" to "Pixel Tablet", "shiba" to "Pixel 8", "husky" to "Pixel 8 Pro",
    "akita" to "Pixel 8a", "tokay" to "Pixel 9", "caiman" to "Pixel 9 Pro", "komodo" to "Pixel 9 Pro XL",
    "comet" to "Pixel 9 Pro Fold", "tegu" to "Pixel 9a", "frankel" to "Pixel 10", "blazer" to "Pixel 10 Pro",
    "mustang" to "Pixel 10 Pro XL", "rango" to "Pixel 10 Pro Fold", "stallion" to "Pixel 10a"
  )

  private val modelRe = Regex(
    """^pixel(?:\s+(\d+))?\s*(a)?(?:\s*\(?5g\)?)?(\s+pro)?(\s+xl)?(\s+fold)?(\s+tablet)?\s*$""",
    RegexOption.IGNORE_CASE
  )

  fun classify(model: String): Pair<LaserKind, String> {
    val m = modelRe.find(model.trim()) ?: return LaserKind.UNKNOWN to "not a recognised Pixel model"
    val gen = m.groupValues[1].toIntOrNull()
    val isA = m.groupValues[2].isNotEmpty()
    val pro = m.groupValues[3].isNotEmpty()
    val fold = m.groupValues[5].isNotEmpty()
    val tablet = m.groupValues[6].isNotEmpty()
    if (tablet) return LaserKind.NONE to "Pixel Tablet has no laser AF"
    if (gen == null) {
      return if (fold) LaserKind.LASER to "Pixel Fold has LDAF; zone count isn't published"
      else LaserKind.SINGLE to "original Pixel: single-beam laser assist"
    }
    return when {
      gen == 2 -> LaserKind.SINGLE to "Pixel 2: single-zone laser"
      gen in 3..5 -> LaserKind.NONE to "Pixel $gen series has no laser AF"
      isA && gen <= 8 -> LaserKind.NONE to "Pixel ${gen}a has no laser AF"
      isA -> LaserKind.UNKNOWN to "Pixel ${gen}a laser AF isn't confirmed; run the laser test"
      fold -> LaserKind.LASER to "Fold models have LDAF; zone count isn't published"
      gen in 6..7 -> LaserKind.SINGLE to "Pixel $gen series: single-zone laser"
      gen in 8..9 && pro -> LaserKind.MULTI to "Pixel $gen Pro: multi-zone laser"
      gen in 8..9 -> LaserKind.SINGLE to "Pixel $gen: single-zone laser"
      gen in 10..11 -> LaserKind.MULTI to "Pixel $gen${if (pro) " Pro" else ""}: multi-zone laser"
      else -> LaserKind.UNKNOWN to "Pixel $gen isn't in the table yet; run the laser test"
    }
  }

  fun detect(ctx: Context): Profile {
    val isGoogle = Build.MANUFACTURER.equals("Google", ignoreCase = true)
    val family = when {
      !isGoogle -> Build.MANUFACTURER + " " + Build.MODEL
      modelRe.matches(Build.MODEL.trim()) -> Build.MODEL.trim()
      else -> codenames[Build.DEVICE.lowercase()] ?: Build.MODEL
    }
    val (tableKind, note) = if (isGoogle) classify(family)
    else LaserKind.UNKNOWN to "not a Pixel; run the laser test to find out"
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val observed = if (prefs.getString(KEY_MODEL, null) == Build.MODEL) LaserKind.from(prefs.getString(KEY_KIND, null)) else null
    val kind = observed ?: tableKind
    val source = when {
      observed != null -> "observed"
      tableKind != LaserKind.UNKNOWN -> "model table"
      else -> "unknown"
    }
    return Profile(kind, tableKind, observed, source, family, note)
  }

  fun saveObserved(ctx: Context, kind: LaserKind?) {
    val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
    if (kind == null) e.remove(KEY_KIND).remove(KEY_MODEL)
    else e.putString(KEY_KIND, kind.id).putString(KEY_MODEL, Build.MODEL)
    e.apply()
  }
}

fun tuningFor(kind: LaserKind) = LaserProfiles.tuningFor(kind)
