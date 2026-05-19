package no.nav.nks_ai.api.app

import io.getunleash.DefaultUnleash
import io.getunleash.Unleash
import io.getunleash.util.UnleashConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class FeatureToggles private constructor(private val unleash: Unleash?) {

    fun isVaskemaskinEnabled(): Boolean =
        unleash?.isEnabled(VASKEMASKIN, false) ?: false

    companion object {
        private const val VASKEMASKIN = "nks-bob-api.vaskemaskin"

        fun create(settings: UnleashSettings): FeatureToggles {
            if (!settings.isConfigured) {
                logger.info { "Unleash not configured — all toggles use defaults (vaskemaskin=false)" }
                return FeatureToggles(unleash = null)
            }

            val config = UnleashConfig.builder()
                .appName(settings.appName)
                .instanceId(settings.appName)
                .unleashAPI("${settings.serverApiUrl}/api")
                .customHttpHeader("Authorization", settings.serverApiToken)
                .build()

            logger.info { "Unleash configured: ${settings.serverApiUrl}" }
            return FeatureToggles(DefaultUnleash(config))
        }
    }
}
