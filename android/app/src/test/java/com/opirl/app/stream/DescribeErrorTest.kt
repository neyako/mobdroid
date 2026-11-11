package com.opirl.app.stream

import kotlin.test.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DescribeErrorTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "failed to connect to /10.0.0.1 (port 1935) after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)|Server refused the connection",
            "Unable to resolve host \"live.example\": No address associated with hostname|Server not found",
            "Read timed out|Connection timed out",
            "SRT handshake rejected: bad stream id|SRT handshake rejected: bad stream id",
        ],
    )
    fun `connection failures read as short messages`(reason: String, expected: String) {
        assertEquals(expected, describeError(reason))
    }
}
