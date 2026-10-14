package kr.mujoong.core.report.pdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.fontbox.FontBoxFont;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.font.CIDFontMapping;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.springframework.core.io.ClassPathResource;

/**
 * BR10.6·NFR12: PDFBox 기본 글꼴 탐색은 첫 사용 때 디스크 캐시를 만든다.
 * 앱의 PDF 작업은 클래스패스 글꼴만 사용하므로 대체 글꼴도 메모리에서 공급한다.
 * 파싱된 두 글꼴은 PDFBox의 전역 매핑과 같은 JVM 수명으로 유지한다.
 */
final class EmbeddedFontMapper implements FontMapper {
    private static boolean installed;
    private final TrueTypeFont regular;
    private final TrueTypeFont bold;

    private EmbeddedFontMapper() throws IOException {
        regular = load("Regular");
        bold = load("Bold");
    }

    static synchronized void install() {
        if (installed) return;
        try {
            FontMappers.set(new EmbeddedFontMapper());
            installed = true;
        } catch (IOException failure) {
            throw new UncheckedIOException("Report font resource unavailable", failure);
        }
    }

    private static TrueTypeFont load(String weight) throws IOException {
        try (var input = new ClassPathResource("report/fonts/NotoSansKR-" + weight + ".ttf").getInputStream()) {
            return new TTFParser().parse(input);
        }
    }

    private TrueTypeFont select(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("bold") ? bold : regular;
    }

    @Override
    public FontMapping<TrueTypeFont> getTrueTypeFont(String name, PDFontDescriptor descriptor) {
        return new FontMapping<>(select(name), true);
    }

    @Override
    public FontMapping<FontBoxFont> getFontBoxFont(String name, PDFontDescriptor descriptor) {
        return new FontMapping<>(select(name), true);
    }

    @Override
    public CIDFontMapping getCIDFont(String name, PDFontDescriptor descriptor, PDCIDSystemInfo info) {
        return new CIDFontMapping(null, select(name), true);
    }
}
