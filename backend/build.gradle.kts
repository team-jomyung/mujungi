// 무중 백엔드 루트 빌드 (U1 3.5 Step 3)
// 모듈 공통 규약(Java 21 툴체인 · 컴파일 옵션 · BOM · 시험 · JaCoCo 80%)은
// build-logic의 mujoong.java-conventions 플러그인이 모듈마다 건다(J105).
// 루트는 clean · check · assemble 수명 주기만 가진다.
plugins {
    base
}
