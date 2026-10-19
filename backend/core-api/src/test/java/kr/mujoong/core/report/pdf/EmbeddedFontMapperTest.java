package kr.mujoong.core.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("result-report")
class EmbeddedFontMapperTest {
    @BeforeEach void install() { EmbeddedFontMapper.install(); }
    @Test void installsOnce() {
        var before = FontMappers.instance();
        EmbeddedFontMapper.install();
        assertThat(FontMappers.instance()).isSameAs(before);
    }
    @Test void regularMappingContainsKoreanGlyphs() throws Exception {
        var font = FontMappers.instance().getTrueTypeFont("Helvetica", null);
        assertThat(font.isFallback()).isTrue();
        assertThat(font.getFont().getUnicodeCmapLookup().getGlyphId('한')).isPositive();
    }
    @Test void boldUsesSeparateEmbeddedFace() {
        var mapper = FontMappers.instance();
        assertThat(mapper.getTrueTypeFont("Helvetica-Bold", null).getFont())
                .isNotSameAs(mapper.getTrueTypeFont("Helvetica", null).getFont());
    }
    @Test void absentFontNameFallsBackToRegularInMemory() {
        var mapper = FontMappers.instance();
        assertThat(mapper.getFontBoxFont(null, null).getFont())
                .isSameAs(mapper.getTrueTypeFont("Helvetica", null).getFont());
    }
    @Test void cidFallbackDoesNotLookForSystemFonts() {
        var mapper = FontMappers.instance();
        var mapping = mapper.getCIDFont("Unknown-CJK", null, null);
        assertThat(mapping.isCIDFont()).isFalse();
        assertThat(mapping.getTrueTypeFont()).isSameAs(mapper.getTrueTypeFont("Helvetica", null).getFont());
    }
}
