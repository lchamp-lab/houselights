package app.houselights.journal

import app.houselights.player.Redact

/** The page's words, in the app's language ([language], a BCP 47 tag). */
data class JournalWords(
    val language: String,
    val title: String,
    val text: String,
    val copy: String,
    val copied: String,
    val download: String,
    val empty: String,
)

/**
 * What a report needs beside the lines, to paste as it is: [facts] (the app and its version first, then the device,
 * the source and Trakt), then [time], when the lines were read.
 */
data class JournalHead(val facts: List<String>, val time: String) {
    val line: String get() = (facts + time).joinToString(SEPARATOR)

    private companion object {
        const val SEPARATOR = " · "
    }
}

/**
 * The journal as a phone shows it ([html]) and as it is downloaded ([text]). The page is dark and plain, needs
 * nothing from elsewhere, and escapes every line; its one script copies the text.
 */
object JournalPage {
    /** The downloaded text: the head's line, then the lines oldest first, as logcat wrote them. */
    fun text(head: JournalHead, lines: List<String>): String = buildString {
        append(head.line).append("\n\n")
        lines.forEach { append(it).append('\n') }
    }

    /**
     * The page: the head's line, Copier and Télécharger ([download], the text's address relative to the page), then the
     * lines newest first, warnings and errors in colour. [nonce] lets its style and script run, nothing else.
     */
    fun html(head: JournalHead, lines: List<String>, words: JournalWords, download: String, nonce: String): String {
        val facts = head.facts.mapIndexed { i, fact -> if (i == 0) "<b>${escape(fact)}</b>" else escape(fact) }
        val title = escape(words.title)
        return buildString {
            append("<!doctype html>\n<html lang=\"").append(escape(words.language)).append("\">\n<head>\n")
            append("<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<meta name=\"referrer\" content=\"no-referrer\">\n<title>").append(title).append(" · Houselights</title>\n")
            append("<style nonce=\"").append(nonce).append("\">").append(STYLE).append("</style>\n</head>\n<body>\n")
            append("<header>").append(MARK).append("<b>HOUSELIGHTS</b></header>\n")
            append("<h1>").append(title).append("</h1>\n<p class=\"sub\">").append(escape(words.text)).append("</p>\n")
            append("<p class=\"line\" id=\"head\">").append((facts + escape(head.time)).joinToString(" · ")).append("</p>\n")
            append("<div class=\"btns\"><button id=\"copy\" class=\"w\" type=\"button\" data-done=\"").append(escape(words.copied))
            append("\">").append(escape(words.copy)).append("</button><a class=\"g\" href=\"").append(escape(download))
            append("\" download>").append(escape(words.download)).append("</a></div>\n")
            if (lines.isEmpty()) {
                append("<p class=\"sub\">").append(escape(words.empty)).append("</p>\n")
            } else {
                append("<pre id=\"log\">")
                lines.asReversed().forEach { append(line(it)) }
                append("</pre>\n")
            }
            append("<script nonce=\"").append(nonce).append("\">").append(SCRIPT).append("</script>\n</body>\n</html>\n")
        }
    }

    /** A line as the page shows it: its time, level, tag and message, in colour from a warning up, its masks lit. */
    internal fun line(raw: String): String {
        val match = THREADTIME.matchEntire(raw)
        val shown = match?.groupValues?.let { "${it[TIME]} ${it[LEVEL]} ${it[TAG].padEnd(TAG_WIDTH)} ${it[MESSAGE]}" } ?: raw
        val escaped = escape(shown).replace(Redact.TEXT_MASK, "<span class=\"m\">${Redact.TEXT_MASK}</span>")
        val kind = when (match?.groupValues?.get(LEVEL)) {
            "W" -> "w"
            "E", "F", "A" -> "e"
            else -> null
        }
        return (if (kind == null) escaped else "<span class=\"$kind\">$escaped</span>") + "\n"
    }

    /** [text] safe anywhere in the page's HTML, inside an attribute's quotes included. */
    fun escape(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    /** "10-23 10:42:11.123  1234  1256 W Trakt   : message": the time, the level, the tag and the message. */
    private val THREADTIME = Regex("""\d\d-\d\d (\d\d:\d\d:\d\d)\.\d+\s+\d+\s+\d+ ([VDIWEFA]) (.*?)\s*: (.*)""")
    private const val TAG_WIDTH = 8

    /** Its groups, by number. */
    private const val TIME = 1
    private const val LEVEL = 2
    private const val TAG = 3
    private const val MESSAGE = 4

    /** Gradation, three bars halving in the ember light. */
    private const val MARK =
        "<svg viewBox=\"31.5 31 46 46\" aria-hidden=\"true\"><defs><linearGradient id=\"e\">" +
            "<stop offset=\"0\" stop-color=\"#FF9F1C\"/><stop offset=\"1\" stop-color=\"#FF2E63\"/></linearGradient></defs>" +
            "<path fill=\"url(#e)\" d=\"M31.5 31h20.444v46h-20.444zM57.26 31h10.222v46h-10.222zM72.389 31h5.111v46h-5.111z\"/></svg>"

    private val STYLE = """
        :root{color-scheme:dark}
        *{box-sizing:border-box}
        body{margin:0;background:#0B0B0C;color:#EDEDF0;font:16px/1.4 system-ui,sans-serif;-webkit-text-size-adjust:100%}
        header{display:flex;align-items:center;gap:10px;padding:20px 20px 0}
        header svg{width:16px;height:16px}
        header b{font-size:15px;letter-spacing:.14em;font-stretch:125%}
        h1{margin:18px 20px 4px;font-size:28px;line-height:32px;font-stretch:70%}
        .sub{margin:0 20px;font-size:14px;line-height:20px;color:#A3A3AB}
        .line{margin:16px 20px 0;padding:12px 14px;border-radius:12px;background:#17171A;box-shadow:inset 0 0 0 1px #2A2A30;
        font-size:13px;line-height:19px;color:#CFCFD4}
        .line b{color:#EDEDF0;font-weight:600}
        .btns{margin:14px 20px 0;display:flex;gap:10px}
        .btns>*{flex:1;height:44px;border:0;border-radius:10px;display:flex;align-items:center;justify-content:center;
        font:600 15px system-ui,sans-serif;text-decoration:none}
        .btns .w{background:#F4F4F5;color:#000}
        .btns .g{background:#17171A;color:#EDEDF0;box-shadow:inset 0 0 0 1px #33333A}
        pre{margin:16px 0 0;padding:12px 20px 32px;font:11px/16px ui-monospace,monospace;color:#B4B4BC;white-space:pre-wrap;
        word-break:break-all;background:#08080A;border-top:1px solid #1E1E22}
        pre .w{color:#FFB38A}
        pre .e{color:#FF8FA3}
        pre .m{color:#7FD1A8}
    """.trimIndent().replace("\n", "")

    /**
     * Copier: the head's line and the lines as shown, oldest first, as a report reads them. Without the clipboard, which an
     * address in plain HTTP does not open, through a selection; failing that, the lines stay selected for the phone's
     * own Copy.
     */
    private val SCRIPT = """
        (function(){
        var b=document.getElementById('copy'),p=document.getElementById('log');
        function text(){var l=p?p.textContent.replace(/\n$/,'').split('\n').reverse().join('\n')+'\n':'';
        return document.getElementById('head').textContent+'\n\n'+l}
        function done(){b.textContent=b.getAttribute('data-done')}
        function select(t){var a=document.createElement('textarea');a.value=t;a.setAttribute('readonly','');
        a.style.position='fixed';a.style.opacity='0';document.body.appendChild(a);a.select();a.setSelectionRange(0,t.length);
        var ok=false;try{ok=document.execCommand('copy')}catch(e){}document.body.removeChild(a);
        if(ok){done()}else if(p){var r=document.createRange();r.selectNodeContents(p);
        var s=getSelection();s.removeAllRanges();s.addRange(r)}}
        b.addEventListener('click',function(){var t=text();
        if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t).then(done,function(){select(t)})}
        else{select(t)}});
        })();
    """.trimIndent().replace("\n", "")
}
