package dev.dobrinskiy.livetype.config

import dev.dobrinskiy.livetype.BuildConfig

/**
 * Which worker the app talks to, chosen from a dropdown in debug builds.
 *
 * Release builds have no such control: they always behave like [CUSTOM], with
 * the user typing their deployed worker's URL by hand. The dropdown exists so
 * that during development you can flip between the laptop and a deployed
 * worker without retyping a URL.
 */
enum class EndpointMode {
    /** A deployed Cloudflare Worker. No URL exists yet — see [isAvailable]. */
    PROD,

    /** `wrangler dev` on the laptop, reached over `adb reverse`. */
    DEV,

    /** Anything else; the URL is typed by hand. */
    CUSTOM,
    ;

    /**
     * Whether this mode can currently be selected. `PROD` is listed but not
     * selectable until the worker is actually deployed and [PROD_ENDPOINT] is
     * filled in — showing it greyed out is more honest than hiding it, since
     * it tells the reader the plan without pretending it works.
     */
    val isAvailable: Boolean
        get() = this != PROD || PROD_ENDPOINT.isNotBlank()

    companion object {
        /**
         * Read from `worker/.dev.vars` at build time, never committed: the
         * repository is public and a worker URL is a live endpoint. Blank
         * unless that file supplies `PROD_TOKEN_ENDPOINT`, and blank is what
         * keeps the prod row in the settings dropdown disabled — the same
         * honest "not ready yet" it showed before the worker existed.
         */
        val PROD_ENDPOINT: String get() = BuildConfig.PROD_TOKEN_ENDPOINT

        /** Only debug builds bake in a local endpoint; release leaves it blank. */
        val devEndpoint: String
            get() = BuildConfig.DEFAULT_TOKEN_ENDPOINT

        /** The dropdown is a debug-only affordance. */
        val isSelectable: Boolean
            get() = BuildConfig.DEBUG

        /**
         * A stored mode that is no longer usable falls back to [default]:
         * `PROD` could be persisted by a build where it was available (or by a
         * hand-edited preference), and restoring it would leave the UI on a row
         * that cannot be selected and an endpoint that resolves to nothing.
         */
        fun from(name: String?): EndpointMode =
            entries.firstOrNull { it.name == name }?.takeIf { it.isAvailable } ?: default()

        /**
         * Debug builds start on DEV because that is what their baked endpoint
         * points at; release builds have nothing baked in, so CUSTOM is the
         * only mode that makes sense.
         */
        fun default(): EndpointMode = if (BuildConfig.DEBUG) DEV else CUSTOM

        /** The URL a mode implies, or null when the user supplies it. */
        fun endpointFor(mode: EndpointMode): String? = when (mode) {
            PROD -> PROD_ENDPOINT.ifBlank { null }
            DEV -> devEndpoint.ifBlank { null }
            CUSTOM -> null
        }
    }
}
