package dev.kento.data.net

import dev.kento.core.text.tr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** One model of `GET {OpenUncensored}/images/models`. */
@Serializable
data class ImageModel(
    val id: String,
    val name: String = id,
    val generate: Boolean = true,
    val edit: Boolean = false,
    val maxInputs: Int? = null,
    val priceUsd: Double? = null,
    /** Reference editors bill each input picture on top of the base price. */
    val priceInputUsd: Double? = null,
    val description: String = ""
) {
    fun accepts(references: Int) = if (references == 0) generate else edit && (maxInputs == null || references <= maxInputs)

    /** "$0.0255 + $0.0102 за фото · всего $0.0459" for this request, or the base price alone. */
    fun priceText(references: Int): String? {
        fun usd(v: Double) = "$" + java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
        val base = priceUsd ?: return null
        val extra = priceInputUsd ?: return tr("{0} за изображение", usd(base))
        val total = java.math.BigDecimal.valueOf(base).add(java.math.BigDecimal.valueOf(extra).multiply(references.toBigDecimal())).toDouble()
        return if (references > 0) tr("{0} + {1} за фото · всего {2}", usd(base), usd(extra), usd(total))
            else tr("{0} + {1} за каждое фото", usd(base), usd(extra))
    }
}

internal fun imageCatalog(raw: String): List<ImageModel> {
    val root = wireJson.parseToJsonElement(raw)
    val items = (root as? JsonObject)?.get("data") as? JsonArray ?: root as? JsonArray
        ?: throw ProviderIssue(tr("Неверный формат каталога изображений"))
    return items.mapNotNull { item ->
        val m = item as? JsonObject ?: return@mapNotNull null
        val id = m.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val ops = m.strings("operations")
        ImageModel(id, m.str("name") ?: id, ops?.contains("generate") ?: true, ops?.contains("edit") ?: false,
            m.str("max_input_images")?.toIntOrNull(), m.str("price_per_image_usd")?.toDoubleOrNull(),
            priceInputUsd = m.str("price_per_input_image_usd")?.toDoubleOrNull(),
            description = m.str("description").orEmpty().take(200))
    }.distinctBy { it.id }
}
