package kr.mujoong.realtime.testing;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * 앱 전체 문맥을 시험에서 띄울 때 시험 소스의 클래스(시험 클래스 · 그 안의 설정 · 이 시험 보조 패키지)를 부품 스캔에서 뺀다. Boot의
 * {@code @SpringBootTest}가 쓰는 걸러내기와 같은 일을 {@code SpringApplicationBuilder} 기동에서 한다(시험 소스 전용).
 */
final class ItTypeExcludeFilter extends TypeExcludeFilter {

    private static final String TESTING_PACKAGE = "kr.mujoong.realtime.testing.";

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
        String name = metadataReader.getClassMetadata().getClassName();
        int nested = name.indexOf('$');
        String outer = nested < 0 ? name : name.substring(0, nested);
        return outer.endsWith("Test") || outer.endsWith("IT") || outer.startsWith(TESTING_PACKAGE);
    }

    @Override
    public boolean equals(Object other) {
        return other != null && other.getClass() == getClass();
    }

    @Override
    public int hashCode() {
        return ItTypeExcludeFilter.class.hashCode();
    }
}
