package com.qubit.quantbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ApiResponseCacheTest {
    @Test
    fun readReturnsLastSuccessfulResponseWithinMaxAge() {
        val dir = Files.createTempDirectory("quant-api-cache").toFile()
        val cache = ApiResponseCache(dir)

        cache.write("https://example.test/portfolio/us", """{"ok":true}""")

        assertEquals("""{"ok":true}""", cache.read("https://example.test/portfolio/us", 60_000)?.raw)
        assertNull(cache.read("https://example.test/portfolio/kr", 60_000))
    }

    @Test
    fun readDropsExpiredResponse() {
        val dir = Files.createTempDirectory("quant-api-cache").toFile()
        val cache = ApiResponseCache(dir)

        cache.write("https://example.test/news", """{"items":[]}""")

        assertNull(cache.read("https://example.test/news", -1))
    }
}
