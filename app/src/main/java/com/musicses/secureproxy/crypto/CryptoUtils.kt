package com.musicses.secureproxy.crypto

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具类 - 实现 AES-256-GCM 加密
 */
object CryptoUtils {

    private const val IV_LENGTH = 12
    private const val AUTH_TAG_LENGTH = 16
    const val OVERHEAD = IV_LENGTH + AUTH_TAG_LENGTH
    private const val TAG_LENGTH_BITS = AUTH_TAG_LENGTH * 8

    /**
     * 密钥派生 - 使用 HKDF
     */
    fun deriveKeys(sharedKey: ByteArray, salt: ByteArray): KeyPair {
        // 使用简化的 HKDF 实现
        val keyMaterial = hkdfExpand(
            hkdfExtract(salt, sharedKey),
            "secure-proxy-v1".toByteArray(),
            64
        )

        val sendKey = keyMaterial.copyOfRange(0, 32)
        val recvKey = keyMaterial.copyOfRange(32, 64)

        return KeyPair(sendKey, recvKey)
    }

    /**
     * HKDF Extract
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        return hmacSha256(salt, ikm)
    }

    /**
     * HKDF Expand
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArray(length)
        var offset = 0
        var counter = 1
        var t = ByteArray(0)

        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()

            val copyLength = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLength)
            offset += copyLength
            counter++
        }

        return result
    }

    /**
     * AES-256-GCM 加密
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = generateRandomBytes(IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey: SecretKey = SecretKeySpec(key, "AES")
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        val ciphertext = cipher.doFinal(plaintext)

        // 组合: IV + Ciphertext + AuthTag
        // 注意: GCM 模式下,doFinal 返回的数据已经包含了认证标签
        return ByteBuffer.allocate(IV_LENGTH + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    /**
     * AES-256-GCM 解密
     */
    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < OVERHEAD) {
            throw IllegalArgumentException("Invalid data length: ${data.size}")
        }

        val iv = data.copyOfRange(0, IV_LENGTH)
        val ciphertext = data.copyOfRange(IV_LENGTH, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey: SecretKey = SecretKeySpec(key, "AES")
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * HMAC-SHA256
     */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /**
     * 生成随机字节
     */
    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * 常量时间比较
     */
    fun constantTimeCompare(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false

        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    /**
     * Hex 转字节数组
     */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * 字节数组转 Hex
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 密钥对
     */
    data class KeyPair(
        val sendKey: ByteArray,
        val recvKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as KeyPair
            if (!sendKey.contentEquals(other.sendKey)) return false
            if (!recvKey.contentEquals(other.recvKey)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = sendKey.contentHashCode()
            result = 31 * result + recvKey.contentHashCode()
            return result
        }
    }
}