package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent

/**
 * One generic template for every event: a subject from the event label, then the
 * headline, a card of labelled detail cells, the actor, and an optional deep link.
 * Adding an event needs no template change — the triggering use case just supplies
 * a richer context.
 *
 * The layout is a nested-table email shell (600px card, header bar, metric card,
 * button, footer) because that is the only structure every mail client renders the
 * same way. Every style is inline for the same reason: clients strip `<style>`
 * blocks and none of them resolve CSS variables.
 */
internal object NotificationTemplates {

    data class Rendered(val subject: String, val text: String, val html: String)

    /**
     * The IBMS palette, mirrored from the webapp's design tokens so mail and app read
     * as one system. Source of truth is the `@theme` block in the sibling repo's
     * `billing-management-system/src/index.css`, which redefines Tailwind's `slate`
     * (warm cream) and `blue` (deep teal) scales; [PAGE_BG] is the app shell colour
     * from its `App.tsx`. Update these together with that file, never independently.
     */
    private const val PAGE_BG = "#F0EFDF"
    private const val CARD_BG = "#FFFFFF"
    private const val CARD_BORDER = "#E4E2CC"
    private const val BRAND = "#00473E"
    private const val BRAND_SOFT = "#9FD0C1"
    private const val METRIC_BG = "#E2F4EE"
    private const val METRIC_BORDER = "#B8DFD3"
    private const val TEXT = "#11100C"
    private const val TEXT_BODY = "#514E3A"
    private const val TEXT_MUTED = "#8E8967"
    private const val FOOTER_BG = "#F7F6E7"

    /**
     * The webapp's `--font-sans`, minus the Google Fonts import — clients block or
     * strip webfonts and Outlook ignores `@import` outright, so most recipients see
     * the fallback. The stack is here so anyone who has the face installed matches.
     */
    private const val FONT = "'Plus Jakarta Sans', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"

    private const val PRODUCT = "ISP Billing Management System"
    private const val FOOTER_AUTOMATED = "This is an automated operational email from IBMS."
    private const val FOOTER_HELP = "Need assistance? Contact your system administrator."

    /** Marks a detail value as money, which is what earns it the emphasised styling. */
    private const val PESO = "₱"

    fun render(event: NotificationEvent, ctx: NotificationContext, appUrl: String): Rendered {
        val subject = "[IBMS] ${event.label}"
        val link = ctx.linkPath?.let { appUrl.trimEnd('/') + it }
        val cta = "View ${ctaNoun(event)} in IBMS"

        val textLines = buildList {
            add(event.label)
            add("")
            add(ctx.headline)
            if (ctx.details.isNotEmpty()) {
                add("")
                ctx.details.forEach { (label, value) -> add("$label: $value") }
            }
            ctx.actorName?.let { add(""); add("Performed by: $it") }
            link?.let { add(""); add("$cta: $it") }
            add("")
            add("— $FOOTER_AUTOMATED $FOOTER_HELP")
        }

        return Rendered(subject, textLines.joinToString("\n"), html(event, ctx, link, cta))
    }

    private fun html(
        event: NotificationEvent,
        ctx: NotificationContext,
        link: String?,
        cta: String,
    ): String = buildString {
        append("<!DOCTYPE html>")
        append("<html lang=\"en\"><head>")
        append("<meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        append("<title>${esc(event.label)}</title>")
        append("</head>")
        append("<body style=\"margin:0;padding:0;background-color:$PAGE_BG;font-family:$FONT;color:$TEXT;\">")

        // Inbox preview line. Without it clients fall back to the eyebrow, so every
        // notification would preview as the identical product name.
        append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">${esc(ctx.headline)}</div>")

        append(openTable("width=\"100%\"", "background-color:$PAGE_BG;padding:24px 0;"))
        append("<tr><td align=\"center\">")
        append(
            openTable(
                "width=\"600\"",
                "width:600px;max-width:100%;background-color:$CARD_BG;" +
                    "border:1px solid $CARD_BORDER;border-radius:8px;overflow:hidden;",
            ),
        )

        append("<tr><td style=\"background-color:$BRAND;padding:20px 30px;\">")
        append(
            "<span style=\"font-size:12px;font-weight:700;letter-spacing:1px;" +
                "text-transform:uppercase;color:$BRAND_SOFT;\">$PRODUCT</span>",
        )
        append("<h2 style=\"margin:4px 0 0 0;font-size:20px;font-weight:600;color:#FFFFFF;\">${esc(event.label)}</h2>")
        append("</td></tr>")

        append("<tr><td style=\"padding:30px;\">")
        append("<p style=\"margin:0 0 20px 0;font-size:15px;line-height:1.5;color:$TEXT_BODY;\">${esc(ctx.headline)}</p>")
        if (ctx.details.isNotEmpty()) append(metricCard(ctx.details))
        ctx.actorName?.let {
            append("<p style=\"margin:0 0 20px 0;font-size:13px;color:$TEXT_MUTED;\">Performed by ${esc(it)}</p>")
        }
        link?.let { append(ctaButton(it, cta)) }
        append("</td></tr>")

        append(
            "<tr><td style=\"background-color:$FOOTER_BG;padding:20px 30px;border-top:1px solid $CARD_BORDER;" +
                "font-size:12px;color:$TEXT_MUTED;text-align:center;\">",
        )
        append("<p style=\"margin:0 0 6px 0;\">$FOOTER_AUTOMATED</p>")
        append("<p style=\"margin:0;\">$FOOTER_HELP</p>")
        append("</td></tr>")

        append("</table></td></tr></table></body></html>")
    }

    /**
     * The details as a two-per-row grid. A lone trailing detail spans both columns
     * rather than leaving a ragged empty cell — events carry anywhere from one
     * detail (account number) to five (a compiled top sheet).
     */
    private fun metricCard(details: List<Pair<String, String>>): String = buildString {
        val rows = details.chunked(2)
        append(
            openTable(
                "width=\"100%\"",
                "background-color:$METRIC_BG;border:1px solid $METRIC_BORDER;border-radius:6px;margin-bottom:24px;",
            ),
        )
        rows.forEachIndexed { index, row ->
            append("<tr>")
            row.forEach { (label, value) -> append(cell(label, value, full = row.size == 1, last = index == rows.lastIndex)) }
            append("</tr>")
        }
        append("</table>")
    }

    private fun cell(label: String, value: String, full: Boolean, last: Boolean): String {
        val span = if (full) " colspan=\"2\" width=\"100%\"" else " width=\"50%\""
        val divider = if (last) "" else "border-bottom:1px solid $METRIC_BORDER;"
        // Amounts arrive already formatted for display (see Money.display), which is
        // what makes them detectable here — the template never guesses from the label.
        val isAmount = value.startsWith(PESO)
        val weight = if (isAmount) "700" else "600"
        val colour = if (isAmount) BRAND else TEXT
        return "<td$span style=\"padding:16px 20px;$divider\">" +
            "<span style=\"font-size:12px;font-weight:600;letter-spacing:0.5px;" +
            "text-transform:uppercase;color:$BRAND;\">${esc(label)}</span>" +
            "<div style=\"font-size:16px;font-weight:$weight;color:$colour;margin-top:2px;\">${esc(value)}</div>" +
            "</td>"
    }

    private fun ctaButton(href: String, label: String): String =
        openTable("width=\"100%\"", "") +
            "<tr><td align=\"center\">" +
            "<a href=\"${esc(href)}\" style=\"background-color:$BRAND;color:#FFFFFF;text-decoration:none;" +
            "padding:12px 28px;border-radius:6px;font-weight:600;font-size:14px;display:inline-block;\">" +
            "${esc(label)} &rarr;</a>" +
            "</td></tr></table>"

    /** `role`/`border` keep Outlook laying tables out and screen readers out of them. */
    private fun openTable(width: String, style: String): String =
        "<table role=\"presentation\" $width border=\"0\" cellpadding=\"0\" cellspacing=\"0\"" +
            (if (style.isEmpty()) "" else " style=\"$style\"") + ">"

    /**
     * The noun the "view" button uses. Exhaustive on purpose — no `else` — so a ninth
     * event fails compilation here instead of silently shipping a vaguer button.
     */
    private fun ctaNoun(event: NotificationEvent): String = when (event) {
        NotificationEvent.STORE_CREATED -> "Store"
        NotificationEvent.ACCOUNT_CREATED,
        NotificationEvent.ACCOUNT_UPDATED,
        NotificationEvent.ACCOUNT_TRANSFERRED,
        NotificationEvent.ACCOUNT_TERMINATED,
        NotificationEvent.ACCOUNT_DEACTIVATION_REQUESTED,
        -> "Account"
        NotificationEvent.TOPSHEET_COMPILED,
        NotificationEvent.TOPSHEET_RELEASED,
        -> "Topsheet"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
