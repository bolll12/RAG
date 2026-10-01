package com.pingan.rag;

import org.junit.jupiter.api.Test;
import java.nio.charset.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmlTextExtractorTest {
    @Test void extractsStructureEntitiesAndExcludesActiveContent() {
        String html="<html><head><title>报销制度</title><style>css-secret</style></head><body>"
            +"<h1>交通费用</h1><p>每日限额 <b>200</b> 元 &amp; 需发票。</p><p>第二段<br>下一行</p>"
            +"<table><tr><th>类别</th><th>上限</th></tr><tr><td>住宿</td><td>500</td></tr></table>"
            +"<script>script-secret</script><template>template-secret</template><div hidden>hidden-secret</div>"
            +"<iframe src='http://127.0.0.1:1/'>frame-secret</iframe></body></html>";
        String result=DocumentParser.parse("policy.HTML",html.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.startsWith("报销制度\n\n"));
        assertTrue(result.contains("交通费用\n"));
        assertTrue(result.contains("每日限额 200 元 & 需发票。"));
        assertTrue(result.contains("第二段\n下一行"));
        assertTrue(result.contains("住宿\t500"));assertFalse(result.contains("secret"));
    }
    @Test void detectsDeclaredChineseEncodingAndRepairsFragments() {
        String html="<meta charset=GB18030><p>中文政策<p>第二段";
        String result=DocumentParser.parse("policy.htm",html.getBytes(Charset.forName("GB18030")));
        assertTrue(result.contains("中文政策"));assertTrue(result.contains("第二段"));
    }
    @Test void rejectsScriptOnlyPages() {
        assertThrows(IllegalArgumentException.class,()->DocumentParser.parse("app.html",
                "<title>空页面</title><script>document.write('动态正文')</script>".getBytes(StandardCharsets.UTF_8)));
    }
}
