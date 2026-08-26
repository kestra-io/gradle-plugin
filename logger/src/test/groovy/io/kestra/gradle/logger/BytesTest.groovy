package io.kestra.gradle.logger

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class BytesTest {

    @Test
    void 'negative values are the not-available sentinel, never a size'() {
        assertEquals('n/a', Bytes.format(-1))
    }

    @Test
    void 'formats each unit band'() {
        assertEquals('0 B', Bytes.format(0))
        assertEquals('512 B', Bytes.format(512))
        assertEquals('1.0 KB', Bytes.format(1024))
        assertEquals('1.5 MB', Bytes.format((1.5 * 1024 * 1024) as long))
        assertEquals('2.0 GB', Bytes.format(2L * 1024 * 1024 * 1024))
    }
}
