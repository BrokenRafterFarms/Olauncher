val entry = "MyFolder:pkg:com.android.settings,pkg:com.google.android.apps.photos"
val partsNoLimit = entry.split(":")
println("Parts without limit: ${partsNoLimit.joinToString(" | ")}")

val partsWithLimit = entry.split(":", limit = 2)
println("Parts with limit 2: ${partsWithLimit.joinToString(" | ")}")

if (partsWithLimit.size > 1) {
    val items = partsWithLimit[1].split(",").filter { it.isNotBlank() }
    println("Items: ${items.joinToString(" | ")}")
}
