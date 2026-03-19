package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyImporterTest {

    // Throwaway RSA 2048-bit key in OpenSSH format for use as a test fixture only.
    // Generated with: ssh-keygen -t rsa -b 2048 -N "" -f /tmp/haven_test_key
    // This key must never be used for real authentication.
    private val validRsaPem = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
        NhAAAAAwEAAQAAAQEAwCsJkuZffj9UnEKr33fxniFh+3PQ/Ef0sUS+dlT9RCmgRoMwnbLW
        bR7sKuz4k6xYcXm5CgxkrlRNsjfleR1TbAreMm3sbLU6yhftCrNMPJCPpj1bx2Usqdi6Ia
        qYIJXj0LZFCdEdRDQhFa/X1JhCSKXh38aNR7o/jgbzjIPFZXG7hDTJAFS4xzhE9YCmAxkQ
        0+xNShYk4VIFtVWtkNK/pUDFTXf6gvgDXL+cMBAXlPshyMn7ZNxRAwP1KCp0JOtfNNZRzw
        HZuD1BFneNvLZPNbErEv4zi3XD5TZX35TtCvUNjnUs+ySosZI044tu2mAhgfYBP8qK+WGp
        fDGI9kFnWQAAA8jBPPCIwTzwiAAAAAdzc2gtcnNhAAABAQDAKwmS5l9+P1ScQqvfd/GeIW
        H7c9D8R/SxRL52VP1EKaBGgzCdstZtHuwq7PiTrFhxebkKDGSuVE2yN+V5HVNsCt4ybexs
        tTrKF+0Ks0w8kI+mPVvHZSyp2LohqpgglePQtkUJ0R1ENCEVr9fUmEJIpeHfxo1Huj+OBv
        OMg8VlcbuENMkAVLjHOET1gKYDGRDT7E1KFiThUgW1Va2Q0r+lQMVNd/qC+ANcv5wwEBeU
        +yHIyftk3FEDA/UoKnQk61801lHPAdm4PUEWd428tk81sSsS/jOLdcPlNlfflO0K9Q2OdS
        z7JKixkjTji27aYCGB9gE/yor5Yal8MYj2QWdZAAAAAwEAAQAAAQBR16J+sWW3J3K6ED0R
        8gvx3GbWCFfXsi+Y9d2mGQE6b/4GOeZRK3LeS36qs30Uq6CJR52SlX+lrVrfzaWKJP6784
        75bE52Z+LvYiw+0+jinHDJjLVTYRgaCCcRoo2ixyOc5pvVl/1+aDM1AMyLiwMj3J4rx2yx
        QTXDH9vHGvHNh1sH9NUXNETpEg11m9wQeY2f/vOhH54PucZLYXrHMAYu3kRqO42FeRHeWC
        P3R7EOA9RWMlYykpkAGIEEK+BjIZo89SFB8cZqDzDV9UKYTwPi4AQYVSgA7lTIJuQSbuhV
        bB/t/PFYoXlHLWZ0MfFkLZ40GmppwXyWa8C8tRoWv6THAAAAgQCkZWwtsw3lJ+J4+ptDMa
        pnEoJ92lKZXjMxaFAcERbMgQfJuEYE/YheHuG4LOARRGdR3nTj9bWPSezu4S/waV9BGns5
        iBIenFxUDtrKtVx0g9dABp/nKPbFYLszogEnkH8eik6YpcVEDoMAVJxhRycsoOEwYufSeT
        Q6C92X8ujMewAAAIEA595thhVDg4DwHhDB7sz1ILLljmjN0RMpebj3KlElfIOgckCQyvrP
        Pv6OnSGjMMbotu+HnBFiHxdJy+RWavNOgQc3xaitnQnav0Od3VchtZ3JjtT1hs7rDk9pJa
        0C/ZTt9fLapE/bjvp04GN9cpZLI2McFgGjBoy3Xes1Ok2s4wcAAACBANQq4acoMbwpae57
        CVmYkSVxyv1puHz5fgYOnTNoAdnMWyb/fn7yMbKC2eb2b1tH68Xt8LBZ27RvNc0eTqYyiN
        2DP7e/Voy1Aq9qX3pxWf12SBkyRMixBNghjlSHhRek+70j6YclWJxinNqu9wlimequ725r
        6w//dktAErFpxOqfAAAADGlhbkBtc2ktejc5MAECAwQFBg==
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent().toByteArray()

    // ---- import() with invalid input ----

    @Test(expected = IllegalArgumentException::class)
    fun `import with empty bytes throws IllegalArgumentException`() {
        SshKeyImporter.import(byteArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import with garbage bytes throws IllegalArgumentException`() {
        val garbage = ByteArray(256) { it.toByte() }
        SshKeyImporter.import(garbage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import with short random bytes throws IllegalArgumentException`() {
        SshKeyImporter.import(byteArrayOf(0x00, 0x01, 0x02))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import with arbitrary text bytes throws IllegalArgumentException`() {
        SshKeyImporter.import("not a key".toByteArray())
    }

    // ---- import() happy path ----

    @Test
    fun `import valid RSA PEM key returns ImportedKey`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertNotNull(result)
    }

    @Test
    fun `import valid RSA PEM key returns ssh-rsa key type`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertEquals("ssh-rsa", result.keyType)
    }

    @Test
    fun `import valid RSA PEM key preserves original file bytes`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertTrue(validRsaPem.contentEquals(result.privateKeyBytes))
    }

    @Test
    fun `import valid RSA PEM key produces SHA256 fingerprint`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertTrue(
            "Expected fingerprint to start with SHA256:, got: ${result.fingerprintSha256}",
            result.fingerprintSha256.startsWith("SHA256:")
        )
    }

    @Test
    fun `import valid RSA PEM key produces openssh public key line`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertTrue(
            "Expected public key to start with key type, got: ${result.publicKeyOpenSsh}",
            result.publicKeyOpenSsh.startsWith("ssh-rsa ")
        )
    }

    @Test
    fun `import is deterministic for same input`() {
        val r1 = SshKeyImporter.import(validRsaPem)
        val r2 = SshKeyImporter.import(validRsaPem)
        assertEquals(r1.keyType, r2.keyType)
        assertEquals(r1.fingerprintSha256, r2.fingerprintSha256)
        assertEquals(r1.publicKeyOpenSsh, r2.publicKeyOpenSsh)
    }
}
