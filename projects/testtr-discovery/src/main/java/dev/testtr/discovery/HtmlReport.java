package dev.testtr.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Standalone, offline report. All observed page content is escaped as text. */
final class HtmlReport {
    static String render(JsonNode report) {
        var groups = new LinkedHashMap<List<String>, List<JsonNode>>();
        int used = 0, review = 0;
        for (JsonNode object : report.path("objects")) {
            groups.computeIfAbsent(List.of(text(object,"page"), text(object,"frame"), text(object,"url")), k -> new ArrayList<>()).add(object);
            if (object.path("interacted").asBoolean()) used++;
            if (needsReview(object)) review++;
        }
        StringBuilder html = new StringBuilder("""
            <!doctype html><html lang="tr"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
            <title>TestTR • Obje keşif raporu</title>
            <style>
            :root{color-scheme:light;font-family:Segoe UI,Arial,sans-serif;color:#17283c;background:#f3f6fa}
            *{box-sizing:border-box}body{margin:0}main{max-width:1440px;margin:auto;padding:40px 28px}
            header{border-top:5px solid #12b8a6;padding:26px 30px;background:#132b46;color:white;border-radius:12px}
            .eyebrow{letter-spacing:2px;font-size:12px;color:#82e5da}h1{font-size:30px;margin:10px 0}h2{font-size:22px;margin:32px 0 16px}
            p{line-height:1.6}.muted{color:#607187;font-size:13px}.date{color:#bacee3;font-size:13px;overflow-wrap:anywhere}
            .stats{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin:24px 0}.stat{background:white;border:1px solid #dbe3ed;border-radius:10px;padding:20px}
            .stat strong{font-size:30px;display:block;margin-bottom:6px}.stat span{font-size:13px;color:#607187}
            .card{background:white;border:1px solid #dbe3ed;border-radius:10px;margin:16px 0;overflow:hidden}
            .scope{padding:18px 20px;border-bottom:1px solid #e4eaf1}.scope h3{font-size:16px;margin:0 0 6px}.url{font-size:13px;color:#607187;overflow-wrap:anywhere}
            .scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:13px;text-align:left}th{background:#edf2f7;color:#445870;font-size:12px}
            th,td{padding:13px 16px;border-bottom:1px solid #e4eaf1;vertical-align:top}tbody tr:last-child td{border:0}tbody tr:nth-child(even){background:#fafbfd}
            code{font-family:Consolas,monospace;font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere;color:#254b7a}
            .badge{display:inline-block;border-radius:5px;padding:4px 7px;white-space:nowrap;font-size:11px;background:#e5f5ef;color:#176748}
            .warn{background:#fff0d5;color:#835600}.neutral{background:#edf1f6;color:#53657b}.empty{padding:24px;color:#607187}
            .note{border-left:3px solid #12b8a6;padding:10px 16px;background:#eaf6f5;font-size:13px;line-height:1.7}
            footer{margin-top:24px;color:#607187;font-size:12px}
            @media(max-width:700px){main{padding:20px 12px}.stats{grid-template-columns:repeat(2,1fr)}header{padding:20px}h1{font-size:24px}}
            @media print{body{background:white}main{padding:0;max-width:none}.scroll{overflow:visible}header{color:#17283c;background:white;border:1px solid #ccc}.date{color:#607187}.stat{padding:12px}thead{display:table-header-group}tr{break-inside:avoid}th,td{padding:8px}.card{overflow:visible}}
            </style></head><body><main><header><div class="eyebrow">TESTTR DISCOVERY</div>
            <h1>Obje keşif raporu</h1><p>Keşfedilen ekran objeleri ve kullanıcı etkileşimleri</p><div class="date">Son güncelleme (UTC): 
            """);
        html.append(escape(timestamp(text(report,"generatedAt")))).append("</div></header><section class=\"stats\" aria-label=\"Özet\">");
        stat(html, report.path("objects").size(), "Keşfedilen obje"); stat(html, used, "Etkileşilen obje");
        stat(html, report.path("interactions").size(), "Kaydedilen etkileşim"); stat(html, review, "İnceleme gereken locator");
        html.append("</section><p class=\"note\">Locator tekilliği gözlemlendiği frame içinde kontrol edilir. Özellik tabanlı locator da sonraki açılışlarda kararlılık garantisi vermez. Yapısal locator’ları inceleyin. Görünürlük ve etkinlik son gözlemi belirtir; kaldırılmış objeler envanterde kalır.</p><h2>Obje envanteri</h2>");
        if (groups.isEmpty()) html.append("<div class=\"card empty\">Henüz obje keşfedilmedi.</div>");
        for (var group : groups.entrySet()) {
            var scope = group.getKey();
            html.append("<section class=\"card\"><div class=\"scope\"><h3>").append(escape(scope.get(0))).append(" / ").append(escape(scope.get(1)))
                .append(" · ").append(group.getValue().size()).append(" obje</h3><div class=\"url\">").append(escape(scope.get(2))).append("</div></div><div class=\"scroll\"><table><thead><tr><th scope=\"col\">Obje adı</th><th scope=\"col\">CSS selector</th><th scope=\"col\">Tür</th><th scope=\"col\">Locator</th><th scope=\"col\">Durum</th><th scope=\"col\">Kullanım</th></tr></thead><tbody>");
            for (JsonNode o : group.getValue()) {
                html.append("<tr><td><strong>").append(escape(text(o,"name"))).append("</strong></td><td><code>").append(escape(text(o,"css"))).append("</code></td><td>").append(escape(text(o,"type"))).append("</td><td>");
                badge(html, needsReview(o) ? "İnceleme gerekli" : "Özellik tabanlı", needsReview(o) ? "warn" : "");
                html.append("<div class=\"muted\">").append(o.path("matches").asInt()).append(" eşleşme</div></td><td>");
                badge(html, o.path("visible").asBoolean() ? "Görünür" : "Gizli", "neutral");
                html.append(" "); badge(html, o.path("enabled").asBoolean() ? "Etkin" : "Devre dışı", "neutral");
                html.append("</td><td>"); badge(html, o.path("interacted").asBoolean() ? "Etkileşildi" : "Keşfedildi", o.path("interacted").asBoolean() ? "" : "neutral");
                html.append("</td></tr>");
            }
            html.append("</tbody></table></div></section>");
        }
        html.append("<h2>Etkileşim akışı</h2>");
        if (report.path("interactions").isEmpty()) html.append("<div class=\"card empty\">Henüz etkileşim kaydedilmedi.</div>");
        else {
            html.append("<div class=\"card scroll\"><table><thead><tr><th scope=\"col\">Sıra / zaman (UTC)</th><th scope=\"col\">İşlem</th><th scope=\"col\">Obje / selector</th><th scope=\"col\">Sayfa ve kapsam</th><th scope=\"col\">Parametre</th></tr></thead><tbody>");
            for (JsonNode e : report.path("interactions")) {
                html.append("<tr><td><strong>#").append(e.path("sequence").asInt()).append("</strong><div class=\"muted\">").append(escape(timestamp(text(e,"time")))).append("</div></td><td>").append(escape(action(text(e,"action"))))
                    .append("</td><td><strong>").append(escape(text(e.path("object"),"name"))).append("</strong><br><code>").append(escape(text(e.path("object"),"css")))
                    .append("</code></td><td>").append(escape(text(e,"page"))).append(" / ").append(escape(text(e,"frame"))).append("<div class=\"url\">").append(escape(text(e,"url")))
                    .append("</div></td><td>").append(e.path("parameterRequired").asBoolean() ? "Gerekli · değer kaydedilmedi" : "—").append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        return html.append("<footer>TestTR Discovery · Çevrimdışı HTML raporu · Girilen alan değerleri kaydedilmez. DOM etiketleri ve selector özellikleri uygulamanın gösterdiği metinleri içerebilir.</footer></main></body></html>").toString();
    }
    private static boolean needsReview(JsonNode o) { return !"attribute".equals(text(o,"quality")) || o.path("matches").asInt() != 1; }
    private static String text(JsonNode node, String key) { return node.path(key).asText(""); }
    private static String timestamp(String value) {
        try { return java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.parse(value)); }
        catch (java.time.DateTimeException e) { return value; }
    }
    private static String action(String value) { return switch(value) { case "click" -> "Tıkla"; case "fill" -> "Doldur"; case "select" -> "Seç"; case "check" -> "İşaretle / kaldır"; default -> value; }; }
    private static void stat(StringBuilder html, int count, String label) { html.append("<div class=\"stat\"><strong>").append(count).append("</strong><span>").append(label).append("</span></div>"); }
    private static void badge(StringBuilder html, String label, String style) { html.append("<span class=\"badge ").append(style).append("\">").append(label).append("</span>"); }
    private static String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
