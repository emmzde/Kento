package dev.kento.data.net

import dev.kento.core.text.tr
import kotlinx.serialization.json.*

/**
 * The provider's own words from an error body: `error.message` (Refuseless, OpenUncensored, OpenAI shape), a plain
 * `error` string, or a top-level `message`/`detail`. Null when the body says nothing useful.
 */
internal fun errorDetail(body: String, secret: String? = null): String? {
    val obj = runCatching { wireJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val error = obj["error"]
    val text = (error as? JsonObject)?.str("message") ?: (error as? JsonPrimitive)?.contentOrNull
        ?: obj.str("message") ?: obj.str("detail")
    // Some servers echo the credential back ("Invalid key abl_…"): it must never reach the screen or the journal.
    val clean = text?.let { t -> secret?.trim()?.takeIf { it.length >= 6 }?.let { t.replace(it, tr("[скрыто]")) } ?: t }
        ?.let(dev.kento.data.log.Journal::mask)
    return clean?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(220)
}

/** The machine-readable `error.code` of a body, e.g. `insufficient_credits` or `model_warming`. */
internal fun errorCode(body: String): String? = runCatching {
    ((wireJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject)?.str("code")
}.getOrNull()

/** The API key a request carried, so an error body can be cleaned of it. */
internal fun okhttp3.Response.sentKey(): String? = request.header("Authorization")?.removePrefix("Bearer ")?.trim()

/**
 * What an error code means for the user, for the codes Refuseless and OpenUncensored document. Null for codes that
 * the HTTP status already explains well enough.
 */
internal fun codeReason(code: String?): String? = when (code) {
    "insufficient_credits", "insufficient_quota" -> tr("На счёте закончились кредиты. Пополните баланс")
    "insufficient_credits_for_hold" -> tr("Кредитов мало для такого запроса. Пополните баланс или уменьшите «Максимум токенов ответа»")
    "key_budget_exceeded" -> tr("Исчерпан бюджет этого ключа")
    "key_daily_budget_exceeded" -> tr("Исчерпан дневной бюджет ключа")
    "missing_api_key", "invalid_key", "key_revoked" -> tr("Проверь API-ключ")
    "invalid_key_format" -> tr("Ключ не похож на ключ этого сервиса")
    "key_expired" -> tr("Срок действия ключа истёк")
    "email_unverified", "account_deleted", "member_inactive" -> tr("Аккаунт ключа недоступен")
    "model_not_found" -> tr("Модель не найдена в каталоге")
    "model_busy", "concurrency_limit" -> tr("Модель занята, попробуйте ещё раз")
    "model_warming" -> tr("Модель запускается, попробуйте через полминуты")
    "rate_limit_exceeded" -> tr("Превышен лимит запросов")
    "model_unavailable", "worker_unavailable", "no_provider_available", "all_providers_failed" -> tr("Модель сейчас недоступна")
    "upstream_error", "gateway_down", "gateway_error", "billing_unavailable" -> tr("Сбой на стороне провайдера, попробуйте ещё раз")
    "invalid_size" -> tr("Провайдер не принял размер изображения")
    "invalid_image" -> tr("Референс не подошёл: нужен PNG, JPEG, WebP или GIF до 20 МБ")
    "too_many_images" -> tr("Слишком много фото для этой модели")
    "model_cannot_edit" -> tr("Эта модель не редактирует изображения")
    "edit_only_model" -> tr("Эта модель только редактирует: приложите фото")
    else -> null
}

/**
 * The status line while Kento itself waits for a cold or busy model ([Provider.stream] retries as Refuseless asks):
 * it says what is happening and asks nothing of the user, unlike [codeReason], which is the error once retries ran out.
 */
internal fun waitReason(code: String?): String =
    if (code == "model_warming") tr("Модель запускается…") else tr("Модель занята, жду…")
